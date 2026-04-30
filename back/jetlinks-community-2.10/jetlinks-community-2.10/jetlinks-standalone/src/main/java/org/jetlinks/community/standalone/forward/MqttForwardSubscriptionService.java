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
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class MqttForwardSubscriptionService implements CommandLineRunner {

    private static final int MESSAGE_CONCURRENCY = 4;
    private static final String WILDCARD_PROPERTY = "*";
    private static final Duration LEASE_TTL = Duration.ofSeconds(180);
    private static final Duration LEASE_CLEANUP_INTERVAL = Duration.ofSeconds(30);

    private final EventBus eventBus;
    private final NetworkManager networkManager;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeviceEventSubscription> deviceSubscriptions = new ConcurrentHashMap<>();

    // deviceId -> propertyId or "*" -> leaseIds
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Set<String>>> propertyLeaseIndex =
        new ConcurrentHashMap<>();

    private Disposable leaseCleanupTask;

    @Override
    public void run(String... args) {
        leaseCleanupTask = Flux.interval(LEASE_CLEANUP_INTERVAL)
            .subscribe(tick -> cleanupExpiredLeases());
    }

    public Mono<MqttForwardLeaseResponse> createByDevices(DeviceSubscribeRequest request) {
        return Mono.fromSupplier(() -> {
            Lease lease = toLease(request);
            synchronized (this) {
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

    private Lease toLease(DeviceSubscribeRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
        if (isBlank(request.getProductId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "productId is required");
        }
        if (isBlank(request.getMqttNetworkId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mqttNetworkId is required");
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
            request.getMqttQos(),
            now + LEASE_TTL.toMillis()
        );
    }

    private Set<String> parseDeviceIds(List<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceIds is required");
        }
        Set<String> result = deviceIds.stream()
            .filter(id -> !isBlank(id))
            .map(String::trim)
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
        return Arrays.stream(watchedProperties.split(","))
            .filter(property -> !isBlank(property))
            .map(String::trim)
            .collect(Collectors.toCollection(LinkedHashSet::new));
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
                        deviceSubscriptions.remove(deviceId);
                    }
                );
            return new DeviceEventSubscription(productId, disposable);
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
                    return client.publish(mqttMessage);
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
        response.setExpiresAt(lease.expiresAt);
        response.setTtlSeconds(LEASE_TTL.getSeconds());
        response.setDeviceIds(new ArrayList<>(lease.deviceIds));
        response.setWatchedProperties(lease.watchedProperties.isEmpty()
            ? List.of()
            : new ArrayList<>(lease.watchedProperties));
        return response;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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
        final int mqttQos;
        volatile long expiresAt;

        private Lease(String leaseId,
                      String productId,
                      Set<String> deviceIds,
                      Set<String> watchedProperties,
                      String mqttNetworkId,
                      String mqttTopicPrefix,
                      int mqttQos,
                      long expiresAt) {
            this.leaseId = leaseId;
            this.productId = productId;
            this.deviceIds = Set.copyOf(deviceIds);
            this.watchedProperties = Set.copyOf(watchedProperties);
            this.mqttNetworkId = mqttNetworkId;
            this.mqttTopicPrefix = mqttTopicPrefix;
            this.mqttQos = mqttQos;
            this.expiresAt = expiresAt;
        }

        boolean isExpired(long now) {
            return expiresAt <= now;
        }

        Collection<String> propertyKeys() {
            return watchedProperties.isEmpty() ? List.of(WILDCARD_PROPERTY) : watchedProperties;
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
