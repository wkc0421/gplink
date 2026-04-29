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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis 设备属性最新值缓存服务。
 * <p>
 * Key 结构：{@code device:{deviceId}:latest}（Hash）<br>
 * Field 结构：{@code property:{propertyId}} = JSON {@code {"v":<typed-value>,"ts":<timestamp>}}
 * <p>
 * 存储格式使用 JSON，保留原始类型（数值、布尔、对象），同时避免分隔符冲突。<br>
 * 写入使用 Lua + cjson 脚本保证原子性，防止乱序消息覆盖较新的值。
 *
 * @see RedisDeviceLatestDataService
 */
@Slf4j
public class RedisDeviceLatestService {

    /** Hash Key 前缀 */
    private static final String KEY_PREFIX = "device:";
    /** Hash Key 后缀 */
    private static final String KEY_SUFFIX = ":latest";
    /** Hash Field 前缀 */
    static final String FIELD_PREFIX = "property:";

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    /**
     * Lua 脚本：原子比较时间戳并写入。
     * KEYS[1] = device:{deviceId}:latest
     * ARGV[1] = property:{propertyId}
     * ARGV[2] = JSON 字符串 {"v":<value>,"ts":<timestamp>}
     * ARGV[3] = timestamp（毫秒，用于比较）
     * ARGV[4] = TTL（秒）
     * 返回 1=写入成功，0=时间戳过旧被拦截
     */
    private static final DefaultRedisScript<Long> WRITE_SCRIPT;

    static {
        WRITE_SCRIPT = new DefaultRedisScript<>();
        WRITE_SCRIPT.setScriptText(
            "local cur = redis.call('HGET', KEYS[1], ARGV[1])\n" +
            "if cur then\n" +
            "    local ok, obj = pcall(cjson.decode, cur)\n" +
            "    if ok and obj and obj.ts and tonumber(ARGV[3]) <= tonumber(obj.ts) then\n" +
            "        return 0\n" +
            "    end\n" +
            "end\n" +
            "redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])\n" +
            "redis.call('EXPIRE', KEYS[1], ARGV[4])\n" +
            "return 1"
        );
        WRITE_SCRIPT.setResultType(Long.class);
    }

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long ttlSeconds;

    // --- Micrometer 指标 ---
    private final Counter hitCounter;
    private final Counter missCounter;
    private final Counter writeFailCounter;
    private final Counter staleBlockedCounter;

    public RedisDeviceLatestService(ReactiveStringRedisTemplate redisTemplate,
                                    ObjectMapper objectMapper,
                                    long ttlSeconds,
                                    MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttlSeconds = ttlSeconds;
        log.warn("Redis latest service initialized, keyPattern=device:{deviceId}:latest, ttlSeconds={}", ttlSeconds);
        this.hitCounter = Counter.builder("redis.latest.hit")
                                 .description("Redis latest 命中次数")
                                 .register(meterRegistry);
        this.missCounter = Counter.builder("redis.latest.miss")
                                  .description("Redis latest miss 次数")
                                  .register(meterRegistry);
        this.writeFailCounter = Counter.builder("redis.latest.write.fail")
                                       .description("Redis latest 写入失败次数")
                                       .register(meterRegistry);
        this.staleBlockedCounter = Counter.builder("redis.latest.stale.blocked")
                                          .description("Redis latest 乱序覆盖拦截次数")
                                          .register(meterRegistry);
    }

    String buildKey(String deviceId) {
        return KEY_PREFIX + deviceId + KEY_SUFFIX;
    }

    String buildField(String propertyId) {
        return FIELD_PREFIX + propertyId;
    }

    /**
     * 原子写入单个属性最新值（带时间戳防乱序）。
     * 写失败不抛出异常，返回 false。
     *
     * @return Mono&lt;Boolean&gt; true=成功写入，false=乱序被拦截或写入异常
     */
    public Mono<Boolean> writeProperty(String deviceId, String propertyId, Object value, long timestamp) {
        String key = buildKey(deviceId);
        String field = buildField(propertyId);
        String tsStr = String.valueOf(timestamp);
        String ttlStr = String.valueOf(ttlSeconds);
        log.warn("Redis latest writeProperty start: key={}, field={}, timestamp={}, value={}",
                 key, field, timestamp, value);

        String json;
        try {
            Map<String, Object> entry = new LinkedHashMap<>(2);
            entry.put("v", value);
            entry.put("ts", timestamp);
            json = objectMapper.writeValueAsString(entry);
            log.warn("Redis latest serialized payload: key={}, field={}, json={}", key, field, json);
        } catch (JsonProcessingException e) {
            writeFailCounter.increment();
            log.warn("Redis latest serialize failed: device={}, property={}", deviceId, propertyId, e);
            return Mono.just(false);
        }

        return redisTemplate
            .execute(WRITE_SCRIPT,
                     Collections.singletonList(key),
                     field, json, tsStr, ttlStr)
            .next()
            .map(result -> {
                log.warn("Redis latest lua result: key={}, field={}, result={}", key, field, result);
                if (result == 0L) {
                    staleBlockedCounter.increment();
                    log.warn("Redis latest write blocked by stale timestamp: key={}, field={}, timestamp={}",
                             key, field, timestamp);
                    return false;
                }
                log.warn("Redis latest write success: key={}, field={}", key, field);
                return true;
            })
            .switchIfEmpty(Mono.fromSupplier(() -> {
                log.warn("Redis latest lua returned empty result: key={}, field={}", key, field);
                return true;
            }))
            .onErrorResume(e -> {
                writeFailCounter.increment();
                log.warn("Redis latest write failed: device={}, property={}", deviceId, propertyId, e);
                return Mono.just(false);
            });
    }

    /**
     * 读取设备全部属性最新值，返回类型与写入时一致（数值/布尔/对象均保留原始类型）。
     * key 不存在（miss）时返回空 Map，不抛出异常。
     *
     * @return Mono&lt;Map&lt;propertyId, value&gt;&gt;，miss 时返回空 Map
     */
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
                log.warn("Redis latest readAll failed: device={}", deviceId, e);
                missCounter.increment();
                return Mono.just(Collections.emptyMap());
            });
    }

    /**
     * 读取单个属性最新值，类型与写入时一致。
     *
     * @return Mono&lt;Object&gt;，miss 时返回 empty
     */
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
                log.warn("Redis latest read failed: device={}, property={}", deviceId, propertyId, e);
                missCounter.increment();
                return Mono.empty();
            });
    }

    /**
     * 从 JSON {@code {"v":<value>,"ts":<ts>}} 中提取 value，保留原始类型。
     * 若反序列化失败，原样返回字符串。
     */
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
