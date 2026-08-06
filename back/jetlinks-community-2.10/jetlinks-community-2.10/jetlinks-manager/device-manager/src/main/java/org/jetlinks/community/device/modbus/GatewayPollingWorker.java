package org.jetlinks.community.device.modbus;

import lombok.extern.slf4j.Slf4j;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.event.EventBus;
import org.jetlinks.core.message.ReadPropertyMessageSender;
import org.jetlinks.core.message.property.ReadPropertyMessageReply;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
        this.gatewayId = gatewayId;
        this.nodeId = nodeId;
        this.ownerValue = ownerToken + "|" + nodeId;
        this.leaseService = leaseService;
        this.planResolver = planResolver;
        this.registry = registry;
        this.reportPublisher = reportPublisher;
        this.eventBus = eventBus;
    }

    Mono<Boolean> start() {
        return leaseService
                .acquire(gatewayId, ownerValue, LEASE_TTL)
                .flatMap(acquired -> {
                    if (!acquired) {
                        return Mono.just(false);
                    }
                    markLeaseValid();
                    return reloadPlans()
                            .then(Mono.fromRunnable(this::startLoops))
                            .thenReturn(true);
                });
    }

    Mono<Void> reloadPlans() {
        return planResolver
                .resolve(gatewayId)
                .collectList()
                .doOnNext(resolved -> this.plans = resolved)
                .then();
    }

    Mono<Void> stop() {
        stopLocally(true);
        return leaseService.release(gatewayId, ownerValue).then();
    }

    /**
     * Stop local work during application shutdown without touching Redis or the
     * event bus. Both infrastructures may already be stopping; the lease is
     * deliberately left to expire and the protocol executor is disposed by its
     * protocol support lifecycle.
     */
    void shutdownLocally() {
        stopLocally(false);
    }

    boolean isLeaseValid() {
        return leaseValid.get() && System.currentTimeMillis() < leaseValidUntil.get();
    }

    private void startLoops() {
        renewal = Flux
                .interval(RENEW_INTERVAL, Schedulers.parallel())
                .concatMap(ignore -> leaseService
                        .renew(gatewayId, ownerValue, LEASE_TTL)
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

        loop = Flux
                .interval(Duration.ZERO, Duration.ofMillis(200), Schedulers.parallel())
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
        long now = System.currentTimeMillis();
        ModbusPollPlan due = plans
                .stream()
                .filter(plan -> plan.isDue(now))
                .min(Comparator.comparingLong(ModbusPollPlan::getNextFireTime))
                .orElse(null);
        if (due == null) {
            return Mono.empty();
        }
        long plannedFireTime = due.claimFire(now);
        String cycleId = UUID.randomUUID().toString();
        return executePlan(due, plannedFireTime, cycleId)
                .doFinally(ignore -> {
                    long completed = System.currentTimeMillis();
                    due.mergeCronTrigger(completed);
                    due.complete(completed);
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
                                .then(Mono.delay(Duration.ofMillis(plan.getDeviceIntervalMs())))
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
        long startedAt = System.currentTimeMillis();
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
                    long completedAt = System.currentTimeMillis();
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
        leaseValidUntil.set(System.currentTimeMillis() + LEASE_TTL.toMillis());
        leaseValid.set(true);
    }

    private void loseLease(String reason) {
        if (leaseValid.compareAndSet(true, false)) {
            log.warn("Modbus poll worker [{}] lost lease: {}", gatewayId, reason);
            cancelQueuedFrames();
        }
    }

    private void cancelQueuedFrames() {
        eventBus
                .publish(
                        ModbusPollingCoordinator.LEASE_LOST_TOPIC,
                        gatewayId + "|" + ownerToken)
                .subscribe();
    }

    private void stopLocally(boolean notifyExecutor) {
        leaseValid.set(false);
        dispose(loop);
        dispose(renewal);
        loop = null;
        renewal = null;
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
