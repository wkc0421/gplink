package org.jetlinks.community.device.modbus;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.crud.events.EntityDeletedEvent;
import org.hswebframework.web.crud.events.EntityModifyEvent;
import org.hswebframework.web.crud.events.EntitySavedEvent;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.device.session.DeviceSessionEvent;
import org.jetlinks.core.device.session.DeviceSessionManager;
import org.jetlinks.core.event.EventBus;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns one plan-only worker for every locally active parent gateway session.
 * Actual Modbus frame serialization remains in the protocol request executor.
 */
@Slf4j
@Component
public class ModbusPollingCoordinator implements CommandLineRunner, DisposableBean {

    public static final String LEASE_LOST_TOPIC = "/_sys/gplink/modbus/poll/lease/lost";

    private final DeviceSessionManager sessionManager;
    private final ModbusPollingLeaseService leaseService;
    private final ModbusPollPlanResolver planResolver;
    private final DeviceRegistry registry;
    private final ModbusPollReportPublisher reportPublisher;
    private final EventBus eventBus;
    private final Map<String, GatewayPollingWorker> workers = new ConcurrentHashMap<>();
    private final Map<String, Mono<Void>> stoppingWorkers = new ConcurrentHashMap<>();
    private final Set<GatewayPollingWorker> retiringWorkers = ConcurrentHashMap.newKeySet();
    private final ModbusPollingReloadGate reloadGate;

    private final Disposable.Swap sessionListener = Disposables.swap();
    private final Disposable.Swap initialEnumeration = Disposables.swap();
    private volatile boolean shuttingDown;

    public ModbusPollingCoordinator(DeviceSessionManager sessionManager,
                                    ModbusPollingLeaseService leaseService,
                                    ModbusPollPlanResolver planResolver,
                                    DeviceRegistry registry,
                                    ModbusPollReportPublisher reportPublisher,
                                    EventBus eventBus) {
        this.sessionManager = sessionManager;
        this.leaseService = leaseService;
        this.planResolver = planResolver;
        this.registry = registry;
        this.reportPublisher = reportPublisher;
        this.eventBus = eventBus;
        this.reloadGate = new ModbusPollingReloadGate(this::reloadWorkers, Duration.ofMillis(200));
    }

    @Override
    public void run(String... args) {
        if (shuttingDown) {
            return;
        }
        initialEnumeration.update(sessionManager
                .getSessions()
                .flatMap(session -> startWorker(session.getDeviceId()))
                .subscribe(null, error -> log.warn("Failed to enumerate Modbus sessions", error)));
        sessionListener.update(sessionManager.listenEvent(event -> {
            if (event.getType() == DeviceSessionEvent.Type.register) {
                return startWorker(event.getSession().getDeviceId());
            }
            return stopWorker(event.getSession().getDeviceId());
        }));
    }

    @EventListener
    public void onDeviceSaved(EntitySavedEvent<DeviceInstanceEntity> event) {
        event.async(reloadGate.request());
    }

    @EventListener
    public void onDeviceModified(EntityModifyEvent<DeviceInstanceEntity> event) {
        event.async(reloadGate.request());
    }

    @EventListener
    public void onDeviceDeleted(EntityDeletedEvent<DeviceInstanceEntity> event) {
        event.async(reloadGate.request());
    }

    @EventListener
    public void onProductSaved(EntitySavedEvent<DeviceProductEntity> event) {
        event.async(reloadGate.request());
    }

    @EventListener
    public void onProductModified(EntityModifyEvent<DeviceProductEntity> event) {
        event.async(reloadGate.request());
    }

    private synchronized Mono<Void> startWorker(String gatewayId) {
        if (shuttingDown || gatewayId == null || workers.containsKey(gatewayId)) {
            return Mono.empty();
        }
        GatewayPollingWorker worker = new GatewayPollingWorker(
                gatewayId,
                sessionManager.getCurrentServerId(),
                leaseService,
                planResolver,
                registry,
                reportPublisher,
                eventBus);
        // Track startup before asynchronous lookup so an offline event can stop it too.
        workers.put(gatewayId, worker);
        return stoppingWorkers.getOrDefault(gatewayId, Mono.empty())
                .then(Mono.defer(() -> planResolver
                .isModbusGateway(gatewayId)
                .takeUntilOther(worker.whenStopped())
                .defaultIfEmpty(false)
                .flatMap(modbus -> {
                    if (!modbus) {
                        workers.remove(gatewayId, worker);
                        worker.shutdownLocally();
                        return Mono.empty();
                    }
                    return worker
                            .start()
                            .flatMap(started -> started ? Mono.<Void>empty() : stopWorker(gatewayId, worker));
                })))
                .doOnCancel(() -> stopWorker(gatewayId, worker).subscribe(null,
                        error -> log.warn("Failed to stop cancelled Modbus worker [{}]", gatewayId, error)))
                .onErrorResume(error -> {
                    log.warn("Failed to start Modbus poll worker [{}]", gatewayId, error);
                    return stopWorker(gatewayId, worker).onErrorResume(releaseError -> Mono.empty());
                });
    }

    private synchronized Mono<Void> stopWorker(String gatewayId) {
        return stopWorker(gatewayId, workers.get(gatewayId));
    }

    private synchronized Mono<Void> stopWorker(String gatewayId, GatewayPollingWorker worker) {
        if (worker == null || !workers.remove(gatewayId, worker)) {
            return stoppingWorkers.getOrDefault(gatewayId, Mono.empty());
        }
        if (shuttingDown) {
            worker.shutdownLocally();
            return Mono.empty();
        }
        retiringWorkers.add(worker);
        Mono<Void> previous = stoppingWorkers.getOrDefault(gatewayId, Mono.empty());
        AtomicReference<Mono<Void>> reference = new AtomicReference<>();
        Mono<Void> stop = Mono.whenDelayError(previous, worker.stop())
                .doFinally(ignore -> {
                    stoppingWorkers.remove(gatewayId, reference.get());
                    retiringWorkers.remove(worker);
                })
                .cache();
        reference.set(stop);
        // A fast reconnect must acquire its lease after the old owner releases it.
        stoppingWorkers.put(gatewayId, stop);
        return stop;
    }

    private Mono<Void> reloadWorkers() {
        if (shuttingDown) {
            return Mono.empty();
        }
        return Flux
                .fromIterable(workers.values())
                .flatMap(GatewayPollingWorker::reloadPlans)
                .then();
    }

    public Map<String, Object> getReloadStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("requestedRevision", reloadGate.getRequestedRevision());
        status.put("appliedRevision", reloadGate.getAppliedRevision());
        status.put("reloading", reloadGate.isRunning());
        status.put("activeGatewayCount", workers.size());
        return status;
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        shutdownLocally();
    }

    @Override
    public void destroy() {
        shutdownLocally();
    }

    private synchronized void shutdownLocally() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        // Mark every generation before cancelling callers: cancellation cleanup
        // must not access Redis or the event bus while those services shut down.
        workers.values().forEach(GatewayPollingWorker::shutdownLocally);
        retiringWorkers.forEach(GatewayPollingWorker::shutdownLocally);
        sessionListener.dispose();
        initialEnumeration.dispose();
        workers.clear();
        stoppingWorkers.clear();
        retiringWorkers.clear();
    }
}
