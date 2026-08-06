package org.jetlinks.community.device.modbus;

import lombok.Getter;
import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
final class ModbusPollPlan {

    enum ScheduleType {
        FIXED_DELAY,
        CRON
    }

    private final String id;
    private final ScheduleType scheduleType;
    private final long intervalMs;
    private final String cron;
    private final long deviceIntervalMs;
    private final long frameIntervalMs;
    private final int retryCount;
    private final List<String> propertyIds;
    private final List<String> deviceIds;

    private volatile long nextFireTime;
    private volatile boolean pendingCronFire;

    ModbusPollPlan(String id,
                   ScheduleType scheduleType,
                   long intervalMs,
                   String cron,
                   long deviceIntervalMs,
                   long frameIntervalMs,
                   int retryCount,
                   List<String> propertyIds,
                   List<String> deviceIds) {
        this.id = id;
        this.scheduleType = scheduleType;
        this.intervalMs = Math.max(1000L, intervalMs);
        this.cron = cron;
        this.deviceIntervalMs = Math.max(0L, deviceIntervalMs);
        this.frameIntervalMs = Math.max(0L, frameIntervalMs);
        this.retryCount = Math.max(0, retryCount);
        this.propertyIds = Collections.unmodifiableList(new ArrayList<>(propertyIds));
        this.deviceIds = Collections.unmodifiableList(new ArrayList<>(deviceIds));
        this.nextFireTime = computeNext(System.currentTimeMillis());
    }

    boolean isDue(long now) {
        return now >= nextFireTime;
    }

    long claimFire(long now) {
        long planned = nextFireTime;
        if (scheduleType == ScheduleType.CRON) {
            nextFireTime = computeNext(Math.max(now, nextFireTime));
        } else {
            nextFireTime = Long.MAX_VALUE;
        }
        return planned;
    }

    void complete(long completedAt) {
        if (scheduleType == ScheduleType.FIXED_DELAY) {
            nextFireTime = completedAt + intervalMs;
            return;
        }
        if (pendingCronFire) {
            pendingCronFire = false;
            nextFireTime = completedAt;
        }
    }

    void mergeCronTrigger(long now) {
        if (scheduleType == ScheduleType.CRON && now >= nextFireTime) {
            pendingCronFire = true;
            nextFireTime = computeNext(now);
        }
    }

    private long computeNext(long after) {
        if (scheduleType != ScheduleType.CRON || cron == null || cron.trim().isEmpty()) {
            return after + intervalMs;
        }
        ZonedDateTime next = CronExpression
                .parse(cron)
                .next(ZonedDateTime.ofInstant(Instant.ofEpochMilli(after), ZoneId.systemDefault()));
        return next == null ? Long.MAX_VALUE : next.toInstant().toEpochMilli();
    }
}
