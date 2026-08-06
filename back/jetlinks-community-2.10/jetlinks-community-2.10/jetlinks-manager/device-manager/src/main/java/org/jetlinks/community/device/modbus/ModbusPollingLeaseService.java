package org.jetlinks.community.device.modbus;

import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;

@Component
public class ModbusPollingLeaseService {

    static final String LEASE_PREFIX = "gplink:modbus:poll:lease:";

    private static final RedisScript<Long> RENEW_SCRIPT = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final ReactiveRedisOperations<String, String> redis;

    public ModbusPollingLeaseService(ReactiveRedisOperations<String, String> redis) {
        this.redis = redis;
    }

    public Mono<Boolean> acquire(String gatewayId, String ownerValue, Duration ttl) {
        return redis
                .opsForValue()
                .setIfAbsent(key(gatewayId), ownerValue, ttl)
                .defaultIfEmpty(false);
    }

    public Mono<Boolean> renew(String gatewayId, String ownerValue, Duration ttl) {
        return redis
                .execute(
                        RENEW_SCRIPT,
                        Collections.singletonList(key(gatewayId)),
                        ownerValue,
                        String.valueOf(ttl.toMillis()))
                .next()
                .map(result -> result != null && result == 1L)
                .defaultIfEmpty(false);
    }

    public Mono<Boolean> release(String gatewayId, String ownerValue) {
        return redis
                .execute(
                        RELEASE_SCRIPT,
                        Collections.singletonList(key(gatewayId)),
                        ownerValue)
                .next()
                .map(result -> result != null && result == 1L)
                .defaultIfEmpty(false);
    }

    static String key(String gatewayId) {
        return LEASE_PREFIX + gatewayId;
    }
}
