package gp.saas.legacy.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class LegacyDeviceLatestValueService {

    private static final String KEY_PREFIX = "device:";
    private static final String KEY_SUFFIX = ":latest";
    private static final String FIELD_PREFIX = "property:";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public LegacyDeviceLatestValueService(ReactiveStringRedisTemplate redisTemplate,
                                          ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public Mono<Map<String, LatestValue>> readAll(String deviceId) {
        return redisTemplate
            .opsForHash()
            .entries(buildKey(deviceId))
            .collectMap(
                entry -> normalizeField(String.valueOf(entry.getKey())),
                entry -> decode(String.valueOf(entry.getValue())),
                LinkedHashMap::new)
            .defaultIfEmpty(Collections.emptyMap())
            .onErrorResume(e -> {
                log.warn("Legacy Redis latest readAll failed: device={}", deviceId, e);
                return Mono.just(Collections.emptyMap());
            });
    }

    public Mono<LatestValue> readProperty(String deviceId, String property) {
        return redisTemplate
            .opsForHash()
            .get(buildKey(deviceId), FIELD_PREFIX + property)
            .cast(String.class)
            .map(this::decode)
            .onErrorResume(e -> {
                log.warn("Legacy Redis latest read failed: device={}, property={}", deviceId, property, e);
                return Mono.empty();
            });
    }

    public Mono<Map<String, LatestValue>> readProperties(String deviceId, List<String> properties) {
        if (properties == null || properties.isEmpty()) {
            return Mono.just(Collections.emptyMap());
        }

        List<String> propertyIds = new ArrayList<>(properties.size());
        List<String> fields = new ArrayList<>(properties.size());
        for (String property : properties) {
            if (property != null && !propertyIds.contains(property)) {
                propertyIds.add(property);
                fields.add(FIELD_PREFIX + property);
            }
        }
        if (fields.isEmpty()) {
            return Mono.just(Collections.emptyMap());
        }

        return redisTemplate
            .<String, String>opsForHash()
            .multiGet(buildKey(deviceId), fields)
            .map(values -> {
                Map<String, LatestValue> result = new LinkedHashMap<>();
                int size = Math.min(propertyIds.size(), values.size());
                for (int i = 0; i < size; i++) {
                    String value = values.get(i);
                    if (value != null) {
                        result.put(propertyIds.get(i), decode(value));
                    }
                }
                return result;
            })
            .defaultIfEmpty(Collections.emptyMap())
            .onErrorResume(e -> {
                log.warn("Legacy Redis latest multiGet failed: device={}, properties={}",
                         deviceId,
                         propertyIds.size(),
                         e);
                return Mono.just(Collections.emptyMap());
            });
    }

    private String buildKey(String deviceId) {
        return KEY_PREFIX + deviceId + KEY_SUFFIX;
    }

    private String normalizeField(String field) {
        return field.startsWith(FIELD_PREFIX) ? field.substring(FIELD_PREFIX.length()) : field;
    }

    private LatestValue decode(String json) {
        try {
            Map<String, Object> value = objectMapper.readValue(json, MAP_TYPE);
            Object timestamp = value.get("ts");
            return new LatestValue(value.get("v"), timestamp instanceof Number ? ((Number) timestamp).longValue() : 0L);
        } catch (Exception e) {
            return new LatestValue(json, 0L);
        }
    }

    @Getter
    @AllArgsConstructor
    public static class LatestValue {
        private final Object value;
        private final long timestamp;
    }
}
