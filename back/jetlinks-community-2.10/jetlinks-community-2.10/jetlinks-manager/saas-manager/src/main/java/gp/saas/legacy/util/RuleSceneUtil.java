package gp.saas.legacy.util;

import org.hswebframework.ezorm.core.param.Term;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.relation.utils.VariableSource;
import org.jetlinks.community.rule.engine.commons.ShakeLimit;
import org.jetlinks.community.rule.engine.enums.AlarmMode;
import org.jetlinks.community.rule.engine.executor.device.SelectorValue;
import org.jetlinks.community.rule.engine.scene.DeviceOperation;
import org.jetlinks.community.rule.engine.scene.SceneAction;
import org.jetlinks.community.rule.engine.scene.SceneActions;
import org.jetlinks.community.rule.engine.scene.SceneConditionAction;
import org.jetlinks.community.rule.engine.scene.Trigger;
import org.jetlinks.community.rule.engine.scene.internal.actions.AlarmAction;
import org.jetlinks.community.rule.engine.scene.internal.actions.AlarmActionProvider;
import org.jetlinks.community.rule.engine.scene.internal.actions.DeviceAction;
import org.jetlinks.community.rule.engine.scene.internal.actions.DeviceActionProvider;
import org.jetlinks.community.rule.engine.scene.internal.triggers.DeviceTrigger;
import org.jetlinks.community.rule.engine.scene.internal.triggers.DeviceTriggerProvider;
import org.jetlinks.community.rule.engine.scene.internal.triggers.TimerTrigger;
import org.jetlinks.community.rule.engine.scene.internal.triggers.TimerTriggerProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class RuleSceneUtil {

    private RuleSceneUtil() {
    }

    public static Trigger createTimerTrigger(String cron) {
        Trigger trigger = new Trigger();
        trigger.setType(TimerTriggerProvider.PROVIDER);
        TimerTrigger timer = new TimerTrigger();
        timer.setTrigger(TimerTrigger.Trigger.cron);
        timer.setCron(cron);
        trigger.setTimer(timer);
        return trigger;
    }

    public static Trigger createDeviceTrigger(String productId, String deviceIds) {
        Trigger trigger = new Trigger();
        trigger.setType(DeviceTriggerProvider.PROVIDER);
        DeviceTrigger deviceTrigger = new DeviceTrigger();
        deviceTrigger.setProductId(productId);
        deviceTrigger.setOperation(DeviceOperation.reportProperty());
        if (deviceIds == null || deviceIds.isEmpty()) {
            deviceTrigger.setSelector("all");
            deviceTrigger.setSelectorValues(new ArrayList<>());
        } else {
            deviceTrigger.setSelector("fixed");
            deviceTrigger.setSelectorValues(createDeviceSelectorValueFromIdList(Arrays.asList(deviceIds.split(";"))));
        }
        trigger.setDevice(deviceTrigger);
        return trigger;
    }

    public static Map<String, Object> createTimerOptions(String cron) {
        Map<String, Object> options = new HashMap<>();
        options.put("trigger", "time:" + cron);
        return options;
    }

    public static Map<String, Object> createAlarmOptions(String productId) {
        Map<String, Object> options = new HashMap<>();
        LinkedHashMap<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("name", "product " + productId + " alarm trigger rule");
        options.put("trigger", trigger);
        return options;
    }

    public static SceneConditionAction createReadPropertyTimerBranch(boolean parallel,
                                                                     String productId,
                                                                     List<DeviceInstanceEntity> deviceList,
                                                                     String property) {
        SceneConditionAction branch = new SceneConditionAction();
        branch.setShakeLimit(createShakeLimit(false, 1, 1, false));
        branch.setThen(List.of(deviceAction(parallel, productId, deviceList, Map.of(
            "messageType", "READ_PROPERTY",
            "properties", property
        ))));
        return branch;
    }

    public static SceneConditionAction createInvokeFunctionTimerBranch(boolean parallel,
                                                                       String productId,
                                                                       List<DeviceInstanceEntity> deviceList,
                                                                       String functionId,
                                                                       String functionInput) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("input", functionInput);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("messageType", "INVOKE_FUNCTION");
        message.put("functionId", functionId);
        message.put("inputs", input);

        SceneConditionAction branch = new SceneConditionAction();
        branch.setShakeLimit(createShakeLimit(false, 1, 1, false));
        branch.setThen(List.of(deviceAction(parallel, productId, deviceList, message)));
        return branch;
    }

    public static SceneConditionAction createTriggerAlarmBranch(boolean parallel, ShakeLimit shakeLimit, List<Term> when) {
        SceneConditionAction branch = new SceneConditionAction();
        branch.setShakeLimit(shakeLimit);
        branch.setWhen(when);
        branch.setThen(List.of(alarmAction(parallel, AlarmMode.trigger)));
        return branch;
    }

    public static SceneConditionAction createRelieveAlarmBranch(boolean parallel, ShakeLimit shakeLimit) {
        SceneConditionAction branch = new SceneConditionAction();
        branch.setWhen(new ArrayList<>());
        branch.setShakeLimit(shakeLimit);
        branch.setThen(List.of(alarmAction(parallel, AlarmMode.relieve)));
        return branch;
    }

    public static ShakeLimit createShakeLimit(boolean enabled, int time, int threshold, boolean alarmFirst) {
        ShakeLimit shakeLimit = new ShakeLimit();
        shakeLimit.setEnabled(enabled);
        shakeLimit.setTime(time);
        shakeLimit.setThreshold(threshold);
        shakeLimit.setAlarmFirst(alarmFirst);
        return shakeLimit;
    }

    private static SceneActions deviceAction(boolean parallel,
                                             String productId,
                                             List<DeviceInstanceEntity> deviceList,
                                             Map<String, Object> message) {
        SceneAction action = new SceneAction();
        action.setExecutor(DeviceActionProvider.PROVIDER);
        DeviceAction device = new DeviceAction();
        device.setProductId(productId);
        device.setMessage(message);
        device.setSelector("fixed");
        device.setSelectorValues(createDeviceSelectorValueFromDeviceList(deviceList));
        device.setSource(VariableSource.Source.fixed);
        action.setDevice(device);

        SceneActions actions = new SceneActions();
        actions.setParallel(parallel);
        actions.setActions(List.of(action));
        return actions;
    }

    private static SceneActions alarmAction(boolean parallel, AlarmMode mode) {
        SceneAction action = new SceneAction();
        action.setExecutor(SceneAction.Executor.alarm);
        action.setExecutor(AlarmActionProvider.PROVIDER);
        AlarmAction alarm = new AlarmAction();
        alarm.setMode(mode);
        action.setAlarm(alarm);

        SceneActions actions = new SceneActions();
        actions.setParallel(parallel);
        actions.setActions(List.of(action));
        return actions;
    }

    public static List<SelectorValue> createDeviceSelectorValueFromDeviceList(List<DeviceInstanceEntity> deviceList) {
        return deviceList.stream().map(device -> {
            SelectorValue value = new SelectorValue();
            value.setValue(device.getId());
            value.setName(device.getName());
            return value;
        }).collect(Collectors.toList());
    }

    public static List<SelectorValue> createDeviceSelectorValueFromIdList(List<String> deviceList) {
        return deviceList.stream().map(deviceId -> {
            SelectorValue value = new SelectorValue();
            value.setValue(deviceId);
            value.setName(deviceId);
            return value;
        }).collect(Collectors.toList());
    }
}
