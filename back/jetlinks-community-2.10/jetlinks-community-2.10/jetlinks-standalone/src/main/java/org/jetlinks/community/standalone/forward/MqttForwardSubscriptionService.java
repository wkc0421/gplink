package org.jetlinks.community.standalone.forward;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.crud.events.EntityDeletedEvent;
import org.hswebframework.web.crud.events.EntityModifyEvent;
import org.hswebframework.web.crud.events.EntitySavedEvent;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.community.standalone.forward.entity.MqttForwardSubscriptionEntity;
import org.jetlinks.community.network.DefaultNetworkType;
import org.jetlinks.community.network.NetworkManager;
import org.jetlinks.community.network.mqtt.client.MqttClient;
import org.jetlinks.core.event.EventBus;
import org.jetlinks.core.event.Subscription;
import org.jetlinks.core.message.DeviceMessage;
import org.jetlinks.core.message.codec.SimpleMqttMessage;
import org.jetlinks.core.message.property.ReadPropertyMessageReply;
import org.jetlinks.core.message.property.ReportPropertyMessage;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class MqttForwardSubscriptionService
    extends GenericReactiveCrudService<MqttForwardSubscriptionEntity, String>
    implements CommandLineRunner {

    /** Max concurrent forwardMessage() calls per subscription stream */
    private static final int MESSAGE_CONCURRENCY = 4;

    /** Evict device property cache entries after this period of inactivity */
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    /** How often to scan and evict stale cache entries */
    private static final Duration CACHE_CLEANUP_INTERVAL = Duration.ofMinutes(10);

    private final EventBus eventBus;
    private final NetworkManager networkManager;
    private final ObjectMapper objectMapper;

    // key = "{entityId}_{messageType}", value = active Disposable
    private final Map<String, Disposable> subscribers = new ConcurrentHashMap<>();

    // key = deviceId — TTL-aware property cache for change detection
    private final ConcurrentHashMap<String, DevicePropertyCache> propertyValueCache =
        new ConcurrentHashMap<>();

    private Disposable cacheCleanupTask;

    /** Wraps per-device property values with a last-access timestamp for TTL eviction. */
    private static final class DevicePropertyCache {
        final ConcurrentHashMap<String, Object> values = new ConcurrentHashMap<>();
        volatile long lastAccessMs = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ startup

    @Override
    public void run(String... args) {
        createQuery()
            .where(MqttForwardSubscriptionEntity::getState, "enabled")
            .fetch()
            .flatMap(entity -> startSubscription(entity)
                .onErrorResume(e -> {
                    log.error("Failed to start mqtt-forward subscription [{}]", entity.getId(), e);
                    return Mono.empty();
                }))
            .subscribe();

        long ttlMs = CACHE_TTL.toMillis();
        cacheCleanupTask = Flux.interval(CACHE_CLEANUP_INTERVAL)
            .subscribe(tick -> {
                long threshold = System.currentTimeMillis() - ttlMs;
                int before = propertyValueCache.size();
                propertyValueCache.entrySet().removeIf(e -> e.getValue().lastAccessMs < threshold);
                int removed = before - propertyValueCache.size();
                if (removed > 0) {
                    log.debug("mqtt-forward cache evicted {} stale device entries", removed);
                }
            });
    }

    // ----------------------------------------------------------------- lifecycle events

    @EventListener
    public void onSaved(EntitySavedEvent<MqttForwardSubscriptionEntity> event) {
        event.async(
            Flux.fromIterable(event.getEntity())
                .flatMap(this::handleStateChange)
        );
    }

    @EventListener
    public void onModified(EntityModifyEvent<MqttForwardSubscriptionEntity> event) {
        event.async(
            Flux.fromIterable(event.getAfter())
                .flatMap(this::handleStateChange)
        );
    }

    @EventListener
    public void onDeleted(EntityDeletedEvent<MqttForwardSubscriptionEntity> event) {
        event.async(
            Flux.fromIterable(event.getEntity())
                .doOnNext(entity -> stopSubscription(entity.getId()))
                .then()
        );
    }

    // ----------------------------------------------------------------- public helpers (for controller)

    public Flux<MqttForwardSubscriptionEntity> createByDevices(DeviceSubscribeRequest request) {
        List<MqttForwardSubscriptionEntity> entities = new ArrayList<>(request.getDeviceIds().size());
        for (String deviceId : request.getDeviceIds()) {
            MqttForwardSubscriptionEntity e = new MqttForwardSubscriptionEntity();
            e.setProductId(request.getProductId());
            e.setDeviceId(deviceId);
            e.setMqttNetworkId(request.getMqttNetworkId());
            e.setMqttTopicPrefix(request.getMqttTopicPrefix());
            e.setMqttQos(request.getMqttQos());
            e.setMessageTypes(request.getMessageTypes());
            e.setWatchedProperties(request.getWatchedProperties());
            e.setState(request.getState() != null ? request.getState() : "enabled");
            e.setDescription(request.getDescription());
            entities.add(e);
        }
        return save(Flux.fromIterable(entities)).thenMany(Flux.fromIterable(entities));
    }

    public Mono<Void> deleteByDeviceIds(List<String> deviceIds) {
        return createQuery()
            .in(MqttForwardSubscriptionEntity::getDeviceId, deviceIds)
            .fetch()
            .map(MqttForwardSubscriptionEntity::getId)
            .collectList()
            .flatMap(ids -> ids.isEmpty() ? Mono.empty() : deleteById(Flux.fromIterable(ids)).then());
    }

    public Map<String, Boolean> getActiveSubscriptions() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        subscribers.forEach((key, d) -> result.put(key, !d.isDisposed()));
        return result;
    }

    // ----------------------------------------------------------------- subscription management

    private Mono<Void> handleStateChange(MqttForwardSubscriptionEntity entity) {
        stopSubscription(entity.getId());
        if (entity.isEnabled()) {
            return startSubscription(entity);
        }
        return Mono.empty();
    }

    Mono<Void> startSubscription(MqttForwardSubscriptionEntity entity) {
        return Mono.fromRunnable(() -> {
            Set<String> types = entity.getMessageTypeSet();
            if (types.isEmpty()) {
                log.warn("mqtt-forward subscription [{}] has no message types configured, skipped", entity.getId());
                return;
            }
            String productId = entity.getProductId();
            String devId = entity.getEffectiveDeviceId();

            for (String type : types) {
                String[] eventBusTopics = buildEventBusTopics(productId, devId, type);
                if (eventBusTopics == null) {
                    continue;
                }
                String key = entity.getId() + "_" + type;
                Subscription subscription = Subscription.of(
                    "mqtt-forward:" + entity.getId() + ":" + type,
                    eventBusTopics,
                    Subscription.Feature.local,
                    Subscription.Feature.broker
                );

                Disposable d = eventBus
                    .subscribe(subscription, DeviceMessage.class)
                    .onBackpressureDrop(msg -> log.warn(
                        "mqtt-forward drop: subscription={} type={} device={}",
                        entity.getId(), type, msg.getDeviceId()))
                    // PROPERTY_CHANGE must be processed sequentially to avoid cache races
                    .flatMap(msg -> forwardMessage(entity, type, msg),
                        "PROPERTY_CHANGE".equals(type) ? 1 : MESSAGE_CONCURRENCY)
                    .subscribe(
                        null,
                        err -> {
                            log.error("mqtt-forward subscription [{}] type [{}] terminated with error",
                                entity.getId(), type, err);
                            subscribers.remove(key);
                        }
                    );

                // If there was already a subscription under this key, dispose the old one
                Disposable old = subscribers.put(key, d);
                if (old != null) {
                    old.dispose();
                }
            }
        });
    }

    void stopSubscription(String entityId) {
        String prefix = entityId + "_";
        subscribers.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(prefix)) {
                entry.getValue().dispose();
                return true;
            }
            return false;
        });
    }

    // ----------------------------------------------------------------- message handling

    private Mono<Void> forwardMessage(MqttForwardSubscriptionEntity entity,
                                      String type,
                                      DeviceMessage msg) {
        Map<String, Object> overrideProps = null;
        if ("PROPERTY_CHANGE".equals(type)) {
            overrideProps = extractChangedProperties(msg);
            if (overrideProps == null || overrideProps.isEmpty()) {
                return Mono.empty();
            }
        }

        // Apply watched-property filter for all property-based message types
        if (overrideProps != null || "PROPERTY_REPORT".equals(type) || "PROPERTY_READ_REPLY".equals(type)) {
            Set<String> watched = entity.getWatchedPropertySet();
            if (!watched.isEmpty()) {
                Map<String, Object> base = overrideProps != null ? overrideProps : extractRawProperties(msg);
                if (base == null || base.isEmpty()) {
                    return Mono.empty();
                }
                overrideProps = new LinkedHashMap<>();
                for (String key : watched) {
                    if (base.containsKey(key)) {
                        overrideProps.put(key, base.get(key));
                    }
                }
                if (overrideProps.isEmpty()) {
                    return Mono.empty();
                }
            }
        }

        final Map<String, Object> propsToSend = overrideProps;
        return networkManager
            .<MqttClient>getNetwork(DefaultNetworkType.MQTT_CLIENT, entity.getMqttNetworkId())
            .flatMap(client -> {
                String topic = buildMqttTopic(entity, type, msg);
                byte[] payload = buildPayload(entity, type, msg, propsToSend);
                SimpleMqttMessage mqttMessage = new SimpleMqttMessage();
                mqttMessage.setTopic(topic);
                mqttMessage.setQosLevel(entity.getMqttQos());
                mqttMessage.setPayload(Unpooled.copiedBuffer(payload));
                return client.publish(mqttMessage);
            })
            .onErrorResume(e -> {
                log.warn("mqtt-forward publish failed: subscription={} type={} device={} cause={}",
                    entity.getId(), type, msg.getDeviceId(), e.getMessage());
                return Mono.empty();
            });
    }

    private Map<String, Object> extractRawProperties(DeviceMessage msg) {
        if (msg instanceof ReportPropertyMessage report) {
            return report.getProperties();
        } else if (msg instanceof ReadPropertyMessageReply reply) {
            return reply.getProperties();
        }
        return null;
    }

    /**
     * Compare incoming property values against the last-known cache.
     * Updates the cache for all received properties and returns only those that changed.
     * Returns null if the message carries no properties.
     */
    private Map<String, Object> extractChangedProperties(DeviceMessage msg) {
        Map<String, Object> incoming = extractRawProperties(msg);
        if (incoming == null || incoming.isEmpty()) {
            return null;
        }
        String deviceId = msg.getDeviceId();
        DevicePropertyCache entry =
            propertyValueCache.computeIfAbsent(deviceId, k -> new DevicePropertyCache());
        entry.lastAccessMs = System.currentTimeMillis();

        Map<String, Object> changed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : incoming.entrySet()) {
            Object newVal = e.getValue();
            Object sentinel = newVal == null ? "" : newVal;
            Object oldVal = entry.values.put(e.getKey(), sentinel);
            if (!Objects.equals(oldVal, sentinel)) {
                changed.put(e.getKey(), newVal);
            }
        }
        return changed;
    }

    private String[] buildEventBusTopics(String productId, String deviceId, String type) {
        return switch (type) {
            case "PROPERTY_REPORT" ->
                new String[]{"/device/" + productId + "/" + deviceId + "/message/property/report"};
            case "PROPERTY_READ_REPLY" ->
                new String[]{"/device/" + productId + "/" + deviceId + "/message/property/read/reply"};
            case "PROPERTY_CHANGE" ->
                // Change detection reuses the property/report stream; filtering happens in forwardMessage()
                new String[]{"/device/" + productId + "/" + deviceId + "/message/property/report"};
            case "ONLINE" ->
                new String[]{"/device/" + productId + "/" + deviceId + "/online"};
            case "OFFLINE" ->
                new String[]{"/device/" + productId + "/" + deviceId + "/offline"};
            default -> {
                log.warn("mqtt-forward: unsupported message type '{}', skipped", type);
                yield null;
            }
        };
    }

    private String buildMqttTopic(MqttForwardSubscriptionEntity entity, String type, DeviceMessage msg) {
        String prefix = (entity.getMqttTopicPrefix() != null && !entity.getMqttTopicPrefix().isBlank())
            ? entity.getMqttTopicPrefix()
            : "IOT/Business";
        String suffix = switch (type) {
            case "PROPERTY_REPORT", "PROPERTY_READ_REPLY" -> "/Data/Report";
            case "PROPERTY_CHANGE" -> "/Data/Change";
            case "ONLINE" -> "/Data/Online";
            case "OFFLINE" -> "/Data/Offline";
            default -> "/Data/" + type;
        };
        return prefix + "/" + entity.getProductId() + "/" + msg.getDeviceId() + suffix;
    }

    private byte[] buildPayload(MqttForwardSubscriptionEntity entity, String type,
                                DeviceMessage msg, Map<String, Object> propsOverride) {
        String style = switch (type) {
            case "PROPERTY_REPORT", "PROPERTY_READ_REPLY" -> "Property";
            case "PROPERTY_CHANGE" -> "Change";
            case "ONLINE" -> "Online";
            case "OFFLINE" -> "Offline";
            default -> type;
        };

        List<Map<String, Object>> dataObject = new ArrayList<>();
        Map<String, Object> properties = propsOverride;
        if (properties == null) {
            if (msg instanceof ReportPropertyMessage report) {
                properties = report.getProperties();
            } else if (msg instanceof ReadPropertyMessageReply reply) {
                properties = reply.getProperties();
            }
        }
        if (properties != null) {
            for (Map.Entry<String, Object> e : properties.entrySet()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("Key", e.getKey());
                item.put("Value", e.getValue());
                dataObject.add(item);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("MsgType", "MQData");
        data.put("Style", style);
        data.put("Sender", "GPLink");
        data.put("Time", msg.getTimestamp());
        data.put("DataObject", dataObject);

        try {
            return objectMapper.writeValueAsBytes(data);
        } catch (JsonProcessingException e) {
            log.warn("mqtt-forward: failed to serialize payload for device {}", msg.getDeviceId(), e);
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
    }

    // ----------------------------------------------------------------- cleanup

    @PreDestroy
    public void destroy() {
        if (cacheCleanupTask != null) {
            cacheCleanupTask.dispose();
        }
        subscribers.values().forEach(Disposable::dispose);
        subscribers.clear();
        propertyValueCache.clear();
    }
}
