package gp.saas.legacy.service;

import com.alibaba.fastjson.JSONObject;
import gp.saas.legacy.dto.SynchronizationAlarmRecordEntity;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.gateway.annotation.Subscribe;
import org.jetlinks.community.rule.engine.entity.AlarmHistoryInfo;
import org.jetlinks.community.rule.engine.entity.AlarmRecordEntity;
import org.jetlinks.community.rule.engine.enums.AlarmRecordState;
import org.jetlinks.community.rule.engine.scene.SceneRule;
import org.jetlinks.community.rule.engine.service.AlarmRecordService;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;

@Slf4j
@Service
public class LegacyAlarmSubscribeService {

    private static final String SYSTEM_ALARM_PUSH_DEDUP_PREFIX = "gplink:saas:system-alarm:push:";
    private static final Duration SYSTEM_ALARM_PUSH_DEDUP_TTL = Duration.ofDays(7);
    private static final Duration SYSTEM_ALARM_PUSH_PENDING_TTL = Duration.ofMinutes(5);
    private static final String SYSTEM_ALARM_TRIGGER = "trigger";
    private static final String SYSTEM_ALARM_RELIEVE = "relieve";
    private static final String PUSH_STATE_PENDING = "pending:";
    private static final String PUSH_STATE_PUSHED = "pushed:";
    private static final String CURRENT_SUFFIX = "_current";

    private final LocalDeviceInstanceService deviceService;
    private final LocalDeviceProductService productService;
    private final LegacyMessagePublishService messagePublishService;
    private final AlarmRecordService alarmRecordService;
    private final ReactiveRedisOperations<String, String> redis;

    public LegacyAlarmSubscribeService(LocalDeviceInstanceService deviceService,
                                       LocalDeviceProductService productService,
                                       LegacyMessagePublishService messagePublishService,
                                       AlarmRecordService alarmRecordService,
                                       ReactiveRedisOperations<String, String> redis) {
        this.deviceService = deviceService;
        this.productService = productService;
        this.messagePublishService = messagePublishService;
        this.alarmRecordService = alarmRecordService;
        this.redis = redis;
    }

    @Subscribe(topics = {
        "/alarm/*/*/*/record"
    })
    public Mono<Void> alarmTriggerMessageHandler(AlarmHistoryInfo alarm) {
        String deviceId = alarm.getTargetId();
        if (!StringUtils.hasText(deviceId)) {
            return Mono.empty();
        }
        return resolveDeviceProduct(deviceId)
            .flatMap(context -> {
                SynchronizationAlarmRecordEntity entity = createBaseAlarm(alarm, context, true);
                return resolveAlarmRecord(alarm, true)
                    .map(record -> {
                        applyStableAlarmId(entity, alarm, record);
                        applyPropertyAndValueFromRecord(record, entity);
                        if (!StringUtils.hasText(entity.getProperty()) || entity.getPropertyValue() == null) {
                            parsePropertyAndValueFromAlarmInfo(alarm, entity);
                        }
                        return entity;
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        applyStableAlarmId(entity, alarm, null);
                        parsePropertyAndValueFromAlarmInfo(alarm, entity);
                        return Mono.just(entity);
                    }))
                    .flatMap(resolved -> publishSystemAlarmOnce(context.productId,
                                                                deviceId,
                                                                resolved,
                                                                alarm.getAlarmTime(),
                                                                resolved.getAlarmId(),
                                                                SYSTEM_ALARM_TRIGGER));
            });
    }

    @Subscribe(topics = {
        "/alarm/*/*/*/relieve"
    })
    public Mono<Void> alarmRelieveMessageHandler(AlarmHistoryInfo alarm) {
        String deviceId = alarm.getTargetId();
        if (!StringUtils.hasText(deviceId)) {
            return Mono.empty();
        }
        return resolveDeviceProduct(deviceId)
            .flatMap(context -> {
                SynchronizationAlarmRecordEntity entity = createBaseAlarm(alarm, context, false);
                parsePropertyAndValueFromAlarmInfo(alarm, entity);
                return resolveAlarmRecord(alarm, false)
                    .map(record -> {
                        applyStableAlarmId(entity, alarm, record);
                        if (!StringUtils.hasText(entity.getProperty()) || entity.getPropertyValue() == null) {
                            applyPropertyAndValueFromRecord(record, entity);
                        }
                        return entity;
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        applyStableAlarmId(entity, alarm, null);
                        return Mono.just(entity);
                    }))
                    .flatMap(resolved -> publishSystemAlarmOnce(context.productId,
                                                                deviceId,
                                                                resolved,
                                                                alarm.getAlarmTime(),
                                                                resolved.getAlarmId(),
                                                                SYSTEM_ALARM_RELIEVE));
            });
    }

    private Mono<DeviceProductContext> resolveDeviceProduct(String deviceId) {
        return deviceService
            .findById(deviceId)
            .flatMap(device -> productService
                .findById(device.getProductId())
                .map(product -> new DeviceProductContext(device, product)));
    }

    private SynchronizationAlarmRecordEntity createBaseAlarm(AlarmHistoryInfo alarm,
                                                            DeviceProductContext context,
                                                            boolean trigger) {
        SynchronizationAlarmRecordEntity entity = new SynchronizationAlarmRecordEntity();
        entity.setSourceId(alarm.getSourceId());
        entity.setSourceName(alarm.getSourceName());
        entity.setSourceType(resolveSystemAlarmSourceType(alarm));
        entity.setLevel(alarm.getLevel());
        entity.setFirstAlarmTime(trigger ? alarm.getAlarmTime() : null);
        entity.setIsConfirmed(false);
        entity.setIsAlarming(trigger);
        entity.setIsDanger(false);
        entity.setAlarmName(alarm.getAlarmConfigName());
        entity.setAlarmConfigId(alarm.getAlarmConfigId());
        entity.setDeviceId(context.device.getId());
        entity.setDeviceName(firstText(alarm.getTargetName(), context.device.getName()));
        entity.setDescription(alarm.getDescription());
        entity.setDangerType("1");
        return entity;
    }

    private Mono<AlarmRecordEntity> resolveAlarmRecord(AlarmHistoryInfo alarm, boolean trigger) {
        if (StringUtils.hasText(alarm.getAlarmRecordId())) {
            return alarmRecordService.findById(alarm.getAlarmRecordId());
        }
        var query = alarmRecordService
            .createQuery()
            .where(AlarmRecordEntity::getAlarmConfigId, alarm.getAlarmConfigId())
            .and(AlarmRecordEntity::getSourceId, alarm.getSourceId())
            .and(AlarmRecordEntity::getSourceType, alarm.getSourceType());

        if (trigger) {
            query.and(AlarmRecordEntity::getState, AlarmRecordState.warning);
        }
        return query.fetchOne();
    }

    private void applyStableAlarmId(SynchronizationAlarmRecordEntity entity,
                                    AlarmHistoryInfo alarm,
                                    AlarmRecordEntity record) {
        String alarmId = getStableAlarmId(alarm, record);
        entity.setAlarmId(alarmId);
        entity.setLocalAlarmId(alarmId);
    }

    private String getStableAlarmId(AlarmHistoryInfo alarm, AlarmRecordEntity record) {
        String sourceId = firstText(alarm.getSourceId(), record == null ? null : record.getSourceId(), alarm.getTargetId());
        String sourceType = firstText(alarm.getSourceType(), record == null ? null : record.getSourceType(), alarm.getTargetType());
        String sceneId = resolveSceneId(alarm);
        if (!StringUtils.hasText(sourceId)
            || !StringUtils.hasText(sourceType)
            || !StringUtils.hasText(alarm.getAlarmConfigId())
            || !StringUtils.hasText(sceneId)) {
            log.warn("skip system alarm mqtt publish because stable identity is incomplete, sourceId={}, sourceType={}, alarmConfigId={}, sceneId={}, targetId={}",
                     sourceId, sourceType, alarm.getAlarmConfigId(), sceneId, alarm.getTargetId());
            return null;
        }
        return AlarmRecordEntity.generateId(safe(sourceId),
                                            safe(sourceType),
                                            safe(alarm.getAlarmConfigId()),
                                            sceneId);
    }

    private String resolveSceneId(AlarmHistoryInfo alarm) {
        JSONObject alarmInfo = parseAlarmInfo(alarm);
        if (alarmInfo == null) {
            return null;
        }
        String sceneId = firstText(alarmInfo.getString("ruleId"),
                                   alarmInfo.getString("sceneId"));
        if (StringUtils.hasText(sceneId)) {
            return sceneId;
        }
        JSONObject output = alarmInfo.getJSONObject("output");
        if (output == null) {
            return null;
        }
        JSONObject scene = output.getJSONObject(SceneRule.CONTEXT_KEY_SCENE_OUTPUT);
        if (scene == null) {
            return firstText(output.getString("ruleId"), output.getString("sceneId"));
        }
        return firstText(scene.getString("ruleId"),
                         scene.getString("sceneId"),
                         scene.getString("id"));
    }

    private void parsePropertyAndValueFromAlarmInfo(AlarmHistoryInfo alarm,
                                                    SynchronizationAlarmRecordEntity entity) {
        JSONObject alarmInfo = parseAlarmInfo(alarm);
        if (alarmInfo == null) {
            return;
        }
        boolean parsed = parsePropertyAndValue(alarmInfo, entity);
        if (parsed) {
            return;
        }
        JSONObject output = alarmInfo.getJSONObject("output");
        if (output != null && parsePropertyAndValue(output, entity)) {
            return;
        }
        if (output != null) {
            JSONObject scene = output.getJSONObject(SceneRule.CONTEXT_KEY_SCENE_OUTPUT);
            if (scene != null) {
                parsePropertyAndValue(scene, entity);
            }
        }
    }

    private JSONObject parseAlarmInfo(AlarmHistoryInfo alarm) {
        if (!StringUtils.hasText(alarm.getAlarmInfo())) {
            return null;
        }
        try {
            return JSONObject.parseObject(alarm.getAlarmInfo());
        } catch (Exception error) {
            log.debug("parse alarmInfo failed, alarmConfigId={}, targetId={}",
                      alarm.getAlarmConfigId(), alarm.getTargetId(), error);
            return null;
        }
    }

    private boolean parsePropertyAndValue(JSONObject source,
                                          SynchronizationAlarmRecordEntity entity) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.endsWith(CURRENT_SUFFIX)) {
                entity.setProperty(key.substring(0, key.length() - CURRENT_SUFFIX.length()));
                entity.setPropertyValue(entry.getValue() == null ? null : String.valueOf(entry.getValue()));
                return true;
            }
            Object value = entry.getValue();
            if (value instanceof JSONObject && parsePropertyAndValue((JSONObject) value, entity)) {
                return true;
            }
        }
        return false;
    }

    private void applyPropertyAndValueFromRecord(AlarmRecordEntity record,
                                                 SynchronizationAlarmRecordEntity entity) {
        String property = invokeStringGetter(record, "getProperty");
        String value = invokeStringGetter(record, "getValue");
        if (StringUtils.hasText(property) && value != null) {
            entity.setProperty(property);
            entity.setPropertyValue(value);
        }
    }

    private String invokeStringGetter(AlarmRecordEntity record, String methodName) {
        try {
            Method method = record.getClass().getMethod(methodName);
            Object value = method.invoke(record);
            return value == null ? null : String.valueOf(value);
        } catch (ReflectiveOperationException ignore) {
            return null;
        }
    }

    private Mono<Void> publishSystemAlarmOnce(String productId,
                                              String deviceId,
                                              SynchronizationAlarmRecordEntity entity,
                                              Long timestamp,
                                              String alarmId,
                                              String type) {
        if (!StringUtils.hasText(alarmId)) {
            return Mono.empty();
        }
        String dedupKey = SYSTEM_ALARM_PUSH_DEDUP_PREFIX + type + ":" + alarmId;
        String oppositeKey = SYSTEM_ALARM_PUSH_DEDUP_PREFIX +
            (SYSTEM_ALARM_TRIGGER.equals(type) ? SYSTEM_ALARM_RELIEVE : SYSTEM_ALARM_TRIGGER) +
            ":" + alarmId;

        long now = System.currentTimeMillis();
        long messageTime = timestamp == null ? now : timestamp;
        Mono<Boolean> acquire = SYSTEM_ALARM_RELIEVE.equals(type)
            ? acquireRelievePublish(dedupKey, oppositeKey, now)
            : acquireTriggerPublish(dedupKey, oppositeKey, now);

        return acquire
            .flatMap(acquired -> {
                if (!Boolean.TRUE.equals(acquired)) {
                    return Mono.empty();
                }
                return publishAndMark(dedupKey, oppositeKey, productId, deviceId, entity, messageTime);
            });
    }

    private Mono<Boolean> acquireTriggerPublish(String triggerKey, String relieveKey, long stateTime) {
        return Mono
            .zip(redis.opsForValue().get(triggerKey).defaultIfEmpty(""),
                 redis.opsForValue().get(relieveKey).defaultIfEmpty(""))
            .flatMap(state -> {
                String triggerState = state.getT1();
                String relieveState = state.getT2();

                if (StringUtils.hasText(triggerState)
                    && (!isPushed(relieveState) || stateTime(triggerState) >= stateTime(relieveState))) {
                    return Mono.just(false);
                }

                Mono<Long> cleanup = isPushed(relieveState)
                    ? redis.delete(triggerKey)
                    : Mono.just(0L);

                return cleanup.then(redis.opsForValue()
                                         .setIfAbsent(triggerKey,
                                                      pendingValue(stateTime),
                                                      SYSTEM_ALARM_PUSH_PENDING_TTL));
            });
    }

    private Mono<Boolean> acquireRelievePublish(String relieveKey, String triggerKey, long stateTime) {
        return Mono
            .zip(redis.opsForValue().get(triggerKey).defaultIfEmpty(""),
                 redis.opsForValue().get(relieveKey).defaultIfEmpty(""))
            .flatMap(state -> {
                String triggerState = state.getT1();
                String relieveState = state.getT2();
                if (!isPushed(triggerState)) {
                    return Mono.just(false);
                }
                if (StringUtils.hasText(relieveState)
                    && (!isPushed(relieveState) || stateTime(relieveState) >= stateTime(triggerState))) {
                    return Mono.just(false);
                }

                Mono<Long> cleanup = isPushed(relieveState)
                    ? redis.delete(relieveKey)
                    : Mono.just(0L);

                return cleanup.then(redis.opsForValue()
                                         .setIfAbsent(relieveKey,
                                                      pendingValue(stateTime),
                                                      SYSTEM_ALARM_PUSH_PENDING_TTL));
            });
    }

    private Mono<Void> publishAndMark(String dedupKey,
                                      String oppositeKey,
                                      String productId,
                                      String deviceId,
                                      SynchronizationAlarmRecordEntity entity,
                                      long messageTime) {
        long stateTime = System.currentTimeMillis();
        return messagePublishService
            .standardMqttAlarmPublisher(productId, deviceId, entity, messageTime)
            .onErrorResume(error -> redis.delete(dedupKey).then(Mono.error(error)))
            .flatMap(published -> {
                if (!Boolean.TRUE.equals(published)) {
                    return redis.delete(dedupKey).then();
                }
                return redis.opsForValue()
                    .set(dedupKey, pushedValue(stateTime), SYSTEM_ALARM_PUSH_DEDUP_TTL)
                    .onErrorResume(error -> {
                        log.error("system alarm mqtt published but redis pushed state mark failed, key={}",
                                  dedupKey, error);
                        return Mono.empty();
                    })
                    .then(deleteOppositeKey(oppositeKey));
            });
    }

    private Mono<Void> deleteOppositeKey(String oppositeKey) {
        return redis
            .delete(oppositeKey)
            .onErrorResume(error -> {
                log.warn("delete opposite system alarm push key failed, key={}", oppositeKey, error);
                return Mono.just(0L);
            })
            .then();
    }

    private static String pendingValue(long stateTime) {
        return PUSH_STATE_PENDING + stateTime;
    }

    private static String pushedValue(long stateTime) {
        return PUSH_STATE_PUSHED + stateTime;
    }

    private static boolean isPushed(String value) {
        return value != null && value.startsWith(PUSH_STATE_PUSHED);
    }

    private static long stateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return -1;
        }
        int index = value.indexOf(':');
        if (index < 0 || index == value.length() - 1) {
            return -1;
        }
        try {
            return Long.parseLong(value.substring(index + 1));
        } catch (NumberFormatException ignore) {
            return -1;
        }
    }

    private static String resolveSystemAlarmSourceType(AlarmHistoryInfo alarm) {
        String alarmConfigName = alarm.getAlarmConfigName();
        String sceneName = alarm.getSourceName();
        if (!StringUtils.hasText(sceneName)) {
            return alarmConfigName;
        }
        if (!StringUtils.hasText(alarmConfigName)) {
            return sceneName;
        }
        return alarmConfigName + "(" + sceneName + ")";
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static class DeviceProductContext {
        private final DeviceInstanceEntity device;
        private final String productId;

        private DeviceProductContext(DeviceInstanceEntity device, DeviceProductEntity product) {
            this.device = device;
            this.productId = product.getId();
        }
    }
}
