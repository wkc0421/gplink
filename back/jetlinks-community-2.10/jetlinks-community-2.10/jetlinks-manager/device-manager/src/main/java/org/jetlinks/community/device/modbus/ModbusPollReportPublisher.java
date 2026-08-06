package org.jetlinks.community.device.modbus;

import org.jetlinks.community.device.message.DeviceMessageConnector;
import org.jetlinks.core.message.property.ReportPropertyMessage;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

@Component
public class ModbusPollReportPublisher {

    static final String DEDUP_PREFIX = "gplink:modbus:poll:report:";
    private static final Duration PUBLISHING_TTL = Duration.ofMinutes(2);
    private static final long PUBLISHED_TTL_MS = Duration.ofHours(24).toMillis();

    private static final RedisScript<Long> MARK_PUBLISHED_SCRIPT = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "redis.call('psetex', KEYS[1], ARGV[2], 'published'); return 1 else return 0 end",
            Long.class);

    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final ReactiveRedisOperations<String, String> redis;
    private final DeviceMessageConnector connector;

    public ModbusPollReportPublisher(ReactiveRedisOperations<String, String> redis,
                                     DeviceMessageConnector connector) {
        this.redis = redis;
        this.connector = connector;
    }

    public Mono<Boolean> publish(PollCycleResult result, BooleanSupplier leaseValid) {
        if (result.properties.isEmpty() || !leaseValid.getAsBoolean()) {
            return Mono.just(false);
        }
        String key = DEDUP_PREFIX + result.messageId;
        String owner = UUID.randomUUID().toString();
        return redis
                .opsForValue()
                .setIfAbsent(key, owner, PUBLISHING_TTL)
                .filter(Boolean.TRUE::equals)
                .flatMap(ignore -> {
                    if (!leaseValid.getAsBoolean()) {
                        return release(key, owner).thenReturn(false);
                    }
                    ReportPropertyMessage report = createReport(result);
                    return connector
                            .onMessage(report)
                            .then(markPublished(key, owner))
                            .flatMap(marked -> marked
                                    ? Mono.just(true)
                                    : Mono.error(new IllegalStateException(
                                            "lost report publishing ownership: " + result.messageId)))
                            .onErrorResume(error -> release(key, owner)
                                    .then(Mono.error(error)));
                })
                .defaultIfEmpty(false);
    }

    ReportPropertyMessage createReport(PollCycleResult result) {
        ReportPropertyMessage report = new ReportPropertyMessage();
        report.setDeviceId(result.deviceId);
        report.setMessageId(result.messageId);
        report.setTimestamp(result.lastSuccessTime);
        report.setProperties(new LinkedHashMap<>(result.properties));
        report.setPropertySourceTimes(new LinkedHashMap<>(result.propertySourceTimes));
        report.addHeader("pollSource", "MODBUS");
        report.addHeader("gatewayId", result.gatewayId);
        report.addHeader("pollCycleId", result.cycleId);
        report.addHeader("pollPlanId", result.planId);
        report.addHeader("pollResult", result.failedCount == 0 ? "success" : "partial");
        report.addHeader("pollCycleStartTime", result.startedAt);
        report.addHeader("pollCycleCompleteTime", result.completedAt);
        report.addHeader("pollSuccessCount", result.properties.size());
        report.addHeader("pollFailedCount", result.failedCount);
        return report;
    }

    private Mono<Boolean> markPublished(String key, String owner) {
        return redis
                .execute(
                        MARK_PUBLISHED_SCRIPT,
                        Collections.singletonList(key),
                        owner,
                        String.valueOf(PUBLISHED_TTL_MS))
                .next()
                .map(result -> result != null && result == 1L)
                .defaultIfEmpty(false);
    }

    private Mono<Void> release(String key, String owner) {
        return redis
                .execute(RELEASE_SCRIPT, Collections.singletonList(key), owner)
                .then();
    }

    public static final class PollCycleResult {
        private final String gatewayId;
        private final String deviceId;
        private final String planId;
        private final String cycleId;
        private final String messageId;
        private final long startedAt;
        private final long completedAt;
        private final long lastSuccessTime;
        private final int failedCount;
        private final Map<String, Object> properties;
        private final Map<String, Long> propertySourceTimes;

        public PollCycleResult(String gatewayId,
                               String deviceId,
                               String planId,
                               String cycleId,
                               String messageId,
                               long startedAt,
                               long completedAt,
                               long lastSuccessTime,
                               int failedCount,
                               Map<String, Object> properties,
                               Map<String, Long> propertySourceTimes) {
            this.gatewayId = gatewayId;
            this.deviceId = deviceId;
            this.planId = planId;
            this.cycleId = cycleId;
            this.messageId = messageId;
            this.startedAt = startedAt;
            this.completedAt = completedAt;
            this.lastSuccessTime = lastSuccessTime;
            this.failedCount = failedCount;
            this.properties = properties;
            this.propertySourceTimes = propertySourceTimes;
        }
    }
}
