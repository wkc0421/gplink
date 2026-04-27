package gp.saas.legacy.web;

import gp.saas.legacy.dto.AlarmRuleEntity;
import gp.saas.legacy.dto.TimerInvokeFunctionEntity;
import gp.saas.legacy.dto.TimerReadPropertyRuleEntity;
import gp.saas.legacy.util.RuleSceneUtil;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.bean.FastBeanCopier;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.rule.engine.entity.SceneEntity;
import org.jetlinks.community.rule.engine.scene.SceneConditionAction;
import org.jetlinks.community.rule.engine.scene.SceneRule;
import org.jetlinks.community.rule.engine.scene.Trigger;
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
@RequestMapping("/api/v1/rule/scene")
@Authorize(ignore = true)
public class LegacyRuleSceneController {

    private final LocalDeviceInstanceService deviceService;
    private final SceneService sceneService;

    public LegacyRuleSceneController(LocalDeviceInstanceService deviceService, SceneService sceneService) {
        this.deviceService = deviceService;
        this.sceneService = sceneService;
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
        return sceneService.findById(id);
    }

    @DeleteMapping("/{id}")
    public Mono<Integer> deleteScene(@PathVariable String id) {
        return sceneService.deleteById(id);
    }

    @GetMapping("/{id}/_enable")
    public Mono<Void> enableScene(@PathVariable String id) {
        return sceneService.enable(id);
    }

    @GetMapping("/{id}/_disable")
    public Mono<Void> disableScene(@PathVariable String id) {
        return sceneService.disabled(id);
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
        return sceneService.createScene(alarmRule(request));
    }

    @PutMapping("/{id}/alarm/_trigger")
    public Mono<SceneEntity> updateAlarmRule(@PathVariable String id, @RequestBody AlarmRuleEntity request) {
        return sceneService.disabled(id).then(sceneService.updateScene(id, alarmRule(request)));
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
        List<Term> when = new ArrayList<>();
        Term term = new Term();
        term.setType(request.getType());
        term.setTerms(request.getTermList());
        when.add(term);
        List<SceneConditionAction> branches = new ArrayList<>();
        branches.add(RuleSceneUtil.createTriggerAlarmBranch(false, request.getShakeLimit(), when));
        branches.add(RuleSceneUtil.createRelieveAlarmBranch(false, request.getShakeLimit()));
        rule.setBranches(branches);
        return rule;
    }
}
