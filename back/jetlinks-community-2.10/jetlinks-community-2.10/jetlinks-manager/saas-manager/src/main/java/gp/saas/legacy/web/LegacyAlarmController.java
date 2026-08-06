package gp.saas.legacy.web;

import com.alibaba.fastjson.JSONObject;
import gp.saas.legacy.dto.DiYaAlarmRecordEntity;
import gp.saas.legacy.dto.SynchronizationAlarmRecordEntity;
import gp.saas.legacy.service.LegacyMessagePublishService;
import gp.saas.legacy.util.RuleSceneUtil;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.bean.FastBeanCopier;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.community.rule.engine.entity.AlarmConfigEntity;
import org.jetlinks.community.rule.engine.entity.AlarmRuleBindEntity;
import org.jetlinks.community.rule.engine.entity.SceneEntity;
import org.jetlinks.community.rule.engine.service.AlarmConfigService;
import org.jetlinks.community.rule.engine.service.AlarmRecordService;
import org.jetlinks.community.rule.engine.service.AlarmRuleBindService;
import org.jetlinks.community.rule.engine.service.SceneService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({
    "/v1/alarm",
    "/api/v1/alarm"
})
@Authorize
public class LegacyAlarmController {

    private final AlarmRecordService alarmRecordService;
    private final AlarmConfigService alarmConfigService;
    private final AlarmRuleBindService alarmRuleBindService;
    private final SceneService sceneService;
    private final LegacyMessagePublishService messagePublishService;

    public LegacyAlarmController(AlarmRecordService alarmRecordService,
                                 AlarmConfigService alarmConfigService,
                                 AlarmRuleBindService alarmRuleBindService,
                                 SceneService sceneService,
                                 LegacyMessagePublishService messagePublishService) {
        this.alarmRecordService = alarmRecordService;
        this.alarmConfigService = alarmConfigService;
        this.alarmRuleBindService = alarmRuleBindService;
        this.sceneService = sceneService;
        this.messagePublishService = messagePublishService;
    }

    @GetMapping("/{id}/mqtt")
    public Mono<Object> sendMqttAlarmMessage(@PathVariable String id) {
        return alarmRecordService.findById(id).cast(Object.class);
    }

    @PostMapping("/mqtt/synchronize")
    public Mono<Void> synchronizeMqttAlarm(@RequestBody SynchronizationAlarmRecordEntity request) {
        String deviceId = request.getDeviceId();
        String managerId = deviceId == null ? "" : deviceId.split("_")[0];
        String topic = "IOT/" + managerId + "/Ctrl";

        DiYaAlarmRecordEntity alarm = toDiYaAlarm(request);
        JSONObject keyValue = new JSONObject();
        keyValue.put("Key", "Alarm");
        keyValue.put("Value", alarm);

        Map<String, Object> payload = new HashMap<>();
        payload.put("MsgType", "MQData");
        payload.put("Style", "Report");
        payload.put("Sender", "GPLink");
        payload.put("Time", System.currentTimeMillis());
        payload.put("DataObject", List.of(keyValue));
        return messagePublishService.mqttPublisher(payload, topic);
    }

    @PostMapping("/config")
    public Mono<AlarmConfigEntity> createAlarmConfig(@RequestBody AlarmConfigEntity entity) {
        String id = IDGenerator.SNOW_FLAKE_STRING.generate();
        entity.setId(id);
        return alarmConfigService.insert(entity).then(alarmConfigService.findById(id));
    }

    @PutMapping("/config/{id}")
    public Mono<Integer> updateAlarmConfig(@PathVariable String id, @RequestBody AlarmConfigEntity entity) {
        return alarmConfigService.findById(id)
            .switchIfEmpty(Mono.error(() -> new BusinessException("error.alarm_config_not_found")))
            .flatMap(config -> {
                FastBeanCopier.copy(entity, config);
                return alarmConfigService.updateById(id, config);
            });
    }

    @GetMapping("/config/{id}")
    public Mono<AlarmConfigEntity> getAlarmConfig(@PathVariable String id) {
        return alarmConfigService.findById(id);
    }

    @DeleteMapping("/config/{id}")
    public Mono<Integer> deleteAlarmConfig(@PathVariable String id) {
        return alarmConfigService.findById(id)
            .switchIfEmpty(Mono.error(() -> new BusinessException("error.alarm_config_not_found")))
            .flatMap(config -> alarmRuleBindService
                .createDelete()
                .where(AlarmRuleBindEntity::getAlarmId, id)
                .execute()
                .then(alarmConfigService.deleteById(id)));
    }

    @GetMapping("/config/{id}/_enable")
    public Mono<Void> enableAlarmConfig(@PathVariable String id) {
        return alarmConfigService.enable(id);
    }

    @GetMapping("/config/{id}/_disable")
    public Mono<Void> disableAlarmConfig(@PathVariable String id) {
        return alarmConfigService.disable(id);
    }

    @GetMapping("/config/{id}/_bind")
    public Mono<Integer> bindScene(@PathVariable String id, @RequestParam String ruleId) {
        return findConfig(id)
            .then(findNumericRule(ruleId))
            .then(Mono.defer(() -> {
                AlarmRuleBindEntity bind = new AlarmRuleBindEntity();
                bind.setAlarmId(id);
                bind.setRuleId(ruleId);
                bind.setBranchIndex(AlarmRuleBindEntity.ANY_BRANCH_INDEX);
                String bindId = bind.getId();
                return alarmRuleBindService.findById(bindId)
                    .map(exists -> 0)
                    .switchIfEmpty(alarmRuleBindService.insert(bind));
            }));
    }

    @GetMapping("/config/{id}/_unbind")
    public Mono<Integer> unbindScene(@PathVariable String id, @RequestParam String ruleId) {
        return findConfig(id)
            .then(findNumericRule(ruleId))
            .then(alarmRuleBindService
                .createDelete()
                .where(AlarmRuleBindEntity::getAlarmId, id)
                .where(AlarmRuleBindEntity::getRuleId, ruleId)
                .execute());
    }

    private Mono<AlarmConfigEntity> findConfig(String id) {
        return alarmConfigService.findById(id)
            .switchIfEmpty(Mono.error(() -> new BusinessException("error.alarm_config_not_found")));
    }

    private Mono<SceneEntity> findNumericRule(String id) {
        return sceneService.findById(id)
            .switchIfEmpty(Mono.error(() -> new BusinessException("error.numeric_alarm_rule_not_found")))
            .map(RuleSceneUtil::requireNumericAlarmRule);
    }

    private DiYaAlarmRecordEntity toDiYaAlarm(SynchronizationAlarmRecordEntity request) {
        DiYaAlarmRecordEntity alarm = new DiYaAlarmRecordEntity();
        alarm.setAlarmId(request.getLocalAlarmId());
        alarm.setAlarmStatus(Boolean.TRUE.equals(request.getIsConfirmed()) ? 1 : 0);
        alarm.setHiddenDanger(Boolean.TRUE.equals(request.getIsDanger()) ? 1 : 0);
        LocalDateTime confirmTime = request.getConfirmTime();
        if (Boolean.TRUE.equals(request.getIsConfirmed()) && confirmTime != null) {
            alarm.setConfirmTime(confirmTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
        Integer dangerFlag = request.getDangerFlag();
        if (dangerFlag != null) {
            switch (dangerFlag) {
                case 0 -> alarm.setHiddenDangerStatus(-1);
                case 1 -> alarm.setHiddenDangerStatus(1);
                case 2 -> alarm.setHiddenDangerStatus(0);
                case 3 -> alarm.setHiddenDangerStatus(2);
                default -> {
                }
            }
        }
        return alarm;
    }
}
