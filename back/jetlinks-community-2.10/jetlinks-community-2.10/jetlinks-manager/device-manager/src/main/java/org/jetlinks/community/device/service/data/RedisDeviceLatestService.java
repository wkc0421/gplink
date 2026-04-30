/*
 * Copyright 2025 JetLinks https://www.jetlinks.cn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetlinks.community.device.service.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis latest cache for device properties.
 * <p>
 * Key: {@code device:{deviceId}:latest}; hash field:
 * {@code property:{propertyId}} = JSON {@code {"v":<value>,"ts":<timestamp>}}.
 */
public class RedisDeviceLatestService {

    private static final String KEY_PREFIX = "device:";
    private static final String KEY_SUFFIX = ":latest";
    static final String FIELD_PREFIX = "property:";

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long ttlSeconds;
    private final Duration writeTimeout;

    private final Counter hitCounter;
    private final Counter missCounter;
    private final Counter writeFailCounter;

    public RedisDeviceLatestService(ReactiveStringRedisTemplate redisTemplate,
                                    ObjectMapper objectMapper,
                                    long ttlSeconds,
                                    long writeTimeoutMs,
                                    MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttlSeconds = ttlSeconds;
        this.writeTimeout = Duration.ofMillis(Math.max(writeTimeoutMs, 1));
        this.hitCounter = Counter.builder("redis.latest.hit")
                                 .description("Redis latest hit count")
                                 .register(meterRegistry);
        this.missCounter = Counter.builder("redis.latest.miss")
                                  .description("Redis latest miss count")
                                  .register(meterRegistry);
        this.writeFailCounter = Counter.builder("redis.latest.write.fail")
                                       .description("Redis latest write failure count")
                                       .register(meterRegistry);
    }

    String buildKey(String deviceId) {
        return KEY_PREFIX + deviceId + KEY_SUFFIX;
    }

    String buildField(String propertyId) {
        return FIELD_PREFIX + propertyId;
    }

    public Mono<Boolean> writeProperty(String deviceId, String propertyId, Object value, long timestamp) {
        return writeProperties(deviceId, Collections.singletonMap(propertyId, value), timestamp);
    }

    public Mono<Boolean> writeProperties(String deviceId, Map<String, Object> properties, long timestamp) {
        if (properties == null || properties.isEmpty()) {
            return Mono.just(true);
        }

        String key = buildKey(deviceId);
        Map<String, String> payload = new LinkedHashMap<>(properties.size());
        try {
            for (Map.Entry<String, Object> property : properties.entrySet()) {
                Map<String, Object> entry = new LinkedHashMap<>(2);
                entry.put("v", property.getValue());
                entry.put("ts", timestamp);
                payload.put(buildField(property.getKey()), objectMapper.writeValueAsString(entry));
            }
        } catch (JsonProcessingException e) {
            writeFailCounter.increment();
            return Mono.just(false);
        }

        return redisTemplate
            .opsForHash()
            .putAll(key, payload)
            .flatMap(success -> success
                ? redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds)).thenReturn(true)
                : Mono.just(false))
            .timeout(writeTimeout)
            .onErrorResume(e -> {
                writeFailCounter.increment();
                return Mono.just(false);
            });
    }

    public Mono<Map<String, Object>> readAllProperties(String deviceId) {
        String key = buildKey(deviceId);

        return redisTemplate
            .opsForHash()
            .entries(key)
            .collectMap(
                e -> {
                    String f = String.valueOf(e.getKey());
                    return f.startsWith(FIELD_PREFIX) ? f.substring(FIELD_PREFIX.length()) : f;
                },
                e -> extractValue(String.valueOf(e.getValue()))
            )
            .flatMap(map -> {
                if (map.isEmpty()) {
                    missCounter.increment();
                    return Mono.just(Collections.<String, Object>emptyMap());
                }
                hitCounter.increment();
                return Mono.just(map);
            })
            .onErrorResume(e -> {
                missCounter.increment();
                return Mono.just(Collections.emptyMap());
            });
    }

    public Mono<Object> readPropertyValue(String deviceId, String propertyId) {
        String key = buildKey(deviceId);
        String field = buildField(propertyId);

        return redisTemplate
            .opsForHash()
            .get(key, field)
            .cast(String.class)
            .map(json -> {
                hitCounter.increment();
                return extractValue(json);
            })
            .switchIfEmpty(Mono.fromRunnable(missCounter::increment).then(Mono.empty()))
            .onErrorResume(e -> {
                missCounter.increment();
                return Mono.empty();
            });
    }

    private Object extractValue(String json) {
        try {
            Map<String, Object> obj = objectMapper.readValue(json, MAP_TYPE_REF);
            Object v = obj.get("v");
            return v != null ? v : json;
        } catch (Exception ex) {
            return json;
        }
    }
}
