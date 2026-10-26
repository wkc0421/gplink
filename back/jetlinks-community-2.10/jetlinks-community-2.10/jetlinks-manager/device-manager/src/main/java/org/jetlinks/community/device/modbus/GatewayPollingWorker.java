package org.jetlinks.community.device.modbus;

import lombok.extern.slf4j.Slf4j;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.event.EventBus;
import org.jetlinks.core.message.ReadPropertyMessageSender;
import org.jetlinks.core.message.property.ReadPropertyMessageReply;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

@Slf4j
final class GatewayPollingWorker {

    private static final Duration LEASE_TTL = Duration.ofSeconds(15);
    private static final Duration RENEW_INTERVAL = Duration.ofSeconds(5);

    private final String gatewayId;
    private final String nodeId;
    private final String ownerToken = UUID.randomUUID().toString();
    private final String ownerValue;
    private final ModbusPollingLeaseService leaseService;
    private final ModbusPollPlanResolver planResolver;
    private final DeviceRegistry registry;
    private final ModbusPollReportPublisher reportPublisher;
    private final EventBus eventBus;
    private final Scheduler scheduler;
    private final LongSupplier clock;
    private final Object lifecycle = new Object();
    private volatile boolean stopped;
    private boolean applicationShutdown;
    private final Sinks.Empty<Void> stoppedSignal = Sinks.empty();
    private final Sinks.Empty<Void> shutdownSignal = Sinks.empty();
    private final Disposable.Swap frameCancellation = Disposables.swap();
    private Mono<Boolean> acquisition;
    private Mono<Boolean> startup;
    private Mono<Void> stopCompletion;

    private final AtomicBoolean leaseValid = new AtomicBoolean();
    private final AtomicLong leaseValidUntil = new AtomicLong();
    private volatile List<ModbusPollPlan> plans = new ArrayList<>();
    private volatile Disposable loop;
    private volatile Disposable renewal;

    GatewayPollingWorker(String gatewayId,
                         String nodeId,
                         ModbusPollingLeaseService leaseService,
                         ModbusPollPlanResolver planResolver,
                         DeviceRegistry registry,
                         ModbusPollReportPublisher reportPublisher,
                         EventBus eventBus) {
        this(gatewayId, nodeId, leaseService, planResolver, registry, reportPublisher, eventBus,
             Schedulers.parallel(), System::currentTimeMillis);
    }

    GatewayPollingWorker(String gatewayId,
                         String nodeId,
                         ModbusPollingLeaseService leaseService,
                         ModbusPollPlanResolver planResolver,
                         DeviceRegistry registry,
                         ModbusPollReportPublisher reportPublisher,
                         EventBus eventBus,
                         Scheduler scheduler,
                         LongSupplier clock) {
        this.gatewayId = gatewayId;
        this.nodeId = nodeId;
        this.ownerValue = ownerToken + "|" + nodeId;
        this.leaseService = leaseService;
        this.planResolver = planResolver;
        this.registry = registry;
        this.reportPublisher = reportPublisher;
        this.eventBus = eventBus;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    Mono<Boolean> start() {
        return Mono.defer(() -> {
            synchronized (lifecycle) {
                if (stopped) {
                    return Mono.just(false);
                }
                if (startup == null) {
                    // Observe the Redis result even if startup is cancelled. A late
                    // successful acquisition must be released before a reconnect.
                    acquisition = Mono.defer(() -> {
                        synchronized (lifecycle) {
                            return stopped ? Mono.just(false)
                                    : leaseService.acquire(gatewayId, ownerValue, LEASE_TTL);
                        }
                    })
                            .takeUntilOther(shutdownSignal.asMono())
                            .defaultIfEmpty(false)
                            .cache();
                    startup = startAcquired()
                            .takeUntilOther(stoppedSignal.asMono())
                            .defaultIfEmpty(false)
                            .onErrorResume(error -> stop()
                                    .onErrorResume(releaseError -> Mono.empty())
                                    .then(Mono.error(error)))
                            .cache();
                }
                return startup;
            }
        }).doOnCancel(this::stop);
    }

    private Mono<Boolean> startAcquired() {
        return acquisition
                .flatMap(acquired -> {
                    if (!acquired) {
                        return Mono.just(false);
                    }
                    synchronized (lifecycle) {
                        if (stopped) {
                            return Mono.just(false);
                        }
                        markLeaseValid();
                        startLeaseRenewal();
                    }
                    return reloadPlans()
                            .then(Mono.defer(() -> {
                                synchronized (lifecycle) {
                                    if (stopped) {
                                        return Mono.just(false);
                                    }
                                    if (isLeaseValid()) {
                                        startLoops();
                                        return Mono.just(true);
                                    }
                                }
                                return stop().thenReturn(false);
                            }));
                });
    }

    Mono<Void> reloadPlans() {
        return Flux.defer(() -> stopped ? Flux.empty() : planResolver.resolve(gatewayId))
                .takeUntilOther(stoppedSignal.asMono())
                .collectList()
                .doOnNext(resolved -> {
                    synchronized (lifecycle) {
                        if (stopped) {
                            return;
                        }
                        Map<String, ModbusPollPlan> previous = plans.stream()
                                .collect(Collectors.toMap(ModbusPollPlan::getId, plan -> plan));
                        // A metadata save must not start another first round for an unchanged plan.
                        this.plans = resolved.stream().map(plan -> {
                            ModbusPollPlan existing = previous.get(plan.getId());
                            return existing != null && existing.hasSameDefinition(plan) ? existing : plan;
                        }).collect(Collectors.toList());
                    }
                })
                .then();
    }

    Mono<Void> stop() {
        stopLocally(true);
        Mono<Void> completion;
        boolean beginRelease = false;
        synchronized (lifecycle) {
            if (stopCompletion == null) {
                beginRelease = true;
                stopCompletion = (acquisition == null ? Mono.<Boolean>empty() : acquisition
                        // A transport error can leave Redis's outcome unknown;
                        // the owner-token guarded release is safe in that case.
                        .onErrorReturn(true))
                        .filter(Boolean.TRUE::equals)
                        .flatMap(ignore -> Mono.defer(() -> {
                            synchronized (lifecycle) {
                                return applicationShutdown ? Mono.empty()
                                        : leaseService.release(gatewayId, ownerValue).then();
                            }
                        }))
                        .then()
                        .takeUntilOther(shutdownSignal.asMono())
                        .cache();
            }
            completion = stopCompletion;
        }
        if (beginRelease) {
            // Stopping can cancel the startup branch that called us before it
            // subscribes to the returned barrier. Keep cleanup independently owned.
            completion.subscribe(null,
                    error -> log.warn("Failed to release stopped Modbus worker [{}]", gatewayId, error));
        }
        return completion;
    }

    Mono<Void> whenStopped() {
        return stoppedSignal.asMono();
    }

    /**
     * Stop local work during application shutdown without touching Redis or the
     * event bus. Both infrastructures may already be stopping; the lease is
     * deliberately left to expire and the protocol executor is disposed by its
     * protocol support lifecycle.
     */
    void shutdownLocally() {
        synchronized (lifecycle) {
            applicationShutdown = true;
        }
        stopLocally(false);
        frameCancellation.dispose();
        shutdownSignal.tryEmitEmpty();
    }

    boolean isLeaseValid() {
        return !stopped && leaseValid.get() && clock.getAsLong() < leaseValidUntil.get();
    }

    private void startLeaseRenewal() {
        // Plan loading may take longer than the lease TTL; keep ownership until it is ready.
        renewal = Flux
                .interval(RENEW_INTERVAL, scheduler)
                .concatMap(ignore -> Mono.defer(() -> {
                            synchronized (lifecycle) {
                                return stopped ? Mono.empty()
                                        : leaseService.renew(gatewayId, ownerValue, LEASE_TTL);
                            }
                        })
                        .doOnNext(renewed -> {
                            if (renewed) {
                                markLeaseValid();
                            } else {
                                loseLease("renew rejected");
                            }
                        })
                        .onErrorResume(error -> {
                            loseLease(error.getMessage());
                            return Mono.empty();
                        }))
                .subscribe();
    }

    private void startLoops() {
        loop = Flux
                .interval(Duration.ZERO, Duration.ofMillis(200), scheduler)
                .onBackpressureLatest()
                .concatMap(ignore -> executeDuePlan()
                        .onErrorResume(error -> {
                            log.warn("Modbus poll worker [{}] tick failed", gatewayId, error);
                            return Mono.empty();
                        }))
                .subscribe();
    }

    private Mono<Void> executeDuePlan() {
        if (!isLeaseValid()) {
            loseLease("local lease expired");
            return Mono.empty();
        }
        long now = clock.getAsLong();
        List<ModbusPollPlan> due = plans
                .stream()
                .filter(plan -> plan.isDue(now))
                .sorted(Comparator.comparingLong(ModbusPollPlan::getNextFireTime))
                .collect(Collectors.toList());
        if (due.isEmpty()) {
            return Mono.empty();
        }
        List<ModbusPollPlan> fixedDelayRound = new ArrayList<>();
        return Flux.fromIterable(due)
                .concatMap(plan -> {
                    if (!isLeaseValid()) {
                        return Mono.empty();
                    }
                    long plannedFireTime = plan.claimFire(clock.getAsLong());
                    if (plan.getScheduleType() == ModbusPollPlan.ScheduleType.FIXED_DELAY) {
                        fixedDelayRound.add(plan);
                        return executePlan(plan, plannedFireTime, UUID.randomUUID().toString());
                    }
                    return executePlan(plan, plannedFireTime, UUID.randomUUID().toString())
                            .doFinally(ignore -> {
                                long completed = clock.getAsLong();
                                plan.mergeCronTrigger(completed);
                                plan.complete(completed);
                            });
                })
                .then()
                .doFinally(ignore -> {
                    long completed = clock.getAsLong();
                    // All products due in this gateway round finish before their delay starts.
                    fixedDelayRound.forEach(plan -> plan.complete(completed));
                });
    }

    private Mono<Void> executePlan(ModbusPollPlan plan,
                                   long plannedFireTime,
                                   String cycleId) {
        return Flux
                .fromIterable(plan.getDeviceIds())
                .concatMap(deviceId -> {
                    if (!isLeaseValid()) {
                        return Mono.empty();
                    }
                    Mono<Void> read = pollDevice(plan, deviceId, plannedFireTime, cycleId);
                    if (plan.getDeviceIntervalMs() > 0) {
                        read = read
                                .then(Mono.delay(Duration.ofMillis(plan.getDeviceIntervalMs()), scheduler))
                                .then();
                    }
                    return read;
                })
                .then();
    }

    private Mono<Void> pollDevice(ModbusPollPlan plan,
                                  String deviceId,
                                  long plannedFireTime,
                                  String cycleId) {
        long startedAt = clock.getAsLong();
        String messageId = deterministicMessageId(plan.getId(), plannedFireTime, deviceId);
        PollAccumulator accumulator = new PollAccumulator(plan.getPropertyIds());
        return registry
                .getDevice(deviceId)
                .flatMap(device -> readWithRetry(
                        device,
                        plan,
                        cycleId,
                        messageId,
                        accumulator,
                        new ArrayList<>(plan.getPropertyIds()),
                        plan.getRetryCount()))
                .then(Mono.defer(() -> {
                    if (accumulator.properties.isEmpty() || !isLeaseValid()) {
                        return Mono.empty();
                    }
                    long completedAt = clock.getAsLong();
                    ModbusPollReportPublisher.PollCycleResult result =
                            new ModbusPollReportPublisher.PollCycleResult(
                                    gatewayId,
                                    deviceId,
                                    plan.getId(),
                                    cycleId,
                                    messageId,
                                    startedAt,
                                    completedAt,
                                    accumulator.lastSuccessTime,
                                    accumulator.failedCount(),
                                    accumulator.properties,
                                    accumulator.sourceTimes);
                    return reportPublisher.publish(result, this::isLeaseValid).then();
                }))
                .onErrorResume(error -> {
                    log.warn("Modbus polling failed: gateway={} device={} plan={}",
                            gatewayId, deviceId, plan.getId(), error);
                    return Mono.empty();
                });
    }

    private Mono<Void> readWithRetry(DeviceOperator device,
                                     ModbusPollPlan plan,
                                     String cycleId,
                                     String messageId,
                                     PollAccumulator accumulator,
                                     List<String> properties,
                                     int retriesLeft) {
        if (properties.isEmpty() || !isLeaseValid()) {
            return Mono.empty();
        }
        ReadPropertyMessageSender sender = device
                .messageSender()
                .readProperty()
                .read(properties)
                .messageId(messageId)
                .header("modbusRequestSource", "POLLING")
                .header("modbusLogicalRequestId", messageId)
                .header("modbusPollPlanId", plan.getId())
                .header("modbusPollCycleId", cycleId)
                .header("modbusPollLeaseOwnerToken", ownerToken)
                .header("modbusPollLeaseExpiresAt", leaseValidUntil.get())
                .header("modbusPollFrameIntervalMs", plan.getFrameIntervalMs());

        return sender
                .send()
                .doOnNext(accumulator::accept)
                .then()
                .onErrorResume(error -> Mono.empty())
                .then(Mono.defer(() -> {
                    List<String> remaining = accumulator.remaining(properties);
                    if (remaining.isEmpty() || retriesLeft <= 0 || !isLeaseValid()) {
                        return Mono.empty();
                    }
                    return readWithRetry(
                            device,
                            plan,
                            cycleId,
                            messageId,
                            accumulator,
                            remaining,
                            retriesLeft - 1);
                }));
    }

    private void markLeaseValid() {
        synchronized (lifecycle) {
            if (!stopped) {
                leaseValidUntil.set(clock.getAsLong() + LEASE_TTL.toMillis());
                leaseValid.set(true);
            }
        }
    }

    private void loseLease(String reason) {
        if (leaseValid.compareAndSet(true, false)) {
            log.warn("Modbus poll worker [{}] lost lease: {}", gatewayId, reason);
            cancelQueuedFrames();
        }
    }

    private void cancelQueuedFrames() {
        synchronized (lifecycle) {
            if (!applicationShutdown) {
                frameCancellation.update(eventBus
                        .publish(ModbusPollingCoordinator.LEASE_LOST_TOPIC, gatewayId + "|" + ownerToken)
                        .subscribe(null, error -> log.warn("Failed to cancel Modbus frames [{}]", gatewayId, error)));
            }
        }
    }

    private void stopLocally(boolean notifyExecutor) {
        Disposable previousLoop;
        Disposable previousRenewal;
        synchronized (lifecycle) {
            if (stopped) {
                return;
            }
            stopped = true;
            leaseValid.set(false);
            previousLoop = loop;
            previousRenewal = renewal;
            loop = null;
            renewal = null;
            plans = new ArrayList<>();
        }
        dispose(previousLoop);
        dispose(previousRenewal);
        stoppedSignal.tryEmitEmpty();
        if (notifyExecutor) {
            cancelQueuedFrames();
        }
    }

    private String deterministicMessageId(String planId, long plannedFireTime, String deviceId) {
        return UUID
                .nameUUIDFromBytes(
                        (planId + "|" + plannedFireTime + "|" + deviceId)
                                .getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private void dispose(Disposable disposable) {
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    private static final class PollAccumulator {
        final Set<String> expected;
        final Map<String, Object> properties = new LinkedHashMap<>();
        final Map<String, Long> sourceTimes = new LinkedHashMap<>();
        long lastSuccessTime;

        private PollAccumulator(List<String> expected) {
            this.expected = new LinkedHashSet<>(expected);
        }

        void accept(ReadPropertyMessageReply reply) {
            if (!reply.isSuccess() || reply.getProperties() == null) {
                return;
            }
            long responseTime = reply.getTimestamp() > 0
                    ? reply.getTimestamp()
                    : System.currentTimeMillis();
            reply.getProperties().forEach((property, value) -> {
                properties.put(property, value);
                long sourceTime = reply
                        .getPropertySourceTime(property)
                        .orElse(responseTime);
                sourceTimes.put(property, sourceTime);
                lastSuccessTime = Math.max(lastSuccessTime, sourceTime);
            });
        }

        List<String> remaining(List<String> attempted) {
            List<String> remaining = new ArrayList<>();
            for (String property : attempted) {
                if (!properties.containsKey(property)) {
                    remaining.add(property);
                }
            }
            return remaining;
        }

        int failedCount() {
            int failed = 0;
            for (String property : expected) {
                if (!properties.containsKey(property)) {
                    failed++;
                }
            }
            return failed;
        }
    }
}
