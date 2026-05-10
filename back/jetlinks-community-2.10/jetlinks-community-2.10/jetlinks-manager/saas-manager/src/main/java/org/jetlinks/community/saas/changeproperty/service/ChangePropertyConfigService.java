package org.jetlinks.community.saas.changeproperty.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.hswebframework.web.exception.ValidationException;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.community.saas.changeproperty.entity.ChangePropertyConfigEntity;
import org.jetlinks.core.event.EventBus;
import org.jetlinks.core.event.Subscription;
import org.jetlinks.core.message.DeviceMessage;
import org.jetlinks.core.message.property.PropertyMessage;
import org.jetlinks.core.message.property.ReadPropertyMessageReply;
import org.jetlinks.core.message.property.ReportPropertyMessage;
import org.jetlinks.core.metadata.DeviceMetadata;
import org.jetlinks.core.metadata.PropertyMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChangePropertyConfigService
    extends GenericReactiveCrudService<ChangePropertyConfigEntity, String>
    implements CommandLineRunner {

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> MAP_TYPE =
        new com.fasterxml.jackson.core.type.TypeReference<>() {};
    private static final String CONFIG_INDEX_KEY = "gplink:device:change:config:index";
    private static final String LATEST_KEY = "gplink:device:change:latest";
    private static final String LAST_KEY = "gplink:device:change:last";
    private static final String MONITOR_LOCK_KEY = "gplink:device:change:monitor:lock";
    private static final String PAIR_SEPARATOR = "|";

    private final LocalDeviceInstanceService deviceService;
    private final LocalDeviceProductService productService;
    private final DeviceDataService deviceDataService;
    private final EventBus eventBus;
    private final ChangePropertyMqttPublisher mqttPublisher;
    private final ReactiveRedisOperations<String, String> redis;
    private final ObjectMapper objectMapper;

    private final Object runtimeMonitor = new Object();
    private final ConcurrentHashMap<String, ProductRuntimeSubscription> productSubscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RuntimeConfig> configIndex = new ConcurrentHashMap<>();
    private final AtomicBoolean monitorRunning = new AtomicBoolean();

    private final AtomicLong droppedMessages = new AtomicLong();
    private final AtomicLong lastDropTime = new AtomicLong();
    private final AtomicLong publishFailures = new AtomicLong();
    private final AtomicLong oversizedValues = new AtomicLong();
    private final AtomicLong processingErrors = new AtomicLong();
    private final AtomicLong saveFailures = new AtomicLong();

    @Value("${gplink.change-property.mqtt-network-id:}")
    private String defaultMqttNetworkId;

    @Value("${gplink.change-property.topic-prefix:IOT/Business}")
    private String defaultMqttTopicPrefix;

    @Value("${gplink.change-property.default-qos:0}")
    private int defaultMqttQos;

    @Value("${gplink.change-property.per-product-buffer-size:${gplink.change-property.per-device-buffer-size:1024}}")
    private int perProductBufferSize;

    @Value("${gplink.change-property.per-product-concurrency:${gplink.change-property.per-device-concurrency:8}}")
    private int perProductConcurrency;

    @Value("${gplink.change-property.max-value-bytes:65536}")
    private int maxValueBytes;

    @Value("${gplink.change-property.monitor-timeout-ms:300000}")
    private long monitorTimeoutMs;

    @Value("${gplink.change-property.monitor-lock-ttl-ms:360000}")
    private long monitorLockTtlMs;

    @Value("${gplink.change-property.monitor-scan-count:512}")
    private int monitorScanCount;

    @Value("${gplink.change-property.monitor-device-concurrency:4}")
    private int monitorDeviceConcurrency;

    @Override
    public void run(String... args) {
        reloadSubscriptions()
            .doOnSuccess(stats -> log.info("change-property runtime initialized: {}", stats))
            .doOnError(err -> log.warn("change-property runtime initialization failed", err))
            .subscribe();
    }

    @Scheduled(fixedDelayString = "${gplink.change-property.monitor-interval-ms:5000}")
    public void monitorScheduled() {
        if (!monitorRunning.compareAndSet(false, true)) {
            return;
        }
        String lockOwner = UUID.randomUUID().toString();
        acquireMonitorLock(lockOwner)
            .flatMap(acquired -> {
                if (!acquired) {
                    return Mono.just(Collections.<String, Object>singletonMap("skipped", true));
                }
                return monitorOnce()
                    .timeout(Duration.ofMillis(Math.max(1000, monitorTimeoutMs)))
                    .flatMap(result -> releaseMonitorLock(lockOwner).thenReturn(result))
                    .onErrorResume(error -> releaseMonitorLock(lockOwner).then(Mono.error(error)));
            })
            .doOnSuccess(result -> log.debug("change-property monitor finished: {}", result))
            .doOnError(err -> log.warn("change-property monitor failed", err))
            .doFinally(signal -> monitorRunning.set(false))
            .subscribe();
    }

    @PreDestroy
    public void shutdown() {
        synchronized (runtimeMonitor) {
            productSubscriptions.values().forEach(ProductRuntimeSubscription::dispose);
            productSubscriptions.clear();
            configIndex.clear();
        }
    }

    public Mono<String> upsert(ChangePropertyConfigEntity request) {
        return prepareForSave(request)
            .flatMap(prepared -> findByUnique(prepared.getDeviceId(), prepared.getPropertyId())
                .flatMap(existing -> {
                    mergeEditable(existing, prepared);
                    return updateById(existing.getId(), existing)
                        .then(findById(existing.getId()))
                        .flatMap(entity -> refreshRuntime(entity).thenReturn(entity.getId()));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    if (!StringUtils.hasText(prepared.getId())) {
                        prepared.setId(IDGenerator.SNOW_FLAKE_STRING.generate());
                    }
                    return insert(prepared)
                        .then(findById(prepared.getId()))
                        .flatMap(entity -> refreshRuntime(entity).thenReturn(entity.getId()));
                })));
    }

    public Mono<Boolean> updateConfig(String id, ChangePropertyConfigEntity request) {
        if (!StringUtils.hasText(id)) {
            return Mono.error(new ValidationException("id is required"));
        }
        return findById(id)
            .switchIfEmpty(Mono.error(new ValidationException("change property config not found")))
            .flatMap(existing -> prepareForSave(request)
                .flatMap(prepared -> assertUniqueForUpdate(id, prepared)
                    .then(Mono.fromSupplier(() -> {
                        prepared.setId(id);
                        copyAudit(existing, prepared);
                        return prepared;
                    })))
                .flatMap(prepared -> updateById(id, prepared)
                    .then(findById(id))
                    .flatMap(entity -> replaceRuntime(existing, entity).thenReturn(true))));
    }

    public Mono<Boolean> deleteConfig(String id) {
        return findById(id)
            .flatMap(entity -> deleteById(id)
                .then(removeRuntime(entity))
                .thenReturn(true))
            .defaultIfEmpty(false);
    }

    public Mono<List<String>> batchUpsert(List<ChangePropertyConfigEntity> configs) {
        if (configs == null || configs.isEmpty()) {
            return Mono.error(new ValidationException("configs is required"));
        }
        return Flux
            .fromIterable(configs)
            .concatMap(this::upsert)
            .collectList();
    }

    public Mono<Map<String, Object>> createByProduct(ChangePropertyConfigEntity request) {
        if (request == null || !StringUtils.hasText(request.getProductId())) {
            return Mono.error(new ValidationException("productId is required"));
        }
        if (!StringUtils.hasText(request.getPropertyId())) {
            return Mono.error(new ValidationException("propertyId is required"));
        }

        String productId = request.getProductId().trim();
        return productService
            .findById(productId)
            .switchIfEmpty(Mono.error(new ValidationException("product not found")))
            .then(productService.getMetadata(productId))
            .flatMap(metadata -> metadata.getProperty(request.getPropertyId().trim())
                .map(property -> Mono.just(property))
                .orElseGet(() -> Mono.error(new ValidationException("property not found in product metadata"))))
            .thenMany(deviceService
                .createQuery()
                .where(DeviceInstanceEntity::getProductId, productId)
                .fetch())
            .map(device -> copyForDevice(request, device))
            .concatMap(this::upsert)
            .collectList()
            .map(ids -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("count", ids.size());
                result.put("ids", ids);
                return result;
            });
    }

    public Mono<Boolean> batchDelete(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Mono.error(new ValidationException("ids is required"));
        }
        return Flux
            .fromIterable(ids)
            .concatMap(this::deleteConfig)
            .reduce(true, (left, right) -> left && right);
    }

    public Mono<Map<String, Object>> deleteByCondition(ChangePropertyConfigEntity condition) {
        if (condition == null || !hasDeleteCondition(condition)) {
            return Mono.error(new ValidationException("at least one delete condition is required"));
        }

        var query = createQuery().where();
        if (StringUtils.hasText(condition.getProductId())) {
            query.and(ChangePropertyConfigEntity::getProductId, condition.getProductId().trim());
        }
        if (StringUtils.hasText(condition.getDeviceId())) {
            query.and(ChangePropertyConfigEntity::getDeviceId, condition.getDeviceId().trim());
        }
        if (StringUtils.hasText(condition.getPropertyId())) {
            query.and(ChangePropertyConfigEntity::getPropertyId, condition.getPropertyId().trim());
        }
        if (condition.getEnabled() != null) {
            query.and(ChangePropertyConfigEntity::getEnabled, condition.getEnabled());
        }
        if (StringUtils.hasText(condition.getMqttNetworkId())) {
            query.and(ChangePropertyConfigEntity::getMqttNetworkId, condition.getMqttNetworkId().trim());
        }

        return query
            .fetch()
            .collectList()
            .flatMap(entities -> {
                List<String> ids = entities
                    .stream()
                    .map(ChangePropertyConfigEntity::getId)
                    .collect(Collectors.toList());
                if (ids.isEmpty()) {
                    return Mono.just(0);
                }
                return deleteById(Flux.fromIterable(ids))
                    .flatMap(count -> removeRuntime(entities).thenReturn(count));
            })
            .map(count -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("count", count);
                return result;
            });
    }

    public Mono<Map<String, Object>> reloadSubscriptions() {
        return createQuery()
            .where(ChangePropertyConfigEntity::getEnabled, true)
            .fetch()
            .collectList()
            .flatMap(enabledConfigs -> {
                Map<String, List<ChangePropertyConfigEntity>> grouped = enabledConfigs
                    .stream()
                    .collect(Collectors.groupingBy(ChangePropertyConfigEntity::getProductId));
                synchronized (runtimeMonitor) {
                    configIndex.clear();
                    Set<String> desiredProducts = grouped.keySet();
                    productSubscriptions.forEach((productId, subscription) -> {
                        if (!desiredProducts.contains(productId)) {
                            subscription.dispose();
                            productSubscriptions.remove(productId, subscription);
                        }
                    });
                    grouped.forEach((productId, configs) -> {
                        if (configs.isEmpty()) {
                            return;
                        }
                        ChangePropertyConfigEntity first = configs.get(0);
                        ProductRuntimeSubscription subscription =
                            ensureProductSubscriptionNow(first.getProductId());
                        subscription.configs.clear();
                        configs.forEach(config -> {
                            RuntimeConfig runtimeConfig = RuntimeConfig.of(config);
                            subscription.configs.put(runtimeKey(config.getDeviceId(), config.getPropertyId()), runtimeConfig);
                            configIndex.put(runtimeKey(config.getDeviceId(), config.getPropertyId()), runtimeConfig);
                        });
                    });
                }
                return rebuildConfigIndex(enabledConfigs).thenReturn(getRuntimeStats());
            });
    }

    public Map<String, Object> getRuntimeStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productSubscriptionCount", productSubscriptions.size());
        result.put("propertyConfigCount", configIndex.size());
        result.put("droppedMessages", droppedMessages.get());
        result.put("lastDropTime", lastDropTime.get());
        result.put("publishFailures", publishFailures.get());
        result.put("oversizedValues", oversizedValues.get());
        result.put("processingErrors", processingErrors.get());
        result.put("saveFailures", saveFailures.get());
        Map<String, Object> products = new LinkedHashMap<>();
        productSubscriptions.forEach((productId, subscription) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", subscription.productId);
            item.put("active", subscription.isActive());
            item.put("propertyCount", subscription.configs.size());
            item.put("droppedMessages", subscription.droppedMessages.get());
            item.put("lastDropTime", subscription.lastDropTime.get());
            products.put(productId, item);
        });
        result.put("products", products);
        return result;
    }

    public Mono<ChangePropertyConfigEntity> findByUnique(String deviceId, String propertyId) {
        if (!StringUtils.hasText(deviceId) || !StringUtils.hasText(propertyId)) {
            return Mono.empty();
        }
        return createQuery()
            .where(ChangePropertyConfigEntity::getDeviceId, deviceId.trim())
            .and(ChangePropertyConfigEntity::getPropertyId, propertyId.trim())
            .fetch()
            .next();
    }

    public Mono<Void> refreshRuntime(ChangePropertyConfigEntity entity) {
        return Mono
            .fromRunnable(() -> {
                synchronized (runtimeMonitor) {
                    if (Boolean.TRUE.equals(entity.getEnabled())) {
                        addRuntimeNow(entity);
                    } else {
                        removeRuntimeNow(entity);
                    }
                }
            })
            .then(Boolean.TRUE.equals(entity.getEnabled())
                ? upsertConfigIndex(entity).then()
                : removeMonitorFields(entity).then());
    }

    public Mono<Void> removeRuntime(ChangePropertyConfigEntity entity) {
        return Mono
            .fromRunnable(() -> {
                synchronized (runtimeMonitor) {
                    removeRuntimeNow(entity);
                }
            })
            .then(removeMonitorFields(entity).then());
    }

    private Mono<Void> removeRuntime(List<ChangePropertyConfigEntity> entities) {
        return Mono
            .fromRunnable(() -> {
                synchronized (runtimeMonitor) {
                    entities.forEach(this::removeRuntimeNow);
                }
            })
            .then(removeMonitorFields(entities).then());
    }

    private Mono<Void> replaceRuntime(ChangePropertyConfigEntity previous,
                                      ChangePropertyConfigEntity current) {
        boolean samePair = Objects.equals(pairKey(previous.getDeviceId(), previous.getPropertyId()),
            pairKey(current.getDeviceId(), current.getPropertyId()));
        Mono<?> redisUpdate = samePair
            ? Mono.empty()
            : removeMonitorFields(previous);
        if (Boolean.TRUE.equals(current.getEnabled())) {
            redisUpdate = redisUpdate.then(upsertConfigIndex(current));
        } else if (samePair) {
            redisUpdate = redisUpdate.then(removeMonitorFields(current));
        }
        return Mono
            .fromRunnable(() -> {
                synchronized (runtimeMonitor) {
                    removeRuntimeNow(previous);
                    if (Boolean.TRUE.equals(current.getEnabled())) {
                        addRuntimeNow(current);
                    } else {
                        removeRuntimeNow(current);
                    }
                }
            })
            .then(redisUpdate.then());
    }

    private Mono<ChangePropertyConfigEntity> prepareForSave(ChangePropertyConfigEntity request) {
        if (request == null) {
            return Mono.error(new ValidationException("request body is required"));
        }
        if (!StringUtils.hasText(request.getDeviceId())) {
            return Mono.error(new ValidationException("deviceId is required"));
        }
        if (!StringUtils.hasText(request.getPropertyId())) {
            return Mono.error(new ValidationException("propertyId is required"));
        }

        String deviceId = request.getDeviceId().trim();
        String propertyId = request.getPropertyId().trim();

        return deviceService
            .findById(deviceId)
            .switchIfEmpty(Mono.error(new ValidationException("device not found")))
            .flatMap(device -> {
                String productId = StringUtils.hasText(request.getProductId())
                    ? request.getProductId().trim()
                    : device.getProductId();
                if (!Objects.equals(productId, device.getProductId())) {
                    return Mono.error(new ValidationException("device does not belong to productId"));
                }
                return productService
                    .findById(productId)
                    .switchIfEmpty(Mono.error(new ValidationException("product not found")))
                    .zipWith(deviceService.getMetadata(deviceId))
                    .map(tuple -> normalize(request, device, tuple.getT1(), tuple.getT2(), propertyId));
            });
    }

    private ChangePropertyConfigEntity normalize(ChangePropertyConfigEntity request,
                                                 DeviceInstanceEntity device,
                                                 DeviceProductEntity product,
                                                 DeviceMetadata metadata,
                                                 String propertyId) {
        PropertyMetadata property = metadata
            .getProperty(propertyId)
            .orElseThrow(() -> new ValidationException("property not found in metadata"));

        ChangePropertyConfigEntity entity = new ChangePropertyConfigEntity();
        entity.setId(request.getId());
        entity.setProductId(device.getProductId());
        entity.setProductName(StringUtils.hasText(product.getName()) ? product.getName() : device.getProductName());
        entity.setDeviceId(device.getId());
        entity.setDeviceName(device.getName());
        entity.setPropertyId(propertyId);
        entity.setPropertyName(property.getName());
        entity.setEnabled(request.getEnabled() == null || Boolean.TRUE.equals(request.getEnabled()));
        entity.setMqttNetworkId(trimToNull(request.getMqttNetworkId()));
        entity.setMqttTopicPrefix(trimToNull(request.getMqttTopicPrefix()));
        entity.setMqttQos(request.getMqttQos() == null ? defaultMqttQos : request.getMqttQos());
        entity.setValueMapping(request.getValueMapping());
        entity.setRemark(request.getRemark());
        return entity;
    }

    private Mono<Void> assertUniqueForUpdate(String id, ChangePropertyConfigEntity prepared) {
        return findByUnique(prepared.getDeviceId(), prepared.getPropertyId())
            .flatMap(existing -> Objects.equals(existing.getId(), id)
                ? Mono.<Void>empty()
                : Mono.error(new ValidationException("deviceId + propertyId already exists")));
    }

    private void copyAudit(ChangePropertyConfigEntity source, ChangePropertyConfigEntity target) {
        target.setCreatorId(source.getCreatorId());
        target.setCreatorName(source.getCreatorName());
        target.setCreateTime(source.getCreateTime());
    }

    private void mergeEditable(ChangePropertyConfigEntity existing,
                               ChangePropertyConfigEntity prepared) {
        existing.setProductId(prepared.getProductId());
        existing.setProductName(prepared.getProductName());
        existing.setDeviceId(prepared.getDeviceId());
        existing.setDeviceName(prepared.getDeviceName());
        existing.setPropertyId(prepared.getPropertyId());
        existing.setPropertyName(prepared.getPropertyName());
        existing.setEnabled(prepared.getEnabled());
        existing.setMqttNetworkId(prepared.getMqttNetworkId());
        existing.setMqttTopicPrefix(prepared.getMqttTopicPrefix());
        existing.setMqttQos(prepared.getMqttQos());
        existing.setValueMapping(prepared.getValueMapping());
        existing.setRemark(prepared.getRemark());
    }

    private ChangePropertyConfigEntity copyForDevice(ChangePropertyConfigEntity request, DeviceInstanceEntity device) {
        ChangePropertyConfigEntity entity = new ChangePropertyConfigEntity();
        entity.setProductId(device.getProductId());
        entity.setProductName(device.getProductName());
        entity.setDeviceId(device.getId());
        entity.setDeviceName(device.getName());
        entity.setPropertyId(request.getPropertyId());
        entity.setPropertyName(request.getPropertyName());
        entity.setEnabled(request.getEnabled() == null || Boolean.TRUE.equals(request.getEnabled()));
        entity.setMqttNetworkId(request.getMqttNetworkId());
        entity.setMqttTopicPrefix(request.getMqttTopicPrefix());
        entity.setMqttQos(request.getMqttQos());
        entity.setValueMapping(request.getValueMapping());
        entity.setRemark(request.getRemark());
        return entity;
    }

    private boolean hasDeleteCondition(ChangePropertyConfigEntity condition) {
        return StringUtils.hasText(condition.getProductId())
            || StringUtils.hasText(condition.getDeviceId())
            || StringUtils.hasText(condition.getPropertyId())
            || StringUtils.hasText(condition.getMqttNetworkId())
            || condition.getEnabled() != null;
    }

    private void addRuntimeNow(ChangePropertyConfigEntity entity) {
        RuntimeConfig config = RuntimeConfig.of(entity);
        configIndex.put(runtimeKey(entity.getDeviceId(), entity.getPropertyId()), config);
        ProductRuntimeSubscription subscription = ensureProductSubscriptionNow(entity.getProductId());
        subscription.configs.put(runtimeKey(entity.getDeviceId(), entity.getPropertyId()), config);
    }

    private void removeRuntimeNow(ChangePropertyConfigEntity entity) {
        configIndex.remove(runtimeKey(entity.getDeviceId(), entity.getPropertyId()));
        ProductRuntimeSubscription subscription = productSubscriptions.get(entity.getProductId());
        if (subscription == null) {
            return;
        }
        subscription.configs.remove(runtimeKey(entity.getDeviceId(), entity.getPropertyId()));
        if (subscription.configs.isEmpty()) {
            subscription.dispose();
            productSubscriptions.remove(entity.getProductId(), subscription);
        }
    }

    private void removeRuntimeConfigNow(RuntimeConfig config) {
        String key = runtimeKey(config.deviceId, config.propertyId);
        configIndex.remove(key, config);
        ProductRuntimeSubscription subscription = productSubscriptions.get(config.productId);
        if (subscription == null) {
            return;
        }
        subscription.configs.remove(key, config);
        if (subscription.configs.isEmpty()) {
            subscription.dispose();
            productSubscriptions.remove(config.productId, subscription);
        }
    }

    private ProductRuntimeSubscription ensureProductSubscriptionNow(String productId) {
        ProductRuntimeSubscription current = productSubscriptions.get(productId);
        if (current != null && current.isActive()) {
            return current;
        }
        if (current != null) {
            current.dispose();
        }

        ProductRuntimeSubscription subscription = new ProductRuntimeSubscription(productId);
        String[] topics = {
            "/device/" + productId + "/*/message/property/report",
            "/device/" + productId + "/*/message/property/read/reply"
        };
        Subscription eventSubscription = Subscription.of(
            "change-property:config-product:" + productId,
            topics,
            Subscription.Feature.local,
            Subscription.Feature.broker
        );

        Disposable disposable = eventBus
            .subscribe(eventSubscription, DeviceMessage.class)
            .onBackpressureBuffer(
                Math.max(1, perProductBufferSize),
                message -> recordDrop(subscription),
                BufferOverflowStrategy.DROP_OLDEST)
            .flatMap(message -> handleProductMessage(subscription, message)
                    .onErrorResume(err -> {
                        processingErrors.incrementAndGet();
                        log.warn("change-property process message failed: product={} device={} cause={}",
                            productId, message.getDeviceId(), err.getMessage(), err);
                        return Mono.empty();
                    }),
                Math.max(1, perProductConcurrency))
            .subscribe(
                null,
                err -> {
                    log.error("change-property subscription terminated: product={}", productId, err);
                    synchronized (runtimeMonitor) {
                        ProductRuntimeSubscription existing = productSubscriptions.get(productId);
                        if (existing == subscription) {
                            productSubscriptions.remove(productId);
                            subscription.configs.values()
                                .forEach(config -> configIndex.remove(runtimeKey(config.deviceId, config.propertyId)));
                        }
                    }
                }
            );
        subscription.disposable = disposable;
        productSubscriptions.put(productId, subscription);
        return subscription;
    }

    private void recordDrop(ProductRuntimeSubscription subscription) {
        long now = System.currentTimeMillis();
        droppedMessages.incrementAndGet();
        lastDropTime.set(now);
        subscription.droppedMessages.incrementAndGet();
        subscription.lastDropTime.set(now);
        log.warn("change-property event dropped: product={} dropped={}",
            subscription.productId, subscription.droppedMessages.get());
    }

    private Mono<Void> handleProductMessage(ProductRuntimeSubscription subscription, DeviceMessage message) {
        if (!StringUtils.hasText(message.getDeviceId())) {
            return Mono.empty();
        }

        PropertyPayload payload = extractPayload(message);
        if (payload == null || CollectionUtils.isEmpty(payload.properties)) {
            return Mono.empty();
        }

        Map<String, String> latestValues = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.properties.entrySet()) {
            RuntimeConfig config = subscription.configs.get(runtimeKey(message.getDeviceId(), entry.getKey()));
            if (config == null) {
                continue;
            }
            long sourceTime = payload
                .propertyMessage
                .getPropertySourceTime(entry.getKey())
                .orElse(message.getTimestamp());
            String latest = encodeLatestValue(config, entry.getValue(), sourceTime);
            if (latest != null) {
                latestValues.put(pairKey(message.getDeviceId(), entry.getKey()), latest);
            }
        }
        if (latestValues.isEmpty()) {
            return Mono.empty();
        }
        return redis
            .<String, String>opsForHash()
            .putAll(LATEST_KEY, latestValues)
            .then();
    }

    private PropertyPayload extractPayload(DeviceMessage message) {
        if (message instanceof ReportPropertyMessage report) {
            return new PropertyPayload("REPORT_PROPERTY", report, report.getProperties());
        }
        if (message instanceof ReadPropertyMessageReply reply) {
            return new PropertyPayload("READ_PROPERTY_REPLY", reply, reply.getProperties());
        }
        return null;
    }

    public Mono<Map<String, Object>> monitorOnce() {
        MonitorResult result = new MonitorResult();
        int scanCount = Math.max(1, monitorScanCount);
        return redis
            .<String, String>opsForHash()
            .scan(LATEST_KEY, ScanOptions.scanOptions().count(scanCount).build())
            .buffer(scanCount)
            .concatMap(batch -> {
                Map<String, String> latest = batch
                    .stream()
                    .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> right,
                        LinkedHashMap::new));
                result.latestCount += latest.size();
                if (latest.isEmpty()) {
                    return Mono.empty();
                }
                List<String> pairs = new ArrayList<>(latest.keySet());
                return Mono
                    .zip(multiGet(CONFIG_INDEX_KEY, pairs), multiGet(LAST_KEY, pairs))
                    .flatMap(tuple -> handleMonitorLatestValues(
                        latest,
                        pairs,
                        tuple.getT1(),
                        tuple.getT2(),
                        result))
                    .then();
            })
            .then(Mono.fromSupplier(result::toMap));
    }

    private Mono<Void> handleMonitorLatestValues(Map<String, String> latest,
                                                 List<String> pairs,
                                                 List<String> mappingValues,
                                                 List<String> lastValues,
                                                 MonitorResult result) {
        Map<String, String> initialBaseline = new LinkedHashMap<>();
        Map<String, DeviceChange> changesByDevice = new LinkedHashMap<>();
        List<String> stalePairs = new ArrayList<>();

        for (int i = 0; i < pairs.size(); i++) {
            String pair = pairs.get(i);
            String mappingJson = valueAt(mappingValues, i);
            if (mappingJson == null) {
                stalePairs.add(pair);
                continue;
            }
            result.configCount++;

            LatestValue current = decodeLatestValue(latest.get(pair));
            if (current == null) {
                result.missingCount++;
                continue;
            }

            String deviceId = deviceIdFromPair(pair);
            String propertyId = propertyIdFromPair(pair);
            if (!StringUtils.hasText(deviceId) || !StringUtils.hasText(propertyId)) {
                stalePairs.add(pair);
                result.missingCount++;
                continue;
            }

            String comparableValue = comparableValue(current.value);
            String previousValue = valueAt(lastValues, i);
            if (previousValue == null) {
                initialBaseline.put(pair, comparableValue);
                result.initializedCount++;
                continue;
            }
            if (sameValue(current.value, previousValue)) {
                continue;
            }

            RuntimeConfig config = configIndex.get(runtimeKey(deviceId, propertyId));
            if (config == null) {
                stalePairs.add(pair);
                result.missingCount++;
                continue;
            }

            DeviceChange deviceChange = changesByDevice.computeIfAbsent(deviceId, id -> new DeviceChange(config));
            deviceChange.timestamp = Math.max(deviceChange.timestamp, current.timestamp);
            deviceChange.baselineToWrite.put(pair, comparableValue);
            deviceChange.reportProperties.put(propertyId, current.value);
            if (current.timestamp > 0) {
                deviceChange.propertySourceTimes.put(propertyId, current.timestamp);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("Key", propertyId);
            data.put("Value", mapValue(mappingJson, current.value));
            deviceChange.changed.add(data);
        }

        Mono<Void> initializeBaseline = initialBaseline.isEmpty()
            ? Mono.empty()
            : updateLastValues(initialBaseline).then();
        Mono<Void> removeStale = stalePairs.isEmpty()
            ? Mono.empty()
            : removeMonitorFieldsByPairs(stalePairs).then();

        return initializeBaseline
            .thenMany(Flux
                .fromIterable(changesByDevice.values())
                .flatMap(deviceChange -> publishDeviceChange(deviceChange, result), Math.max(1, monitorDeviceConcurrency)))
            .then(removeStale);
    }

    private Mono<Void> publishDeviceChange(DeviceChange deviceChange, MonitorResult result) {
        if (deviceChange.changed.isEmpty()) {
            return Mono.empty();
        }
        long timestamp = deviceChange.timestamp > 0 ? deviceChange.timestamp : System.currentTimeMillis();
        return saveChangeReport(deviceChange, timestamp)
            .doOnError(error -> saveFailures.incrementAndGet())
            .then(Mono.defer(() -> publishChange(deviceChange, timestamp)))
            .then(updateLastValues(deviceChange.baselineToWrite))
            .doOnSuccess(ignore -> result.changedCount += deviceChange.changed.size())
            .onErrorResume(error -> {
                result.failedDevices++;
                log.warn("change-property monitor change failed: device={} cause={}",
                    deviceChange.deviceId, error.getMessage(), error);
                return Mono.empty();
            })
            .then();
    }

    private String encodeLatestValue(RuntimeConfig config, Object value, long timestamp) {
        try {
            String valueText = valueToText(value);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("timestamp", timestamp);
            payload.put("value", valueText);
            String json = objectMapper.writeValueAsString(payload);
            int bytes = json.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > Math.max(1, maxValueBytes)) {
                oversizedValues.incrementAndGet();
                log.warn("change-property latest value skipped because serialized value is too large: config={} device={} property={} bytes={}",
                    config.id, config.deviceId, config.propertyId, bytes);
                return null;
            }
            return json;
        } catch (JsonProcessingException e) {
            processingErrors.incrementAndGet();
            log.warn("change-property latest value serialization failed: config={} device={} property={}",
                config.id, config.deviceId, config.propertyId, e);
            return null;
        }
    }

    private LatestValue decodeLatestValue(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(raw, MAP_TYPE);
            LatestValue value = new LatestValue();
            Object timestamp = map.get("timestamp");
            if (timestamp instanceof Number number) {
                value.timestamp = number.longValue();
            } else if (timestamp != null) {
                value.timestamp = Long.parseLong(String.valueOf(timestamp));
            }
            value.value = valueToText(map.get("value"));
            return value;
        } catch (Exception e) {
            processingErrors.incrementAndGet();
            log.warn("change-property latest value decode failed: {}", raw, e);
            return null;
        }
    }

    private Mono<Void> saveChangeReport(DeviceChange deviceChange, long timestamp) {
        if (deviceChange.reportProperties.isEmpty()) {
            return Mono.empty();
        }
        ReportPropertyMessage message = new ReportPropertyMessage();
        message.setMessageId(IDGenerator.SNOW_FLAKE_STRING.generate());
        message.setDeviceId(deviceChange.deviceId);
        message.setTimestamp(timestamp);
        message.setProperties(new LinkedHashMap<>(deviceChange.reportProperties));
        message.setPropertySourceTimes(new LinkedHashMap<>(deviceChange.propertySourceTimes));
        message.addHeader("productId", deviceChange.productId);
        message.addHeader("changeSource", "CHANGE_PROPERTY_MONITOR");
        return deviceDataService.saveDeviceMessage(message);
    }

    private Mono<Void> publishChange(DeviceChange deviceChange, long timestamp) {
        RuntimeConfig config = deviceChange.firstConfig;
        String mqttNetworkId = firstText(config.mqttNetworkId, defaultMqttNetworkId);
        if (!StringUtils.hasText(mqttNetworkId)) {
            publishFailures.incrementAndGet();
            log.warn("change-property mqtt publish skipped because network id is not configured: device={}",
                deviceChange.deviceId);
            return Mono.empty();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("MsgType", "MQData");
        body.put("Style", "Change");
        body.put("Sender", "GPLink");
        body.put("Time", timestamp);
        body.put("ProductId", deviceChange.productId);
        body.put("DeviceId", deviceChange.deviceId);
        body.put("DataObject", new ArrayList<>(deviceChange.changed));

        int qos = config.mqttQos == null ? defaultMqttQos : config.mqttQos;
        return mqttPublisher
            .publish(new ChangePropertyMqttPayload(mqttNetworkId, buildMqttTopic(config), qos, body))
            .onErrorResume(err -> {
                publishFailures.incrementAndGet();
                log.warn("change-property mqtt publish failed: device={} cause={}",
                    deviceChange.deviceId, err.getMessage(), err);
                return Mono.empty();
            });
    }

    private Mono<Boolean> rebuildConfigIndex(List<ChangePropertyConfigEntity> configs) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (ChangePropertyConfigEntity config : configs) {
            String field = pairKey(config.getDeviceId(), config.getPropertyId());
            mapping.put(field, configIndexValue(config.getValueMapping()));
        }
        Mono<Boolean> upsert = mapping.isEmpty()
            ? Mono.just(true)
            : redis.<String, String>opsForHash().putAll(CONFIG_INDEX_KEY, mapping);
        return upsert
            .thenMany(redis.<String, String>opsForHash().keys(CONFIG_INDEX_KEY))
            .filter(field -> !mapping.containsKey(field))
            .collectList()
            .flatMap(stalePairs -> stalePairs.isEmpty()
                ? Mono.just(true)
                : removeMonitorFieldsByPairs(stalePairs).thenReturn(true));
    }

    private Mono<Boolean> acquireMonitorLock(String owner) {
        long timeout = Math.max(1000, monitorTimeoutMs);
        long ttl = Math.max(timeout + 10000, monitorLockTtlMs);
        return redis
            .opsForValue()
            .setIfAbsent(MONITOR_LOCK_KEY, owner, Duration.ofMillis(ttl))
            .defaultIfEmpty(false);
    }

    private Mono<Void> releaseMonitorLock(String owner) {
        return redis
            .opsForValue()
            .get(MONITOR_LOCK_KEY)
            .flatMap(current -> owner.equals(current)
                ? redis.delete(MONITOR_LOCK_KEY).then()
                : Mono.empty())
            .then();
    }

    private Mono<Boolean> upsertConfigIndex(ChangePropertyConfigEntity entity) {
        if (entity == null
            || !StringUtils.hasText(entity.getDeviceId())
            || !StringUtils.hasText(entity.getPropertyId())) {
            return Mono.just(false);
        }
        return redis
            .<String, String>opsForHash()
            .put(CONFIG_INDEX_KEY,
                pairKey(entity.getDeviceId(), entity.getPropertyId()),
                configIndexValue(entity.getValueMapping()));
    }

    private Mono<Long> removeMonitorFields(ChangePropertyConfigEntity entity) {
        if (entity == null) {
            return Mono.just(0L);
        }
        return removeMonitorFieldsByPairs(List.of(pairKey(entity.getDeviceId(), entity.getPropertyId())));
    }

    private Mono<Long> removeMonitorFields(List<ChangePropertyConfigEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return Mono.just(0L);
        }
        List<String> pairs = entities
            .stream()
            .filter(Objects::nonNull)
            .filter(entity -> StringUtils.hasText(entity.getDeviceId()) && StringUtils.hasText(entity.getPropertyId()))
            .map(entity -> pairKey(entity.getDeviceId(), entity.getPropertyId()))
            .distinct()
            .collect(Collectors.toList());
        return removeMonitorFieldsByPairs(pairs);
    }

    private Mono<Long> removeMonitorFieldsByPairs(List<String> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            return Mono.just(0L);
        }
        Object[] fields = pairs.toArray();
        return redis.<String, String>opsForHash()
            .remove(CONFIG_INDEX_KEY, fields)
            .then(redis.<String, String>opsForHash().remove(LATEST_KEY, fields))
            .then(redis.<String, String>opsForHash().remove(LAST_KEY, fields))
            .defaultIfEmpty(0L);
    }

    private Mono<List<String>> multiGet(String key, List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return Mono.just(Collections.emptyList());
        }
        return redis
            .<String, String>opsForHash()
            .multiGet(key, fields);
    }

    private Mono<Boolean> updateLastValues(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Mono.just(false);
        }
        return redis
            .<String, String>opsForHash()
            .putAll(LAST_KEY, values);
    }

    private String configIndexValue(Map<String, String> valueMapping) {
        try {
            return objectMapper.writeValueAsString(valueMapping == null ? Collections.emptyMap() : valueMapping);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String mapValue(String mappingJson, String rawValue) {
        if (!StringUtils.hasText(mappingJson)) {
            return rawValue;
        }
        try {
            Map<String, Object> mapping = objectMapper.readValue(mappingJson, MAP_TYPE);
            return mappedValue(mapping, rawValue);
        } catch (Exception e) {
            log.debug("change-property parse value mapping failed: {}", e.toString());
            return rawValue;
        }
    }

    private String mappedValue(Map<String, ?> mapping, String rawValue) {
        if (mapping == null || mapping.isEmpty()) {
            return rawValue;
        }
        Object exact = mapping.get(rawValue);
        if (exact != null) {
            return String.valueOf(exact);
        }
        String comparable = comparableValue(rawValue);
        Object normalized = mapping.get(comparable);
        if (normalized != null) {
            return String.valueOf(normalized);
        }
        for (Map.Entry<String, ?> entry : mapping.entrySet()) {
            if (sameValue(entry.getKey(), comparable)) {
                return String.valueOf(entry.getValue());
            }
        }
        return rawValue;
    }

    private static <T> T valueAt(List<T> values, int index) {
        return values == null || index >= values.size() ? null : values.get(index);
    }

    private static boolean sameValue(String left, String right) {
        return comparableValue(left).equals(comparableValue(right));
    }

    private static String comparableValue(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        try {
            return new BigDecimal(trimmed).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignore) {
            return value;
        }
    }

    private String valueToText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private static String pairKey(String deviceId, String propertyId) {
        return deviceId + PAIR_SEPARATOR + propertyId;
    }

    private static String deviceIdFromPair(String pair) {
        int index = pair == null ? -1 : pair.indexOf(PAIR_SEPARATOR);
        return index < 0 ? "" : pair.substring(0, index);
    }

    private static String propertyIdFromPair(String pair) {
        int index = pair == null ? -1 : pair.indexOf(PAIR_SEPARATOR);
        return index < 0 ? "" : pair.substring(index + 1);
    }

    private String buildMqttTopic(RuntimeConfig config) {
        String prefix = firstText(config.mqttTopicPrefix, defaultMqttTopicPrefix);
        if (!StringUtils.hasText(prefix)) {
            prefix = "IOT/Business";
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + "/" + config.productId + "/" + config.deviceId + "/Data/Change";
    }

    private static String firstText(String first, String fallback) {
        return StringUtils.hasText(first) ? first.trim() : trimToNull(fallback);
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static String runtimeKey(String deviceId, String propertyId) {
        return deviceId + "\n" + propertyId;
    }

    private static class MonitorResult {
        private int latestCount;
        private int configCount;
        private int initializedCount;
        private int missingCount;
        private int changedCount;
        private int failedDevices;

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("latestCount", latestCount);
            result.put("configCount", configCount);
            result.put("initializedCount", initializedCount);
            result.put("missingCount", missingCount);
            result.put("changedCount", changedCount);
            result.put("failedDevices", failedDevices);
            return result;
        }
    }

    private static class LatestValue {
        private long timestamp;
        private String value;
    }

    private static class DeviceChange {
        private final RuntimeConfig firstConfig;
        private final String productId;
        private final String deviceId;
        private final Map<String, String> baselineToWrite = new LinkedHashMap<>();
        private final Map<String, Object> reportProperties = new LinkedHashMap<>();
        private final Map<String, Long> propertySourceTimes = new LinkedHashMap<>();
        private final List<Map<String, Object>> changed = new ArrayList<>();
        private long timestamp;

        private DeviceChange(RuntimeConfig firstConfig) {
            this.firstConfig = firstConfig;
            this.productId = firstConfig.productId;
            this.deviceId = firstConfig.deviceId;
        }
    }

    @Getter
    private static class ProductRuntimeSubscription {
        private final String productId;
        private final ConcurrentHashMap<String, RuntimeConfig> configs = new ConcurrentHashMap<>();
        private final AtomicLong droppedMessages = new AtomicLong();
        private final AtomicLong lastDropTime = new AtomicLong();
        private volatile Disposable disposable;

        private ProductRuntimeSubscription(String productId) {
            this.productId = productId;
        }

        private boolean isActive() {
            return disposable != null && !disposable.isDisposed();
        }

        private void dispose() {
            Disposable disposableRef = disposable;
            if (disposableRef != null && !disposableRef.isDisposed()) {
                disposableRef.dispose();
            }
        }
    }

    private static class RuntimeConfig {
        private String id;
        private String productId;
        private String deviceId;
        private String propertyId;
        private String mqttNetworkId;
        private String mqttTopicPrefix;
        private Integer mqttQos;
        private static RuntimeConfig of(ChangePropertyConfigEntity entity) {
            RuntimeConfig config = new RuntimeConfig();
            config.id = entity.getId();
            config.productId = entity.getProductId();
            config.deviceId = entity.getDeviceId();
            config.propertyId = entity.getPropertyId();
            config.mqttNetworkId = entity.getMqttNetworkId();
            config.mqttTopicPrefix = entity.getMqttTopicPrefix();
            config.mqttQos = entity.getMqttQos();
            return config;
        }
    }

    @RequiredArgsConstructor
    private static class PropertyPayload {
        private final String sourceType;
        private final PropertyMessage propertyMessage;
        private final Map<String, Object> properties;
    }
}
