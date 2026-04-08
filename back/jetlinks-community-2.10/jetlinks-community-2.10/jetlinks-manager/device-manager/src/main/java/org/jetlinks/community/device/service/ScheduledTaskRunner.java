package org.jetlinks.community.device.service;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.crud.events.EntityDeletedEvent;
import org.hswebframework.web.crud.events.EntityModifyEvent;
import org.hswebframework.web.crud.events.EntitySavedEvent;
import org.jetlinks.community.device.entity.ScheduledTaskEntity;
import org.jetlinks.community.device.entity.ScheduledTaskLogEntity;
import org.jetlinks.community.device.enums.ExecutionMode;
import org.jetlinks.community.device.enums.LogStatus;
import org.jetlinks.community.device.enums.ScheduledTaskState;
import org.jetlinks.community.device.enums.ScheduledTaskType;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ScheduledTaskRunner implements CommandLineRunner {

    private final ScheduledTaskService taskService;
    private final ScheduledTaskLogService logService;
    private final LocalDeviceInstanceService deviceService;

    private final Map<String, Disposable> runningTasks = new ConcurrentHashMap<>();

    public ScheduledTaskRunner(ScheduledTaskService taskService,
                               ScheduledTaskLogService logService,
                               LocalDeviceInstanceService deviceService) {
        this.taskService = taskService;
        this.logService = logService;
        this.deviceService = deviceService;
    }

    @Override
    public void run(String... args) {
        taskService
            .createQuery()
            .where(ScheduledTaskEntity::getState, ScheduledTaskState.enabled)
            .fetch()
            .flatMap(task -> startTask(task)
                .onErrorResume(err -> {
                    log.error("Failed to start scheduled task [{}]", task.getId(), err);
                    return Mono.empty();
                }))
            .subscribe();
    }

    @EventListener
    public void onSaved(EntitySavedEvent<ScheduledTaskEntity> event) {
        event.async(
            Flux.fromIterable(event.getEntity())
                .flatMap(this::handleStateChange)
        );
    }

    @EventListener
    public void onModified(EntityModifyEvent<ScheduledTaskEntity> event) {
        event.async(
            Flux.fromIterable(event.getAfter())
                .flatMap(this::handleStateChange)
        );
    }

    @EventListener
    public void onDeleted(EntityDeletedEvent<ScheduledTaskEntity> event) {
        event.async(
            Flux.fromIterable(event.getEntity())
                .doOnNext(task -> stopTask(task.getId()))
                .then()
        );
    }

    private Mono<Void> handleStateChange(ScheduledTaskEntity task) {
        stopTask(task.getId());
        if (task.getState() == ScheduledTaskState.enabled) {
            return startTask(task);
        }
        return Mono.empty();
    }

    private Mono<Void> startTask(ScheduledTaskEntity task) {
        if (task.getTimerSpec() == null) {
            log.warn("Scheduled task [{}] has no timerSpec, skipping", task.getId());
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> {
            Disposable d = task
                .getTimerSpec()
                .flux(Schedulers.parallel())
                .onBackpressureDrop()
                .concatMap(tick -> executeTask(task)
                    .onErrorResume(err -> {
                        log.warn("Task [{}] tick {} error", task.getId(), tick, err);
                        return Mono.empty();
                    }))
                .subscribe(
                    v -> {},
                    err -> log.error("Task [{}] fatal error, task stopped", task.getId(), err)
                );
            runningTasks.put(task.getId(), d);
            log.info("Scheduled task [{}] ({}) started", task.getId(), task.getName());
        });
    }

    private void stopTask(String taskId) {
        Disposable d = runningTasks.remove(taskId);
        if (d != null && !d.isDisposed()) {
            d.dispose();
            log.info("Scheduled task [{}] stopped", taskId);
        }
    }

    private Mono<Void> executeTask(ScheduledTaskEntity task) {
        return resolveDeviceIds(task)
            .collectList()
            .flatMap(deviceIds -> {
                if (deviceIds.isEmpty()) {
                    return Mono.<Void>empty();
                }
                ExecutionMode mode = task.getExecutionMode() != null
                    ? task.getExecutionMode() : ExecutionMode.PARALLEL;

                if (mode == ExecutionMode.PARALLEL) {
                    return Flux.fromIterable(deviceIds)
                        .flatMap(deviceId -> executeForDevice(task, deviceId))
                        .then();
                } else {
                    long interval = task.getSerialIntervalMs() != null ? task.getSerialIntervalMs() : 0L;
                    return Flux.fromIterable(deviceIds)
                        .concatMap(deviceId -> {
                            Mono<Void> exec = executeForDevice(task, deviceId);
                            if (interval > 0) {
                                return exec.then(Mono.delay(Duration.ofMillis(interval))).then();
                            }
                            return exec;
                        })
                        .then();
                }
            });
    }

    private Flux<String> resolveDeviceIds(ScheduledTaskEntity task) {
        List<String> ids = task.getDeviceIds();
        if (ids != null && !ids.isEmpty()) {
            return Flux.fromIterable(ids);
        }
        return deviceService
            .createQuery()
            .where(org.jetlinks.community.device.entity.DeviceInstanceEntity::getProductId, task.getProductId())
            .fetch()
            .map(org.jetlinks.community.device.entity.DeviceInstanceEntity::getId);
    }

    private Mono<Void> executeForDevice(ScheduledTaskEntity task, String deviceId) {
        if (task.getTaskType() == ScheduledTaskType.READ_PROPERTY) {
            return executeReadProperty(task, deviceId);
        } else {
            return executeInvokeFunction(task, deviceId);
        }
    }

    private Mono<Void> executeReadProperty(ScheduledTaskEntity task, String deviceId) {
        List<String> props = task.getProperties();
        if (props == null || props.isEmpty()) {
            return Mono.empty();
        }
        long delay = task.getPropertyIntervalMs() != null ? task.getPropertyIntervalMs() : 500L;
        return Flux.fromIterable(props)
            .concatMap(prop -> {
                Mono<Void> readOp = deviceService.readProperty(deviceId, prop).then();
                if (delay > 0) {
                    readOp = readOp.then(Mono.delay(Duration.ofMillis(delay))).then();
                }
                return readOp.onErrorResume(err -> {
                    writeLog(task, deviceId, LogStatus.FAILED, err.getMessage());
                    return Mono.empty();
                });
            })
            .then();
    }

    private Mono<Void> executeInvokeFunction(ScheduledTaskEntity task, String deviceId) {
        Map<String, Object> params = task.getFunctionParams() != null
            ? task.getFunctionParams() : Collections.emptyMap();
        return deviceService
            .invokeFunction(deviceId, task.getFunctionId(), params)
            .then()
            .onErrorResume(err -> {
                writeLog(task, deviceId, LogStatus.FAILED, err.getMessage());
                return Mono.empty();
            });
    }

    private void writeLog(ScheduledTaskEntity task, String deviceId, LogStatus status, Object detail) {
        if (!Boolean.TRUE.equals(task.getLogEnabled())) {
            return;
        }
        ScheduledTaskLogEntity logEntity = new ScheduledTaskLogEntity();
        logEntity.setTaskId(task.getId());
        logEntity.setTaskName(task.getName());
        logEntity.setDeviceId(deviceId);
        logEntity.setTaskType(task.getTaskType());
        logEntity.setStatus(status);
        logEntity.setDetail(detail);
        logService.save(logEntity)
            .subscribe(
                v -> {},
                err -> log.warn("Failed to write log for task [{}]", task.getId(), err)
            );
    }
}
