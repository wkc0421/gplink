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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final ModbusPollingReloadGate reloadGate;

    private volatile Disposable sessionListener;
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
        sessionManager
                .getSessions()
                .flatMap(session -> startWorker(session.getDeviceId()))
                .subscribe();
        sessionListener = sessionManager.listenEvent(event -> {
            if (event.getType() == DeviceSessionEvent.Type.register) {
                return startWorker(event.getSession().getDeviceId());
            }
            return stopWorker(event.getSession().getDeviceId());
        });
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

    private Mono<Void> startWorker(String gatewayId) {
        if (shuttingDown || gatewayId == null || workers.containsKey(gatewayId)) {
            return Mono.empty();
        }
        return planResolver
                .isModbusGateway(gatewayId)
                .filter(Boolean.TRUE::equals)
                .flatMap(ignore -> {
                    GatewayPollingWorker worker = new GatewayPollingWorker(
                            gatewayId,
                            sessionManager.getCurrentServerId(),
                            leaseService,
                            planResolver,
                            registry,
                            reportPublisher,
                            eventBus);
                    GatewayPollingWorker existing = workers.putIfAbsent(gatewayId, worker);
                    if (existing != null) {
                        return Mono.empty();
                    }
                    return worker
                            .start()
                            .doOnNext(started -> {
                                if (!started) {
                                    workers.remove(gatewayId, worker);
                                }
                            })
                            .then();
                })
                .onErrorResume(error -> {
                    GatewayPollingWorker worker = workers.get(gatewayId);
                    if (worker != null) {
                        workers.remove(gatewayId, worker);
                    }
                    log.warn("Failed to start Modbus poll worker [{}]", gatewayId, error);
                    return Mono.empty();
                });
    }

    private Mono<Void> stopWorker(String gatewayId) {
        GatewayPollingWorker worker = workers.remove(gatewayId);
        return worker == null ? Mono.empty() : worker.stop();
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
        if (sessionListener != null) {
            sessionListener.dispose();
            sessionListener = null;
        }
        workers.values().forEach(GatewayPollingWorker::shutdownLocally);
        workers.clear();
    }
}
