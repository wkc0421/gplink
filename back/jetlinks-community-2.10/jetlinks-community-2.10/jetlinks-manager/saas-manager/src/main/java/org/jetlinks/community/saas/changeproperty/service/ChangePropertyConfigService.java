package org.jetlinks.community.saas.changeproperty.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChangePropertyConfigService
    extends GenericReactiveCrudService<ChangePropertyConfigEntity, String>
    implements CommandLineRunner {

    private static final TypeReference<Object> OBJECT_TYPE = new TypeReference<>() {};

    private final LocalDeviceInstanceService deviceService;
    private final LocalDeviceProductService productService;
    private final DeviceDataService deviceDataService;
    private final EventBus eventBus;
    private final ChangePropertyMqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;

    private final Object runtimeMonitor = new Object();
    private final ConcurrentHashMap<String, ProductRuntimeSubscription> productSubscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RuntimeConfig> configIndex = new ConcurrentHashMap<>();

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

    @Override
    public void run(String... args) {
        reloadSubscriptions()
            .doOnSuccess(stats -> log.info("change-property runtime initialized: {}", stats))
            .doOnError(err -> log.warn("change-property runtime initialization failed", err))
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
                    mergeEditable(existing, prepared, false);
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
                        copyAuditAndBaseline(existing, prepared);
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
            .map(enabledConfigs -> {
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
                return getRuntimeStats();
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
        return Mono.fromRunnable(() -> {
            synchronized (runtimeMonitor) {
                if (Boolean.TRUE.equals(entity.getEnabled())) {
                    addRuntimeNow(entity);
                } else {
                    removeRuntimeNow(entity);
                }
            }
        });
    }

    public Mono<Void> removeRuntime(ChangePropertyConfigEntity entity) {
        return Mono.fromRunnable(() -> {
            synchronized (runtimeMonitor) {
                removeRuntimeNow(entity);
            }
        });
    }

    private Mono<Void> removeRuntime(List<ChangePropertyConfigEntity> entities) {
        return Mono.fromRunnable(() -> {
            synchronized (runtimeMonitor) {
                entities.forEach(this::removeRuntimeNow);
            }
        });
    }

    private Mono<Void> replaceRuntime(ChangePropertyConfigEntity previous,
                                      ChangePropertyConfigEntity current) {
        return Mono.fromRunnable(() -> {
            synchronized (runtimeMonitor) {
                removeRuntimeNow(previous);
                if (Boolean.TRUE.equals(current.getEnabled())) {
                    addRuntimeNow(current);
                } else {
                    removeRuntimeNow(current);
                }
            }
        });
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
        entity.setLastValueJson(request.getLastValueJson());
        entity.setLastValueTime(request.getLastValueTime());
        entity.setLastChangeTime(request.getLastChangeTime());
        entity.setRemark(request.getRemark());
        return entity;
    }

    private Mono<Void> assertUniqueForUpdate(String id, ChangePropertyConfigEntity prepared) {
        return findByUnique(prepared.getDeviceId(), prepared.getPropertyId())
            .flatMap(existing -> Objects.equals(existing.getId(), id)
                ? Mono.<Void>empty()
                : Mono.error(new ValidationException("deviceId + propertyId already exists")));
    }

    private void copyAuditAndBaseline(ChangePropertyConfigEntity source, ChangePropertyConfigEntity target) {
        target.setCreatorId(source.getCreatorId());
        target.setCreatorName(source.getCreatorName());
        target.setCreateTime(source.getCreateTime());
        if (target.getLastValueJson() == null) {
            target.setLastValueJson(source.getLastValueJson());
        }
        if (target.getLastValueTime() == null) {
            target.setLastValueTime(source.getLastValueTime());
        }
        if (target.getLastChangeTime() == null) {
            target.setLastChangeTime(source.getLastChangeTime());
        }
    }

    private void mergeEditable(ChangePropertyConfigEntity existing,
                               ChangePropertyConfigEntity prepared,
                               boolean overwriteBaseline) {
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
        existing.setRemark(prepared.getRemark());
        if (overwriteBaseline) {
            existing.setLastValueJson(prepared.getLastValueJson());
            existing.setLastValueTime(prepared.getLastValueTime());
            existing.setLastChangeTime(prepared.getLastChangeTime());
        }
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

        return Flux
            .fromIterable(payload.properties.entrySet())
            .flatMap(entry -> {
                RuntimeConfig config = subscription.configs.get(runtimeKey(message.getDeviceId(), entry.getKey()));
                if (config == null) {
                    return Mono.empty();
                }
                long sourceTime = payload
                    .propertyMessage
                    .getPropertySourceTime(entry.getKey())
                    .orElse(message.getTimestamp());
                return handlePropertyValue(config, payload.sourceType, entry.getValue(), sourceTime);
            }, Math.max(1, perProductConcurrency))
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

    private Mono<Void> handlePropertyValue(RuntimeConfig config,
                                           String sourceType,
                                           Object currentValue,
                                           long valueTime) {
        return Mono.defer(() -> {
            String currentJson = serializeValue(config, currentValue);
            if (currentJson == null) {
                return Mono.empty();
            }

            String previousJson = config.lastValueJson.get();
            if (Objects.equals(previousJson, currentJson)) {
                return Mono.empty();
            }

            Long changeTime = previousJson == null ? null : System.currentTimeMillis();
            return updateBaseline(config, previousJson, currentJson, valueTime, changeTime)
                .flatMap(updated -> {
                    if (!updated) {
                        return Mono.empty();
                    }
                    acceptBaseline(config, currentJson, valueTime, changeTime);
                    if (previousJson == null) {
                        return Mono.empty();
                    }

                    Object previousValue = deserializeValue(previousJson);
                    return saveChangeReport(config, currentValue, previousValue, sourceType, valueTime)
                        .onErrorResume(err -> {
                            saveFailures.incrementAndGet();
                            log.warn("change-property save report failed: config={} device={} property={} cause={}",
                                config.id, config.deviceId, config.propertyId, err.getMessage(), err);
                            return Mono.empty();
                        })
                        .then(publishChange(config, currentValue, previousValue, valueTime));
                });
        });
    }

    private String serializeValue(RuntimeConfig config, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.getBytes(StandardCharsets.UTF_8).length > Math.max(1, maxValueBytes)) {
                oversizedValues.incrementAndGet();
                log.warn("change-property value skipped because serialized value is too large: config={} device={} property={} bytes={}",
                    config.id,
                    config.deviceId,
                    config.propertyId,
                    json.getBytes(StandardCharsets.UTF_8).length);
                return null;
            }
            return json;
        } catch (JsonProcessingException e) {
            processingErrors.incrementAndGet();
            log.warn("change-property value serialization failed: config={} device={} property={}",
                config.id, config.deviceId, config.propertyId, e);
            return null;
        }
    }

    private Object deserializeValue(String json) {
        try {
            return objectMapper.readValue(json, OBJECT_TYPE);
        } catch (Exception e) {
            return json;
        }
    }

    private Mono<Boolean> updateBaseline(RuntimeConfig config,
                                         String previousJson,
                                         String currentJson,
                                         long valueTime,
                                         Long changeTime) {
        var update = createUpdate()
            .set(ChangePropertyConfigEntity::getLastValueJson, currentJson)
            .set(ChangePropertyConfigEntity::getLastValueTime, valueTime);
        if (changeTime != null) {
            update.set(ChangePropertyConfigEntity::getLastChangeTime, changeTime);
        }
        var where = update.where(ChangePropertyConfigEntity::getId, config.id);
        if (previousJson == null) {
            where.isNull(ChangePropertyConfigEntity::getLastValueJson);
        } else {
            where.and(ChangePropertyConfigEntity::getLastValueJson, previousJson);
        }
        return where
            .execute()
            .map(count -> count > 0)
            .flatMap(updated -> updated
                ? Mono.just(true)
                : refreshRuntimeSnapshot(config).thenReturn(false));
    }

    private void acceptBaseline(RuntimeConfig config,
                                String currentJson,
                                long valueTime,
                                Long changeTime) {
        config.lastValueJson.set(currentJson);
        config.lastValueTime.set(valueTime);
        if (changeTime != null) {
            config.lastChangeTime.set(changeTime);
        }
    }

    private Mono<Void> refreshRuntimeSnapshot(RuntimeConfig config) {
        return findById(config.id)
            .flatMap(entity -> Mono
                .fromRunnable(() -> {
                    synchronized (runtimeMonitor) {
                        if (!Boolean.TRUE.equals(entity.getEnabled())) {
                            removeRuntimeConfigNow(config);
                            return;
                        }
                        if (sameRuntimeIdentity(config, entity)) {
                            applyRuntimeSnapshot(config, entity);
                        } else {
                            removeRuntimeConfigNow(config);
                            addRuntimeNow(entity);
                        }
                    }
                })
                .thenReturn(true))
            .switchIfEmpty(Mono.defer(() -> Mono
                .fromRunnable(() -> {
                    synchronized (runtimeMonitor) {
                        removeRuntimeConfigNow(config);
                    }
                })
                .thenReturn(false)))
            .then();
    }

    private boolean sameRuntimeIdentity(RuntimeConfig config,
                                        ChangePropertyConfigEntity entity) {
        return Objects.equals(config.productId, entity.getProductId())
            && Objects.equals(config.deviceId, entity.getDeviceId())
            && Objects.equals(config.propertyId, entity.getPropertyId());
    }

    private void applyRuntimeSnapshot(RuntimeConfig config,
                                      ChangePropertyConfigEntity entity) {
        config.mqttNetworkId = entity.getMqttNetworkId();
        config.mqttTopicPrefix = entity.getMqttTopicPrefix();
        config.mqttQos = entity.getMqttQos();
        config.lastValueJson.set(entity.getLastValueJson());
        config.lastValueTime.set(entity.getLastValueTime() == null ? 0L : entity.getLastValueTime());
        config.lastChangeTime.set(entity.getLastChangeTime() == null ? 0L : entity.getLastChangeTime());
    }

    private Mono<Void> saveChangeReport(RuntimeConfig config,
                                        Object currentValue,
                                        Object previousValue,
                                        String sourceType,
                                        long valueTime) {
        ReportPropertyMessage message = new ReportPropertyMessage();
        message.setMessageId(IDGenerator.SNOW_FLAKE_STRING.generate());
        message.setDeviceId(config.deviceId);
        message.setTimestamp(valueTime);
        message.setProperties(Collections.singletonMap(config.propertyId, currentValue));
        message.setPropertySourceTimes(Collections.singletonMap(config.propertyId, valueTime));
        message.addHeader("productId", config.productId);
        message.addHeader("changePropertyConfigId", config.id);
        message.addHeader("previousValue", previousValue);
        message.addHeader("changeSourceMessageType", sourceType);
        return deviceDataService.saveDeviceMessage(message);
    }

    private Mono<Void> publishChange(RuntimeConfig config,
                                     Object currentValue,
                                     Object previousValue,
                                     long valueTime) {
        String mqttNetworkId = firstText(config.mqttNetworkId, defaultMqttNetworkId);
        if (!StringUtils.hasText(mqttNetworkId)) {
            return Mono.empty();
        }

        String topic = buildMqttTopic(config);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("Key", config.propertyId);
        item.put("Value", currentValue);
        item.put("PreviousValue", previousValue);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("MsgType", "MQData");
        body.put("Style", "Change");
        body.put("Sender", "GPLink");
        body.put("Time", valueTime);
        body.put("ProductId", config.productId);
        body.put("DeviceId", config.deviceId);
        body.put("ConfigId", config.id);
        body.put("DataObject", List.of(item));

        int qos = config.mqttQos == null ? defaultMqttQos : config.mqttQos;
        return mqttPublisher
            .publish(new ChangePropertyMqttPayload(mqttNetworkId, topic, qos, body))
            .onErrorResume(err -> {
                publishFailures.incrementAndGet();
                log.warn("change-property mqtt publish failed: config={} device={} property={} cause={}",
                    config.id, config.deviceId, config.propertyId, err.getMessage(), err);
                return Mono.empty();
            });
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
        private final AtomicReference<String> lastValueJson = new AtomicReference<>();
        private final AtomicLong lastValueTime = new AtomicLong();
        private final AtomicLong lastChangeTime = new AtomicLong();

        private static RuntimeConfig of(ChangePropertyConfigEntity entity) {
            RuntimeConfig config = new RuntimeConfig();
            config.id = entity.getId();
            config.productId = entity.getProductId();
            config.deviceId = entity.getDeviceId();
            config.propertyId = entity.getPropertyId();
            config.mqttNetworkId = entity.getMqttNetworkId();
            config.mqttTopicPrefix = entity.getMqttTopicPrefix();
            config.mqttQos = entity.getMqttQos();
            config.lastValueJson.set(entity.getLastValueJson());
            if (entity.getLastValueTime() != null) {
                config.lastValueTime.set(entity.getLastValueTime());
            }
            if (entity.getLastChangeTime() != null) {
                config.lastChangeTime.set(entity.getLastChangeTime());
            }
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
