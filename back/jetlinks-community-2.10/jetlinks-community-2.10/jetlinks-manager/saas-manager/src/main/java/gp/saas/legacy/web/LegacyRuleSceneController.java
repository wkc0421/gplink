package gp.saas.legacy.web;

import gp.saas.legacy.dto.AlarmRuleEntity;
import gp.saas.legacy.dto.TimerInvokeFunctionEntity;
import gp.saas.legacy.dto.TimerReadPropertyRuleEntity;
import gp.saas.legacy.util.RuleSceneUtil;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.hswebframework.web.bean.FastBeanCopier;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.rule.engine.entity.AlarmRuleBindEntity;
import org.jetlinks.community.rule.engine.entity.SceneEntity;
import org.jetlinks.community.rule.engine.scene.SceneConditionAction;
import org.jetlinks.community.rule.engine.scene.SceneRule;
import org.jetlinks.community.rule.engine.scene.Trigger;
import org.jetlinks.community.rule.engine.service.AlarmRuleBindService;
import org.jetlinks.community.rule.engine.service.SceneService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping({
    "/v1/rule/scene",
    "/api/v1/rule/scene"
})
@Authorize
@Resource(id = "rule-scene", name = "Rule scene")
public class LegacyRuleSceneController {

    private final LocalDeviceInstanceService deviceService;
    private final SceneService sceneService;
    private final AlarmRuleBindService alarmRuleBindService;

    public LegacyRuleSceneController(LocalDeviceInstanceService deviceService,
                                     SceneService sceneService,
                                     AlarmRuleBindService alarmRuleBindService) {
        this.deviceService = deviceService;
        this.sceneService = sceneService;
        this.alarmRuleBindService = alarmRuleBindService;
    }

    @PostMapping
    public Mono<Integer> createScene(@RequestBody SceneEntity sceneEntity) {
        return sceneService.insert(sceneEntity);
    }

    @PutMapping("/{id}")
    public Mono<Integer> updateScene(@PathVariable String id, @RequestBody SceneEntity sceneEntity) {
        return sceneService.findById(id).flatMap(entity -> {
            FastBeanCopier.copy(sceneEntity, entity);
            return sceneService.updateById(id, entity);
        });
    }

    @GetMapping("/{id}")
    public Mono<SceneEntity> getScene(@PathVariable String id) {
        return sceneService.findById(id)
            .switchIfEmpty(Mono.error(() -> new BusinessException("error.numeric_alarm_rule_not_found")))
            .map(RuleSceneUtil::requireNumericAlarmRule);
    }

    @DeleteMapping("/{id}")
    public Mono<Integer> deleteScene(@PathVariable String id) {
        return sceneService.findById(id)
            .switchIfEmpty(Mono.error(() -> new BusinessException("error.numeric_alarm_rule_not_found")))
            .map(RuleSceneUtil::requireDisabledNumericAlarmRule)
            .flatMap(scene -> alarmRuleBindService
                .createDelete()
                .where(AlarmRuleBindEntity::getRuleId, id)
                .execute()
                .then(sceneService.deleteById(id)));
    }

    @GetMapping("/{id}/_enable")
    @SaveAction
    public Mono<Void> enableScene(@PathVariable String id) {
        return findNumericRule(id).then(sceneService.enable(id));
    }

    @GetMapping("/{id}/_disable")
    @SaveAction
    public Mono<Void> disableScene(@PathVariable String id) {
        return findNumericRule(id).then(sceneService.disabled(id));
    }

    @PostMapping("/property/_read")
    public Mono<SceneEntity> createReadPropertyTimer(@RequestBody TimerReadPropertyRuleEntity request) {
        return buildDevices(request.getProductId(), request.getDeviceIds())
            .flatMap(devices -> sceneService.createScene(readPropertyRule(request, devices)));
    }

    @PutMapping("/{id}/property/_read")
    public Mono<SceneEntity> updateReadPropertyTimer(@PathVariable String id,
                                                     @RequestBody TimerReadPropertyRuleEntity request) {
        return buildDevices(request.getProductId(), request.getDeviceIds())
            .flatMap(devices -> sceneService.disabled(id).then(sceneService.updateScene(id, readPropertyRule(request, devices))));
    }

    @PostMapping("/function/_invoke")
    public Mono<SceneEntity> createInvokeFunctionTimer(@RequestBody TimerInvokeFunctionEntity request) {
        return buildDevices(request.getProductId(), request.getDeviceIds())
            .flatMap(devices -> sceneService.createScene(invokeFunctionRule(request, devices)));
    }

    @PutMapping("/{id}/function/_invoke")
    public Mono<SceneEntity> updateInvokeFunctionTimer(@PathVariable String id,
                                                       @RequestBody TimerInvokeFunctionEntity request) {
        return buildDevices(request.getProductId(), request.getDeviceIds())
            .flatMap(devices -> sceneService.disabled(id).then(sceneService.updateScene(id, invokeFunctionRule(request, devices))));
    }

    @PostMapping("/alarm/_trigger")
    public Mono<SceneEntity> createAlarmRule(@RequestBody AlarmRuleEntity request) {
        RuleSceneUtil.validateNumericAlarmRequest(request);
        return sceneService.createScene(alarmRule(request));
    }

    @PutMapping("/{id}/alarm/_trigger")
    public Mono<SceneEntity> updateAlarmRule(@PathVariable String id, @RequestBody AlarmRuleEntity request) {
        RuleSceneUtil.validateNumericAlarmRequest(request);
        return findNumericRule(id)
            .then(sceneService.disabled(id))
            .then(sceneService.updateScene(id, alarmRule(request)));
    }

    private Mono<SceneEntity> findNumericRule(String id) {
        return sceneService.findById(id)
            .switchIfEmpty(Mono.error(() -> new BusinessException("error.numeric_alarm_rule_not_found")))
            .map(RuleSceneUtil::requireNumericAlarmRule);
    }

    private Mono<List<DeviceInstanceEntity>> buildDevices(String productId, String deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return deviceService.createQuery().where(DeviceInstanceEntity::getProductId, productId).fetch().collectList();
        }
        return Flux.fromIterable(Arrays.asList(deviceIds.split(";"))).flatMap(deviceService::findById).collectList();
    }

    private SceneRule readPropertyRule(TimerReadPropertyRuleEntity request, List<DeviceInstanceEntity> devices) {
        SceneRule rule = timerRule(request.getName(), request.getCronExpression());
        rule.setBranches(List.of(RuleSceneUtil.createReadPropertyTimerBranch(false, request.getProductId(), devices, request.getProperty())));
        return rule;
    }

    private SceneRule invokeFunctionRule(TimerInvokeFunctionEntity request, List<DeviceInstanceEntity> devices) {
        SceneRule rule = timerRule(request.getName(), request.getCronExpression());
        String input = request.getFunctionInput() == null || request.getFunctionInput().isEmpty() ? "{}" : request.getFunctionInput();
        rule.setBranches(List.of(RuleSceneUtil.createInvokeFunctionTimerBranch(false, request.getProductId(), devices, request.getFunctionId(), input)));
        return rule;
    }

    private SceneRule timerRule(String name, String cron) {
        SceneRule rule = new SceneRule();
        rule.setName(name);
        rule.setParallel(false);
        Trigger trigger = RuleSceneUtil.createTimerTrigger(cron);
        rule.setTrigger(trigger);
        rule.setOptions(RuleSceneUtil.createTimerOptions(cron));
        return rule;
    }

    private SceneRule alarmRule(AlarmRuleEntity request) {
        SceneRule rule = new SceneRule();
        rule.setName(request.getName());
        rule.setParallel(false);
        rule.setTrigger(RuleSceneUtil.createDeviceTrigger(request.getProductId(), request.getDeviceIds()));
        rule.setOptions(RuleSceneUtil.createAlarmOptions(request.getProductId()));
        List<Term> presentTerms = createPresentTerms(request.getTermList());
        Term condition = new Term();
        condition.setType(request.getType());
        condition.setTerms(request.getTermList());
        List<Term> guardedTerms = new ArrayList<>(presentTerms);
        guardedTerms.add(condition);
        Term guardedCondition = new Term();
        guardedCondition.setType(Term.Type.and);
        guardedCondition.setTerms(guardedTerms);
        List<SceneConditionAction> branches = new ArrayList<>();
        branches.add(RuleSceneUtil.createTriggerAlarmBranch(false, request.getShakeLimit(), List.of(guardedCondition)));
        branches.add(RuleSceneUtil.createRelieveAlarmBranch(false, request.getShakeLimit(), createPresentCondition(presentTerms)));
        rule.setBranches(branches);
        return rule;
    }

    private List<Term> createPresentCondition(List<Term> terms) {
        if (terms == null || terms.isEmpty()) {
            return new ArrayList<>();
        }
        Term condition = new Term();
        condition.setType(Term.Type.and);
        condition.setTerms(terms);
        return List.of(condition);
    }

    private List<Term> createPresentTerms(List<Term> terms) {
        List<Term> result = new ArrayList<>();
        collectPresentTerms(terms, result);
        return result;
    }

    private void collectPresentTerms(List<Term> terms, List<Term> result) {
        if (terms == null) {
            return;
        }
        for (Term term : terms) {
            if (term == null) {
                continue;
            }
            if (term.getTerms() != null && !term.getTerms().isEmpty()) {
                collectPresentTerms(term.getTerms(), result);
                continue;
            }
            String column = term.getColumn();
            if (column == null || !column.startsWith("properties.")) {
                continue;
            }
            String[] columnParts = column.split("\\.");
            if (columnParts.length < 2 || columnParts[1].isBlank()) {
                continue;
            }
            Term present = new Term();
            present.setColumn("headers._reportedProperties");
            present.setTermType("like");
            present.setValue("%," + columnParts[1] + ",%");
            result.add(present);
        }
    }
}
