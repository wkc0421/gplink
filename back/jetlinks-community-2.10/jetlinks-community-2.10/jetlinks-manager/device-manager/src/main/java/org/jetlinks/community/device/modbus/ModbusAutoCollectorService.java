package org.jetlinks.community.device.modbus;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.enums.DeviceType;
import org.jetlinks.community.device.message.DeviceMessageConnector;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.core.message.Headers;
import org.jetlinks.core.message.property.ReportPropertyMessage;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ModbusAutoCollectorService implements CommandLineRunner, DisposableBean {

    private static final String IGNORE_CACHE = "ignoreCache";

    private final LocalDeviceProductService productService;
    private final LocalDeviceInstanceService deviceService;
    private final DeviceMessageConnector messageConnector;
    private final Map<String, Disposable> jobs = new ConcurrentHashMap<>();
    private final Map<String, Long> lastStoredAt = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> droppedCycles = new ConcurrentHashMap<>();
    private final AtomicLong reloadVersion = new AtomicLong();
    private final Object jobMonitor = new Object();

    public ModbusAutoCollectorService(LocalDeviceProductService productService,
                                      LocalDeviceInstanceService deviceService,
                                      DeviceMessageConnector messageConnector) {
        this.productService = productService;
        this.deviceService = deviceService;
        this.messageConnector = messageConnector;
    }

    @Override
    public void run(String... args) {
        reload().subscribe(null, error -> log.warn("Reload Modbus auto collector failed", error));
    }

    public Mono<Void> reload() {
        long version = reloadVersion.incrementAndGet();
        return loadPlans()
                .collectList()
                .doOnNext(plans -> {
                    if (version == reloadVersion.get()) {
                        replaceJobs(plans);
                    } else {
                        log.debug("Skip stale Modbus auto collector reload: version={}", version);
                    }
                })
                .then();
    }

    private Flux<GatewayPlan> loadPlans() {
        return productService
                .createQuery()
                .where(DeviceProductEntity::getMessageProtocol, ModbusAccessProperties.PROTOCOL_ID)
                .and(DeviceProductEntity::getDeviceType, DeviceType.childrenDevice)
                .fetch()
                .flatMap(product -> {
                    Map<String, Object> productConfig = product.getConfiguration();
                    if (productConfig == null || !productConfig.containsKey(ModbusAccessProperties.CONFIG_ACCESS_ID)) {
                        return Flux.empty();
                    }
                    List<Map<String, Object>> registerMap = ModbusAccessProperties.normalizeRegisterMap(
                            productConfig.get(ModbusAccessProperties.CONFIG_REGISTER_MAP));
                    List<String> propertyIds = ModbusAccessProperties.readablePropertyIds(registerMap);
                    if (propertyIds.isEmpty()) {
                        return Flux.empty();
                    }
                    ModbusCollectionPolicy productPolicy = ModbusCollectionPolicy.from(productConfig, ModbusCollectionPolicy.defaults());
                    return deviceService
                            .createQuery()
                            .where(DeviceInstanceEntity::getProductId, product.getId())
                            .fetch()
                            .map(device -> toSlavePlan(product, device, productPolicy, propertyIds))
                            .filter(Objects::nonNull);
                })
                .collect(Collectors.groupingBy(plan -> plan.gatewayDeviceId, LinkedHashMap::new, Collectors.toList()))
                .flatMapMany(grouped -> Flux
                        .fromIterable(grouped.entrySet())
                        .map(entry -> new GatewayPlan(entry.getKey(), entry.getValue())));
    }

    private SlavePlan toSlavePlan(DeviceProductEntity product,
                                  DeviceInstanceEntity device,
                                  ModbusCollectionPolicy productPolicy,
                                  List<String> propertyIds) {
        if (!StringUtils.hasText(device.getParentId())) {
            return null;
        }
        Map<String, Object> config = device.getConfiguration();
        Integer slaveId = config == null ? null : ModbusAccessProperties.asInt(config.get(ModbusAccessProperties.CONFIG_SLAVE_ID), -1);
        if (slaveId == null || slaveId < 1 || slaveId > 247) {
            log.warn("Skip Modbus slave without valid slaveId: {}", device.getId());
            return null;
        }
        ModbusCollectionPolicy policy = ModbusCollectionPolicy.from(config, productPolicy);
        if (!Boolean.TRUE.equals(policy.getCollectEnabled())) {
            return null;
        }
        return new SlavePlan(
                device.getParentId(),
                device.getId(),
                product.getId(),
                new ArrayList<>(propertyIds),
                policy);
    }

    private void replaceJobs(List<GatewayPlan> plans) {
        synchronized (jobMonitor) {
            jobs.values().forEach(Disposable::dispose);
            jobs.clear();
            pruneRuntimeState(plans);
            for (GatewayPlan plan : plans) {
                long interval = plan
                        .slaves
                        .stream()
                        .map(SlavePlan::scanIntervalMs)
                        .min(Long::compareTo)
                        .orElse(5000L);
                Disposable disposable = Flux
                        .interval(Duration.ZERO, Duration.ofMillis(interval))
                        .flatMap(ignore -> collectGateway(plan))
                        .subscribe(
                                ignore -> {
                                },
                                error -> log.warn("Modbus auto collector job failed: {}", plan.gatewayDeviceId, error));
                jobs.put(plan.gatewayDeviceId, disposable);
            }
        }
        log.info("Reloaded Modbus auto collector plans: gateways={}, slaves={}",
                plans.size(),
                plans.stream().mapToInt(plan -> plan.slaves.size()).sum());
    }

    private void pruneRuntimeState(List<GatewayPlan> plans) {
        Set<String> activeGateways = plans
                .stream()
                .map(plan -> plan.gatewayDeviceId)
                .collect(Collectors.toSet());
        Set<String> activeStorageKeys = plans
                .stream()
                .flatMap(plan -> plan.slaves.stream())
                .flatMap(slave -> slave.propertyIds
                        .stream()
                        .map(property -> storageKey(slave.deviceId, property)))
                .collect(Collectors.toSet());
        droppedCycles.keySet().retainAll(activeGateways);
        lastStoredAt.keySet().retainAll(activeStorageKeys);
    }

    private Mono<Void> collectGateway(GatewayPlan plan) {
        if (!plan.running.compareAndSet(false, true)) {
            droppedCycles
                    .computeIfAbsent(plan.gatewayDeviceId, ignore -> new AtomicLong())
                    .incrementAndGet();
            return Mono.empty();
        }
        long now = System.currentTimeMillis();
        return Flux
                .fromIterable(plan.slaves)
                .filter(slave -> slave.due(now))
                .concatMap(slave -> collectSlave(slave, now)
                        .delaySubscription(Duration.ofMillis(slave.policy.getDispatchIntervalMs())), 1)
                .then()
                .doFinally(ignore -> plan.running.set(false));
    }

    private Mono<Void> collectSlave(SlavePlan slave, long now) {
        slave.nextScanAt.set(now + slave.policy.getScanIntervalMs());
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(Headers.ignoreStorage.getKey(), true);
        headers.put(Headers.ignoreLog.getKey(), true);
        headers.put(IGNORE_CACHE, true);

        return deviceService
                .readProperties(slave.deviceId, slave.propertyIds, headers)
                .timeout(Duration.ofMillis(slave.readTimeoutMs()))
                .flatMap(values -> publishCollected(slave, values, System.currentTimeMillis()))
                .onErrorResume(error -> {
                    log.debug("Modbus auto collect failed: device={}, {}", slave.deviceId, error.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> publishCollected(SlavePlan slave,
                                        Map<String, Object> values,
                                        long timestamp) {
        if (values == null || values.isEmpty()) {
            return Mono.empty();
        }
        Map<String, Object> storageValues = new LinkedHashMap<>();
        Map<String, Object> realtimeOnlyValues = new LinkedHashMap<>();
        values.forEach((property, value) -> {
            if (shouldStore(slave, property, timestamp)) {
                storageValues.put(property, value);
            } else {
                realtimeOnlyValues.put(property, value);
            }
        });

        Mono<Void> store = storageValues.isEmpty()
                ? Mono.empty()
                : publishReport(slave.deviceId, storageValues, timestamp, false);
        Mono<Void> realtime = realtimeOnlyValues.isEmpty()
                ? Mono.empty()
                : publishReport(slave.deviceId, realtimeOnlyValues, timestamp, true);
        return store.then(realtime);
    }

    private boolean shouldStore(SlavePlan slave, String property, long timestamp) {
        String key = storageKey(slave.deviceId, property);
        Long last = lastStoredAt.get(key);
        if (last == null || timestamp - last >= slave.policy.getStorageIntervalMs()) {
            lastStoredAt.put(key, timestamp);
            return true;
        }
        return false;
    }

    private String storageKey(String deviceId, String property) {
        return deviceId + ":" + property;
    }

    private Mono<Void> publishReport(String deviceId,
                                     Map<String, Object> values,
                                     long timestamp,
                                     boolean ignoreStorageAndLog) {
        ReportPropertyMessage message = new ReportPropertyMessage();
        message.setDeviceId(deviceId);
        message.setMessageId(IDGenerator.SNOW_FLAKE_STRING.generate());
        message.setTimestamp(timestamp);
        message.setProperties(values);
        if (ignoreStorageAndLog) {
            message.addHeader(Headers.ignoreStorage, true);
            message.addHeader(Headers.ignoreLog, true);
        }
        return messageConnector.onMessage(message);
    }

    public long getDroppedCycles(String gatewayDeviceId) {
        AtomicLong counter = droppedCycles.get(gatewayDeviceId);
        return counter == null ? 0L : counter.get();
    }

    @Override
    public void destroy() {
        synchronized (jobMonitor) {
            jobs.values().forEach(Disposable::dispose);
            jobs.clear();
            lastStoredAt.clear();
            droppedCycles.clear();
        }
    }

    private static final class GatewayPlan {
        private final String gatewayDeviceId;
        private final List<SlavePlan> slaves;
        private final AtomicBoolean running = new AtomicBoolean();

        private GatewayPlan(String gatewayDeviceId, List<SlavePlan> slaves) {
            this.gatewayDeviceId = gatewayDeviceId;
            this.slaves = slaves == null ? Collections.emptyList() : slaves;
        }
    }

    private static final class SlavePlan {
        private final String gatewayDeviceId;
        private final String deviceId;
        private final String productId;
        private final List<String> propertyIds;
        private final ModbusCollectionPolicy policy;
        private final AtomicLong nextScanAt = new AtomicLong();

        private SlavePlan(String gatewayDeviceId,
                          String deviceId,
                          String productId,
                          List<String> propertyIds,
                          ModbusCollectionPolicy policy) {
            this.gatewayDeviceId = gatewayDeviceId;
            this.deviceId = deviceId;
            this.productId = productId;
            this.propertyIds = propertyIds;
            this.policy = policy;
        }

        private boolean due(long now) {
            long next = nextScanAt.get();
            return next <= now;
        }

        private long scanIntervalMs() {
            return policy.getScanIntervalMs();
        }

        private long readTimeoutMs() {
            long frameCount = Math.max(propertyIds == null ? 1L : propertyIds.size(), 1L);
            long perFrameTimeout = Math.max(policy.getResponseTimeoutMs(), 1L)
                    + Math.max(policy.getDispatchIntervalMs(), 0L);
            return Math.max(frameCount * perFrameTimeout + 1000L, 1L);
        }
    }
}
