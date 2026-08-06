package org.jetlinks.community.standalone.forward;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.community.network.DefaultNetworkType;
import org.jetlinks.community.network.NetworkManager;
import org.jetlinks.community.network.mqtt.client.MqttClient;
import org.jetlinks.core.event.EventBus;
import org.jetlinks.core.event.Subscription;
import org.jetlinks.core.message.DeviceMessage;
import org.jetlinks.core.message.codec.SimpleMqttMessage;
import org.jetlinks.core.message.property.ReadPropertyMessageReply;
import org.jetlinks.core.message.property.ReportPropertyMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class MqttForwardSubscriptionService implements CommandLineRunner {

    private static final int MESSAGE_CONCURRENCY = 4;
    private static final String WILDCARD_PROPERTY = "*";
    private static final Duration LEASE_TTL = Duration.ofSeconds(180);
    private static final Duration LEASE_CLEANUP_INTERVAL = Duration.ofSeconds(30);
    private static final String SOURCE_TEMPORARY = "temporary";
    private static final String SOURCE_LEGACY = "legacy";
    private static final int MAX_IDENTIFIER_LENGTH = 256;
    private static final int MAX_TOPIC_LENGTH = 1024;
    private static final int MAX_PROPERTIES_TEXT_LENGTH = 65_535;

    private final EventBus eventBus;
    private final NetworkManager networkManager;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeviceEventSubscription> deviceSubscriptions = new ConcurrentHashMap<>();

    // deviceId -> propertyId or "*" -> leaseIds
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Set<String>>> propertyLeaseIndex =
        new ConcurrentHashMap<>();

    @Value("${gplink.mqtt-forward.max-active-leases:10000}")
    private int maxActiveLeases = 10_000;

    @Value("${gplink.mqtt-forward.max-devices-per-lease:1000}")
    private int maxDevicesPerLease = 1_000;

    @Value("${gplink.mqtt-forward.max-properties-per-lease:1000}")
    private int maxPropertiesPerLease = 1_000;

    private Disposable leaseCleanupTask;

    @Override
    public void run(String... args) {
        if (leaseCleanupTask != null) {
            leaseCleanupTask.dispose();
        }
        leaseCleanupTask = Flux.interval(LEASE_CLEANUP_INTERVAL)
            .subscribe(
                tick -> cleanupExpiredLeasesSafely(),
                error -> log.error("mqtt-forward lease cleanup task terminated", error)
            );
    }

    public Mono<MqttForwardLeaseResponse> createByDevices(DeviceSubscribeRequest request) {
        return createByDevices(request, SOURCE_TEMPORARY);
    }

    public Mono<MqttForwardLeaseResponse> createLegacyByDevices(DeviceSubscribeRequest request) {
        return createByDevices(request, SOURCE_LEGACY);
    }

    private Mono<MqttForwardLeaseResponse> createByDevices(DeviceSubscribeRequest request, String source) {
        return Mono.fromSupplier(() -> {
            Lease lease = toLease(request, source);
            if (leases.size() >= maxActiveLeases) {
                cleanupExpiredLeases();
            }
            synchronized (this) {
                if (leases.size() >= maxActiveLeases) {
                    throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Too many active MQTT forward leases"
                    );
                }
                leases.put(lease.leaseId, lease);
                try {
                    addLeaseToIndex(lease);
                    for (String deviceId : lease.deviceIds) {
                        ensureDeviceSubscription(lease.productId, deviceId);
                    }
                } catch (RuntimeException | Error e) {
                    removeLeaseInternal(lease.leaseId);
                    throw e;
                }
            }
            return toResponse(lease);
        });
    }

    public Mono<List<String>> cancelLegacyByDevice(String productId, String deviceId, String topicName) {
        return Mono.fromSupplier(() -> {
            synchronized (this) {
                List<String> leaseIds = leases
                    .values()
                    .stream()
                    .filter(lease -> SOURCE_LEGACY.equals(lease.source))
                    .filter(lease -> textEquals(lease.productId, productId))
                    .filter(lease -> lease.deviceIds.contains(deviceId))
                    .filter(lease -> isBlank(topicName) || textEquals(lease.mqttTopicName, topicName))
                    .map(lease -> lease.leaseId)
                    .collect(Collectors.toList());
                leaseIds.forEach(this::removeLeaseInternal);
                return leaseIds;
            }
        });
    }

    public Mono<MqttForwardLeaseResponse> renewLease(String leaseId) {
        return Mono.fromSupplier(() -> {
            synchronized (this) {
                Lease lease = leases.get(leaseId);
                long now = System.currentTimeMillis();
                if (lease == null) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MQTT forward lease not found");
                }
                if (lease.isExpired(now)) {
                    removeLeaseInternal(leaseId);
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MQTT forward lease expired");
                }
                lease.expiresAt = now + LEASE_TTL.toMillis();
                return toResponse(lease);
            }
        });
    }

    public Mono<Void> closeLease(String leaseId) {
        return Mono.fromRunnable(() -> {
            synchronized (this) {
                removeLeaseInternal(leaseId);
            }
        });
    }

    public Map<String, Object> getActiveSubscriptions() {
        cleanupExpiredLeases();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ttlSeconds", LEASE_TTL.getSeconds());
        result.put("leaseCount", leases.size());
        result.put("deviceSubscriptionCount", deviceSubscriptions.size());
        result.put("indexDeviceCount", propertyLeaseIndex.size());
        result.put("indexEntryCount", propertyLeaseIndex.values()
            .stream()
            .mapToInt(Map::size)
            .sum());
        result.put("indexLeaseRefCount", propertyLeaseIndex.values()
            .stream()
            .flatMap(index -> index.values().stream())
            .mapToInt(Set::size)
            .sum());
        result.put("forwardedLeaseCount", leases.values()
            .stream()
            .filter(lease -> lease.forwardCount() > 0)
            .count());
        result.put("totalForwardCount", leases.values()
            .stream()
            .mapToLong(Lease::forwardCount)
            .sum());
        result.put("lastForwardTime", leases.values()
            .stream()
            .mapToLong(lease -> lease.lastForwardTime)
            .max()
            .orElse(0L));
        result.put("leases", leases.values()
            .stream()
            .map(this::toResponse)
            .collect(Collectors.toList()));

        Map<String, Boolean> activeDevices = new LinkedHashMap<>();
        deviceSubscriptions.forEach((deviceId, subscription) ->
            activeDevices.put(deviceId, subscription.isActive()));
        result.put("deviceSubscriptions", activeDevices);
        return result;
    }

    private Lease toLease(DeviceSubscribeRequest request, String source) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
        if (isBlank(request.getProductId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "productId is required");
        }
        if (isBlank(request.getMqttNetworkId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mqttNetworkId is required");
        }
        validateLength(request.getProductId(), "productId", MAX_IDENTIFIER_LENGTH);
        validateLength(request.getMqttNetworkId(), "mqttNetworkId", MAX_IDENTIFIER_LENGTH);
        validateOptionalLength(request.getMqttTopicPrefix(), "mqttTopicPrefix", MAX_TOPIC_LENGTH);
        validateOptionalLength(request.getMqttTopicName(), "mqttTopicName", MAX_TOPIC_LENGTH);
        if (request.getMqttQos() < 0 || request.getMqttQos() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mqttQos must be between 0 and 2");
        }

        Set<String> deviceIds = parseDeviceIds(request.getDeviceIds());
        Set<String> watchedProperties = parseWatchedProperties(request.getWatchedProperties());
        long now = System.currentTimeMillis();
        return new Lease(
            UUID.randomUUID().toString(),
            request.getProductId().trim(),
            deviceIds,
            watchedProperties,
            request.getMqttNetworkId().trim(),
            request.getMqttTopicPrefix(),
            request.getMqttTopicName(),
            request.getMqttQos(),
            now,
            now + LEASE_TTL.toMillis(),
            source
        );
    }

    private Set<String> parseDeviceIds(List<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceIds is required");
        }
        if (deviceIds.size() > maxDevicesPerLease) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "deviceIds exceeds maximum size " + maxDevicesPerLease
            );
        }
        Set<String> result = deviceIds.stream()
            .filter(id -> !isBlank(id))
            .map(String::trim)
            .peek(id -> validateLength(id, "deviceId", MAX_IDENTIFIER_LENGTH))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (result.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceIds is required");
        }
        return result;
    }

    private Set<String> parseWatchedProperties(String watchedProperties) {
        if (isBlank(watchedProperties)) {
            return Set.of();
        }
        validateLength(watchedProperties, "watchedProperties", MAX_PROPERTIES_TEXT_LENGTH);
        String[] propertyValues = watchedProperties.split("[,;]", maxPropertiesPerLease + 2);
        if (propertyValues.length > maxPropertiesPerLease) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "watchedProperties exceeds maximum size " + maxPropertiesPerLease
            );
        }
        Set<String> result = Arrays.stream(propertyValues)
            .filter(property -> !isBlank(property))
            .map(String::trim)
            .peek(property -> validateLength(property, "propertyId", MAX_IDENTIFIER_LENGTH))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (result.size() > maxPropertiesPerLease) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "watchedProperties exceeds maximum size " + maxPropertiesPerLease
            );
        }
        return result;
    }

    private void addLeaseToIndex(Lease lease) {
        Collection<String> propertyKeys = lease.propertyKeys();
        for (String deviceId : lease.deviceIds) {
            ConcurrentHashMap<String, Set<String>> deviceIndex =
                propertyLeaseIndex.computeIfAbsent(deviceId, ignored -> new ConcurrentHashMap<>());
            for (String propertyKey : propertyKeys) {
                deviceIndex
                    .computeIfAbsent(propertyKey, ignored -> ConcurrentHashMap.newKeySet())
                    .add(lease.leaseId);
            }
        }
    }

    private void ensureDeviceSubscription(String productId, String deviceId) {
        deviceSubscriptions.compute(deviceId, (key, current) -> {
            if (current != null && current.isActive()) {
                if (!current.productId.equals(productId)) {
                    log.warn("mqtt-forward lease reused device [{}] with product [{}], existing product [{}]",
                        deviceId, productId, current.productId);
                }
                return current;
            }

            String[] topics = {
                "/device/" + productId + "/" + deviceId + "/message/property/report",
                "/device/" + productId + "/" + deviceId + "/message/property/read/reply"
            };
            Subscription subscription = Subscription.of(
                "mqtt-forward:temporary:" + deviceId,
                topics,
                Subscription.Feature.local,
                Subscription.Feature.broker
            );

            AtomicReference<DeviceEventSubscription> subscriptionRef = new AtomicReference<>();
            Disposable disposable = eventBus
                .subscribe(subscription, DeviceMessage.class)
                .onBackpressureDrop(msg -> log.warn(
                    "mqtt-forward drop: device={} messageDevice={}", deviceId, msg.getDeviceId()))
                .flatMap(this::forwardMatchedProperties, MESSAGE_CONCURRENCY)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                    .maxBackoff(Duration.ofSeconds(30))
                    .doBeforeRetry(signal -> log.warn(
                        "mqtt-forward device subscription [{}] retrying after error: {}",
                        deviceId,
                        signal.failure() == null ? "unknown" : signal.failure().getMessage())))
                .subscribe(
                    null,
                    err -> {
                        log.error("mqtt-forward device subscription [{}] terminated with error", deviceId, err);
                        DeviceEventSubscription failed = subscriptionRef.get();
                        if (failed != null) {
                            deviceSubscriptions.remove(deviceId, failed);
                        }
                    }
                );
            DeviceEventSubscription created = new DeviceEventSubscription(productId, disposable);
            subscriptionRef.set(created);
            return created;
        });
    }

    private Mono<Void> forwardMatchedProperties(DeviceMessage msg) {
        Map<String, Object> properties = extractRawProperties(msg);
        if (properties == null || properties.isEmpty() || isBlank(msg.getDeviceId())) {
            return Mono.empty();
        }

        ConcurrentHashMap<String, Set<String>> deviceIndex = propertyLeaseIndex.get(msg.getDeviceId());
        if (deviceIndex == null || deviceIndex.isEmpty()) {
            return Mono.empty();
        }

        long now = System.currentTimeMillis();
        Map<String, MatchedLease> matches = new LinkedHashMap<>();
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            collectMatches(deviceIndex.get(property.getKey()), property, now, matches);
            collectMatches(deviceIndex.get(WILDCARD_PROPERTY), property, now, matches);
        }

        if (matches.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(matches.values())
            .flatMap(match -> publishMatchedProperties(msg, match), MESSAGE_CONCURRENCY)
            .then();
    }

    private void collectMatches(Set<String> leaseIds,
                                Map.Entry<String, Object> property,
                                long now,
                                Map<String, MatchedLease> matches) {
        if (leaseIds == null || leaseIds.isEmpty()) {
            return;
        }
        for (String leaseId : leaseIds) {
            Lease lease = leases.get(leaseId);
            if (lease == null) {
                continue;
            }
            if (lease.isExpired(now)) {
                removeExpiredLease(leaseId, now);
                continue;
            }
            matches
                .computeIfAbsent(leaseId, ignored -> new MatchedLease(lease))
                .properties
                .put(property.getKey(), property.getValue());
        }
    }

    private Mono<Void> publishMatchedProperties(DeviceMessage msg, MatchedLease match) {
        return Mono.defer(() -> {
            Lease activeLease = getActiveLease(match.lease);
            if (activeLease == null) {
                return Mono.empty();
            }
            return networkManager
                .<MqttClient>getNetwork(DefaultNetworkType.MQTT_CLIENT, activeLease.mqttNetworkId)
                .flatMap(client -> {
                    Lease leaseBeforePublish = getActiveLease(match.lease);
                    if (leaseBeforePublish == null) {
                        return Mono.empty();
                    }
                    SimpleMqttMessage mqttMessage = new SimpleMqttMessage();
                    mqttMessage.setTopic(buildMqttTopic(leaseBeforePublish, msg));
                    mqttMessage.setQosLevel(leaseBeforePublish.mqttQos);
                    mqttMessage.setPayload(Unpooled.copiedBuffer(buildPayload(msg, match.properties)));
                    return client
                        .publish(mqttMessage)
                        .doOnSuccess(ignore -> {
                            Lease leaseAfterPublish = getActiveLease(leaseBeforePublish);
                            if (leaseAfterPublish != null) {
                                leaseAfterPublish.recordForward(msg.getDeviceId(), match.properties.keySet());
                            }
                        });
                })
                .onErrorResume(e -> {
                    log.warn("mqtt-forward publish failed: lease={} device={} cause={}",
                        match.lease.leaseId, msg.getDeviceId(), e.getMessage());
                    return Mono.empty();
                });
        });
    }

    private Lease getActiveLease(Lease lease) {
        Lease activeLease = leases.get(lease.leaseId);
        long now = System.currentTimeMillis();
        if (activeLease != lease) {
            return null;
        }
        if (activeLease.isExpired(now)) {
            removeExpiredLease(activeLease.leaseId, now);
            return null;
        }
        return activeLease;
    }

    private Map<String, Object> extractRawProperties(DeviceMessage msg) {
        if (msg instanceof ReportPropertyMessage report) {
            return report.getProperties();
        } else if (msg instanceof ReadPropertyMessageReply reply) {
            return reply.getProperties();
        }
        return null;
    }

    private String buildMqttTopic(Lease lease, DeviceMessage msg) {
        if (!isBlank(lease.mqttTopicName)) {
            return lease.mqttTopicName;
        }
        String prefix = isBlank(lease.mqttTopicPrefix) ? "IOT/Business" : lease.mqttTopicPrefix;
        return prefix + "/" + lease.productId + "/" + msg.getDeviceId() + "/Data/Report";
    }

    private byte[] buildPayload(DeviceMessage msg, Map<String, Object> properties) {
        List<Map<String, Object>> dataObject = new ArrayList<>();
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("Key", e.getKey());
            item.put("Value", e.getValue());
            dataObject.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("MsgType", "MQData");
        data.put("Style", "Property");
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

    private void cleanupExpiredLeases() {
        long now = System.currentTimeMillis();
        List<String> expired = leases.values()
            .stream()
            .filter(lease -> lease.isExpired(now))
            .map(lease -> lease.leaseId)
            .collect(Collectors.toList());
        if (expired.isEmpty()) {
            return;
        }
        synchronized (this) {
            for (String leaseId : expired) {
                Lease lease = leases.get(leaseId);
                if (lease != null && lease.isExpired(now)) {
                    removeLeaseInternal(leaseId);
                }
            }
        }
    }

    private void cleanupExpiredLeasesSafely() {
        try {
            cleanupExpiredLeases();
        } catch (RuntimeException error) {
            log.error("mqtt-forward lease cleanup failed", error);
        }
    }

    private void removeExpiredLease(String leaseId, long now) {
        synchronized (this) {
            Lease lease = leases.get(leaseId);
            if (lease != null && lease.isExpired(now)) {
                removeLeaseInternal(leaseId);
            }
        }
    }

    private Lease removeLeaseInternal(String leaseId) {
        Lease lease = leases.remove(leaseId);
        if (lease == null) {
            return null;
        }

        Collection<String> propertyKeys = lease.propertyKeys();
        for (String deviceId : lease.deviceIds) {
            ConcurrentHashMap<String, Set<String>> deviceIndex = propertyLeaseIndex.get(deviceId);
            if (deviceIndex == null) {
                continue;
            }
            for (String propertyKey : propertyKeys) {
                Set<String> indexedLeases = deviceIndex.get(propertyKey);
                if (indexedLeases == null) {
                    continue;
                }
                indexedLeases.remove(leaseId);
                if (indexedLeases.isEmpty()) {
                    deviceIndex.remove(propertyKey, indexedLeases);
                }
            }
            if (deviceIndex.isEmpty()) {
                propertyLeaseIndex.remove(deviceId, deviceIndex);
                stopDeviceSubscription(deviceId);
            }
        }
        return lease;
    }

    private void stopDeviceSubscription(String deviceId) {
        DeviceEventSubscription subscription = deviceSubscriptions.remove(deviceId);
        if (subscription != null) {
            subscription.dispose();
        }
    }

    private MqttForwardLeaseResponse toResponse(Lease lease) {
        MqttForwardLeaseResponse response = new MqttForwardLeaseResponse();
        response.setLeaseId(lease.leaseId);
        response.setProductId(lease.productId);
        response.setMqttNetworkId(lease.mqttNetworkId);
        response.setMqttTopicPrefix(lease.mqttTopicPrefix);
        response.setMqttTopicName(lease.mqttTopicName);
        response.setMqttQos(lease.mqttQos);
        response.setCreatedAt(lease.createdAt);
        response.setExpiresAt(lease.expiresAt);
        response.setTtlSeconds(LEASE_TTL.getSeconds());
        response.setDeviceIds(new ArrayList<>(lease.deviceIds));
        response.setWatchedProperties(lease.watchedProperties.isEmpty()
            ? List.of()
            : new ArrayList<>(lease.watchedProperties));
        response.setForwardCount(lease.forwardCount());
        response.setLastForwardTime(lease.lastForwardTime);
        response.setLastForwardDeviceId(lease.lastForwardDeviceId);
        response.setLastForwardProperties(new ArrayList<>(lease.lastForwardProperties));
        return response;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void validateLength(String value, String name, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                name + " exceeds maximum length " + maxLength
            );
        }
    }

    private void validateOptionalLength(String value, String name, int maxLength) {
        if (!isBlank(value)) {
            validateLength(value, name, maxLength);
        }
    }

    private boolean textEquals(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    @PreDestroy
    public void destroy() {
        if (leaseCleanupTask != null) {
            leaseCleanupTask.dispose();
        }
        deviceSubscriptions.values().forEach(DeviceEventSubscription::dispose);
        deviceSubscriptions.clear();
        leases.clear();
        propertyLeaseIndex.clear();
    }

    private static final class Lease {
        final String leaseId;
        final String productId;
        final Set<String> deviceIds;
        final Set<String> watchedProperties;
        final String mqttNetworkId;
        final String mqttTopicPrefix;
        final String mqttTopicName;
        final int mqttQos;
        final long createdAt;
        final String source;
        final AtomicLong forwardCount = new AtomicLong();
        volatile long expiresAt;
        volatile long lastForwardTime;
        volatile String lastForwardDeviceId;
        volatile List<String> lastForwardProperties = List.of();

        private Lease(String leaseId,
                      String productId,
                      Set<String> deviceIds,
                      Set<String> watchedProperties,
                      String mqttNetworkId,
                      String mqttTopicPrefix,
                      String mqttTopicName,
                      int mqttQos,
                      long createdAt,
                      long expiresAt,
                      String source) {
            this.leaseId = leaseId;
            this.productId = productId;
            this.deviceIds = Set.copyOf(deviceIds);
            this.watchedProperties = Set.copyOf(watchedProperties);
            this.mqttNetworkId = mqttNetworkId;
            this.mqttTopicPrefix = mqttTopicPrefix;
            this.mqttTopicName = mqttTopicName;
            this.mqttQos = mqttQos;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.source = source;
        }

        boolean isExpired(long now) {
            return expiresAt <= now;
        }

        Collection<String> propertyKeys() {
            return watchedProperties.isEmpty() ? List.of(WILDCARD_PROPERTY) : watchedProperties;
        }

        long forwardCount() {
            return forwardCount.get();
        }

        void recordForward(String deviceId, Collection<String> properties) {
            forwardCount.incrementAndGet();
            lastForwardTime = System.currentTimeMillis();
            lastForwardDeviceId = deviceId;
            lastForwardProperties = List.copyOf(properties);
        }
    }

    private static final class DeviceEventSubscription {
        final String productId;
        final Disposable disposable;

        private DeviceEventSubscription(String productId, Disposable disposable) {
            this.productId = productId;
            this.disposable = disposable;
        }

        boolean isActive() {
            return disposable != null && !disposable.isDisposed();
        }

        void dispose() {
            if (disposable != null) {
                disposable.dispose();
            }
        }
    }

    private static final class MatchedLease {
        final Lease lease;
        final Map<String, Object> properties = new LinkedHashMap<>();

        private MatchedLease(Lease lease) {
            this.lease = lease;
        }
    }
}
