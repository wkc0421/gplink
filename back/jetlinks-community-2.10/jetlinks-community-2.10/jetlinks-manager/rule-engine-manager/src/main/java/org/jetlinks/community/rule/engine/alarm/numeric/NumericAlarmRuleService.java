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
package org.jetlinks.community.rule.engine.alarm.numeric;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.rdb.mapping.defaults.SaveResult;
import org.hswebframework.web.crud.events.EntityCreatedEvent;
import org.hswebframework.web.crud.events.EntityDeletedEvent;
import org.hswebframework.web.crud.events.EntityModifyEvent;
import org.hswebframework.web.crud.events.EntitySavedEvent;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.hswebframework.web.exception.ValidationException;
import org.jetlinks.community.PropertyConstants;
import org.jetlinks.community.command.rule.data.AlarmInfo;
import org.jetlinks.community.command.rule.data.RelieveInfo;
import org.jetlinks.community.gateway.DeviceMessageUtils;
import org.jetlinks.community.gateway.annotation.Subscribe;
import org.jetlinks.community.rule.engine.alarm.AlarmHandler;
import org.jetlinks.community.rule.engine.alarm.DeviceAlarmTarget;
import org.jetlinks.community.rule.engine.alarm.numeric.NumericAlarmEvaluator.EvaluationResult;
import org.jetlinks.community.rule.engine.entity.AlarmConfigEntity;
import org.jetlinks.community.rule.engine.entity.AlarmRecordEntity;
import org.jetlinks.community.rule.engine.entity.NumericAlarmRuleEntity;
import org.jetlinks.community.rule.engine.entity.NumericAlarmRuleEntity.DeviceSelector;
import org.jetlinks.community.rule.engine.enums.AlarmHandleType;
import org.jetlinks.community.rule.engine.enums.AlarmRecordState;
import org.jetlinks.community.rule.engine.enums.AlarmState;
import org.jetlinks.community.rule.engine.service.AlarmConfigService;
import org.jetlinks.community.rule.engine.service.AlarmRecordService;
import org.jetlinks.core.event.Subscription;
import org.jetlinks.core.message.DeviceMessage;
import org.reactivestreams.Publisher;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NumericAlarmRuleService extends GenericReactiveCrudService<NumericAlarmRuleEntity, String>
    implements CommandLineRunner {

    static final String ALARM_CONFIG_SOURCE = "numericAlarmRule";

    private final AlarmConfigService alarmConfigService;

    private final AlarmRecordService alarmRecordService;

    private final AlarmHandler alarmHandler;

    private final AtomicReference<Map<String, Map<String, List<RuntimeRule>>>> ruleIndex =
        new AtomicReference<>(Collections.emptyMap());

    private final Set<String> activeRecordIds = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean reloadRunning = new AtomicBoolean();

    private final AtomicBoolean reloadRequested = new AtomicBoolean();

    @Override
    public void run(String... args) {
        reloadIndex()
            .subscribe(null, err -> log.warn("reload numeric alarm rules failed", err));
    }

    @Override
    public Mono<SaveResult> save(Publisher<NumericAlarmRuleEntity> entityPublisher) {
        return Flux
            .from(entityPublisher)
            .collectList()
            .flatMapMany(rules -> {
                validateBatchTargetScope(rules);
                return Flux
                    .fromIterable(rules)
                    .concatMap(this::validateTargetScope);
            })
            .as(super::save);
    }

    @Override
    public Mono<Integer> updateById(String id, Mono<NumericAlarmRuleEntity> entityPublisher) {
        return super.updateById(id, entityPublisher
            .doOnNext(rule -> {
                if (!StringUtils.hasText(rule.getId())) {
                    rule.setId(id);
                }
            })
            .flatMap(this::validateTargetScope));
    }

    @Subscribe(topics = "/device/*/*/message/property/report", features = Subscription.Feature.local)
    public Mono<Void> handlePropertyReport(DeviceMessage message) {
        if (message == null || !StringUtils.hasText(message.getDeviceId())) {
            return Mono.empty();
        }

        String productId = message
            .getHeader(PropertyConstants.productId)
            .map(String::valueOf)
            .orElse(null);
        if (!StringUtils.hasText(productId)) {
            return Mono.empty();
        }

        Map<String, Object> properties = DeviceMessageUtils
            .tryGetProperties(message)
            .orElse(null);
        if (CollectionUtils.isEmpty(properties)) {
            return Mono.empty();
        }

        Map<String, List<RuntimeRule>> productRules = ruleIndex
            .get()
            .get(productId);
        if (CollectionUtils.isEmpty(productRules)) {
            return Mono.empty();
        }

        Map<String, RuntimeRule> candidates = new LinkedHashMap<>();
        for (String property : properties.keySet()) {
            List<RuntimeRule> rules = productRules.get(property);
            if (CollectionUtils.isEmpty(rules)) {
                continue;
            }
            for (RuntimeRule rule : rules) {
                candidates.put(rule.getRule().getId(), rule);
            }
        }

        if (candidates.isEmpty()) {
            return Mono.empty();
        }

        return Flux
            .fromIterable(candidates.values())
            .filter(rule -> rule.matchesDevice(message.getDeviceId()))
            .concatMap(rule -> handleRule(rule, message, productId, properties)
                .onErrorResume(err -> {
                    log.warn("handle numeric alarm rule failed, ruleId={}, deviceId={}",
                             rule.getRule().getId(), message.getDeviceId(), err);
                    return Mono.empty();
                }))
            .then();
    }

    public Mono<Void> enable(String id) {
        return findById(id)
            .flatMap(this::validateTargetScope)
            .then(createUpdate()
                      .set(NumericAlarmRuleEntity::getState, AlarmState.enabled)
                      .where(NumericAlarmRuleEntity::getId, id)
                      .execute())
            .then(reloadIndex());
    }

    public Mono<Void> disable(String id) {
        return createUpdate()
            .set(NumericAlarmRuleEntity::getState, AlarmState.disabled)
            .where(NumericAlarmRuleEntity::getId, id)
            .execute()
            .then(reloadIndex());
    }

    public Mono<Void> reloadIndex() {
        return Mono.defer(() -> {
            if (!reloadRunning.compareAndSet(false, true)) {
                reloadRequested.set(true);
                return Mono.empty();
            }
            reloadRequested.set(false);
            return doReloadIndex()
                .doOnError(err -> log.warn("reload numeric alarm rules failed", err))
                .onErrorResume(err -> Mono.empty())
                .doFinally(ignore -> {
                    reloadRunning.set(false);
                    if (reloadRequested.getAndSet(false)) {
                        reloadIndex()
                            .subscribe(null, err -> log.warn("reload numeric alarm rules failed", err));
                    }
                });
        });
    }

    private Mono<Void> doReloadIndex() {
        return createQuery()
            .where(NumericAlarmRuleEntity::getState, AlarmState.enabled)
            .fetch()
            .flatMap(this::createRuntimeRule)
            .filter(RuntimeRule::isValid)
            .collectList()
            .flatMap(rules -> {
                List<RuntimeRule> uniqueRules = filterOverlappedRuntimeRules(rules);
                replaceIndex(uniqueRules);
                return reloadActiveRecords(uniqueRules);
            });
    }

    private Mono<RuntimeRule> createRuntimeRule(NumericAlarmRuleEntity rule) {
        if (rule == null || !StringUtils.hasText(rule.getAlarmConfigId())) {
            return Mono.empty();
        }
        return alarmConfigService
            .findById(rule.getAlarmConfigId())
            .filter(config -> config.getState() == null || config.getState() == AlarmState.enabled)
            .map(config -> RuntimeRule.of(rule, config));
    }

    private void replaceIndex(List<RuntimeRule> rules) {
        Map<String, Map<String, List<RuntimeRule>>> next = new LinkedHashMap<>();
        for (RuntimeRule rule : rules) {
            Map<String, List<RuntimeRule>> productRules = next
                .computeIfAbsent(rule.getRule().getProductId(), ignore -> new LinkedHashMap<>());
            for (String property : rule.getProperties()) {
                productRules
                    .computeIfAbsent(property, ignore -> new ArrayList<>())
                    .add(rule);
            }
        }

        Map<String, Map<String, List<RuntimeRule>>> immutable = new LinkedHashMap<>();
        next.forEach((productId, productRules) -> {
            Map<String, List<RuntimeRule>> immutableProductRules = new LinkedHashMap<>();
            productRules.forEach((property, indexedRules) ->
                immutableProductRules.put(property, Collections.unmodifiableList(new ArrayList<>(indexedRules))));
            immutable.put(productId, Collections.unmodifiableMap(immutableProductRules));
        });
        ruleIndex.set(Collections.unmodifiableMap(immutable));
        log.info("numeric alarm rule index reloaded, rules={}", rules.size());
    }

    private Mono<Void> handleRule(RuntimeRule rule,
                                  DeviceMessage message,
                                  String productId,
                                  Map<String, Object> properties) {
        EvaluationResult result = NumericAlarmEvaluator.evaluate(rule.getRule().getCondition(), properties);
        if (result == EvaluationResult.SKIPPED) {
            return Mono.empty();
        }
        String recordId = createRecordId(rule, message.getDeviceId());
        if (result == EvaluationResult.MATCHED) {
            return alarmHandler
                .triggerAlarm(createAlarmInfo(rule, message, productId, properties))
                .doOnNext(alarmResult -> {
                    if (alarmResult.isFirstAlarm() || alarmResult.isAlarming()) {
                        activeRecordIds.add(recordId);
                    }
                })
                .then();
        }
        if (!activeRecordIds.contains(recordId)) {
            return Mono.empty();
        }
        return alarmHandler
            .relieveAlarm(createRelieveInfo(rule, message, productId, properties))
            .then(removeActiveRecordIfNormal(recordId))
            .then();
    }

    private Mono<Void> removeActiveRecordIfNormal(String recordId) {
        return alarmRecordService
            .findById(recordId)
            .map(record -> record.getState() != AlarmRecordState.warning)
            .defaultIfEmpty(true)
            .doOnNext(normal -> {
                if (Boolean.TRUE.equals(normal)) {
                    activeRecordIds.remove(recordId);
                }
            })
            .then();
    }

    private Mono<Void> reloadActiveRecords(List<RuntimeRule> rules) {
        Set<String> alarmConfigIds = rules
            .stream()
            .map(rule -> rule.getAlarmConfig().getId())
            .filter(StringUtils::hasText)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (alarmConfigIds.isEmpty()) {
            activeRecordIds.clear();
            return Mono.empty();
        }

        return alarmRecordService
            .createQuery()
            .where(AlarmRecordEntity::getTargetType, DeviceAlarmTarget.TYPE)
            .in(AlarmRecordEntity::getAlarmConfigId, alarmConfigIds)
            .and(AlarmRecordEntity::getState, AlarmRecordState.warning)
            .and(AlarmRecordEntity::getAlarmConfigSource, ALARM_CONFIG_SOURCE)
            .fetch()
            .map(AlarmRecordEntity::getId)
            .collect(Collectors.toSet())
            .doOnNext(records -> {
                activeRecordIds.retainAll(records);
                activeRecordIds.addAll(records);
            })
            .then();
    }

    private Mono<NumericAlarmRuleEntity> validateTargetScope(NumericAlarmRuleEntity rule) {
        if (rule == null
            || !StringUtils.hasText(rule.getAlarmConfigId())
            || !StringUtils.hasText(rule.getProductId())) {
            return Mono.justOrEmpty(rule);
        }
        return createQuery()
            .where(NumericAlarmRuleEntity::getAlarmConfigId, rule.getAlarmConfigId())
            .and(NumericAlarmRuleEntity::getProductId, rule.getProductId())
            .fetch()
            .filter(existing -> !Objects.equals(existing.getId(), rule.getId()))
            .filter(existing -> isTargetScopeOverlapped(rule, existing))
            .next()
            .flatMap(existing -> Mono.<NumericAlarmRuleEntity>error(new ValidationException(
                "numeric alarm rule target scope overlaps with existing rule: " + existing.getName())))
            .switchIfEmpty(Mono.just(rule));
    }

    private static void validateBatchTargetScope(List<NumericAlarmRuleEntity> rules) {
        for (int i = 0; i < rules.size(); i++) {
            NumericAlarmRuleEntity left = rules.get(i);
            if (left == null) {
                continue;
            }
            for (int j = i + 1; j < rules.size(); j++) {
                NumericAlarmRuleEntity right = rules.get(j);
                if (right == null) {
                    continue;
                }
                if (!StringUtils.hasText(left.getAlarmConfigId())
                    || !StringUtils.hasText(left.getProductId())
                    || !StringUtils.hasText(right.getAlarmConfigId())
                    || !StringUtils.hasText(right.getProductId())) {
                    continue;
                }
                if (Objects.equals(left.getId(), right.getId())) {
                    continue;
                }
                if (Objects.equals(left.getAlarmConfigId(), right.getAlarmConfigId())
                    && Objects.equals(left.getProductId(), right.getProductId())
                    && isTargetScopeOverlapped(left, right)) {
                    throw new ValidationException("numeric alarm rule target scope overlaps in request");
                }
            }
        }
    }

    static boolean isTargetScopeOverlapped(NumericAlarmRuleEntity left, NumericAlarmRuleEntity right) {
        if (left.getSelector() == DeviceSelector.all || right.getSelector() == DeviceSelector.all) {
            return true;
        }
        Set<String> leftDevices = normalizeDeviceIds(left.getDeviceIds());
        Set<String> rightDevices = normalizeDeviceIds(right.getDeviceIds());
        if (leftDevices.isEmpty() || rightDevices.isEmpty()) {
            return false;
        }
        for (String deviceId : leftDevices) {
            if (rightDevices.contains(deviceId)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> normalizeDeviceIds(List<String> deviceIds) {
        if (CollectionUtils.isEmpty(deviceIds)) {
            return Collections.emptySet();
        }
        return deviceIds
            .stream()
            .filter(StringUtils::hasText)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<RuntimeRule> filterOverlappedRuntimeRules(List<RuntimeRule> rules) {
        List<RuntimeRule> accepted = new ArrayList<>(rules.size());
        for (RuntimeRule rule : rules) {
            RuntimeRule overlapped = accepted
                .stream()
                .filter(existing -> Objects.equals(existing.getRule().getAlarmConfigId(), rule.getRule().getAlarmConfigId())
                    && Objects.equals(existing.getRule().getProductId(), rule.getRule().getProductId())
                    && existing.overlaps(rule))
                .findFirst()
                .orElse(null);
            if (overlapped != null) {
                log.warn("skip overlapped numeric alarm rule, ruleId={}, overlappedRuleId={}, alarmConfigId={}, productId={}",
                         rule.getRule().getId(),
                         overlapped.getRule().getId(),
                         rule.getRule().getAlarmConfigId(),
                         rule.getRule().getProductId());
                continue;
            }
            accepted.add(rule);
        }
        return accepted;
    }

    private AlarmInfo createAlarmInfo(RuntimeRule rule,
                                      DeviceMessage message,
                                      String productId,
                                      Map<String, Object> properties) {
        AlarmInfo info = new AlarmInfo();
        fillCommonAlarmInfo(info, rule, message, productId, properties);
        return info;
    }

    private RelieveInfo createRelieveInfo(RuntimeRule rule,
                                          DeviceMessage message,
                                          String productId,
                                          Map<String, Object> properties) {
        RelieveInfo info = new RelieveInfo();
        fillCommonAlarmInfo(info, rule, message, productId, properties);
        info.setRelieveTime(resolveMessageTime(message));
        info.setRelieveReason("numeric condition not matched");
        info.setDescribe("numeric condition not matched");
        info.setAlarmRelieveType(AlarmHandleType.system.getValue());
        return info;
    }

    private void fillCommonAlarmInfo(AlarmInfo info,
                                     RuntimeRule rule,
                                     DeviceMessage message,
                                     String productId,
                                     Map<String, Object> properties) {
        NumericAlarmRuleEntity entity = rule.getRule();
        AlarmConfigEntity config = rule.getAlarmConfig();
        String deviceId = message.getDeviceId();
        String deviceName = message
            .getHeader(PropertyConstants.deviceName)
            .map(String::valueOf)
            .orElse(deviceId);
        String productName = message
            .getHeader(PropertyConstants.productName)
            .map(String::valueOf)
            .orElse(productId);
        String ownerId = ownerId(config, entity);

        info.setAlarmConfigId(config.getId());
        info.setAlarmName(config.getName());
        info.setDescription(StringUtils.hasText(config.getDescription())
                                ? config.getDescription()
                                : entity.getDescription());
        info.setLevel(config.getLevel() == null ? 0 : config.getLevel());
        info.setTargetType(DeviceAlarmTarget.TYPE);
        info.setTargetId(deviceId);
        info.setTargetName(deviceName);
        info.setSourceType(DeviceAlarmTarget.TYPE);
        info.setSourceId(deviceId);
        info.setSourceName(deviceName);
        info.setSourceCreatorId(ownerId);
        info.setAlarmConfigSource(ALARM_CONFIG_SOURCE);
        info.setAlarmTime(resolveMessageTime(message));
        info.setData(createAlarmData(rule, productId, productName, deviceId, deviceName, ownerId, properties));
    }

    private Map<String, Object> createAlarmData(RuntimeRule rule,
                                                String productId,
                                                String productName,
                                                String deviceId,
                                                String deviceName,
                                                String ownerId,
                                                Map<String, Object> properties) {
        NumericAlarmRuleEntity entity = rule.getRule();
        AlarmConfigEntity config = rule.getAlarmConfig();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("deviceId", deviceId);
        output.put("deviceName", deviceName);
        output.put("productId", productId);
        output.put("productName", productName);
        output.put("sourceType", DeviceAlarmTarget.TYPE);
        output.put("sourceId", deviceId);
        output.put("sourceName", deviceName);
        output.put("ruleId", entity.getId());
        output.put("ruleName", entity.getName());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("alarmConfigId", config.getId());
        data.put("alarmName", config.getName());
        data.put("ruleId", entity.getId());
        data.put("ruleName", entity.getName());
        data.put("creatorId", ownerId);
        data.put("alarmConfigSource", ALARM_CONFIG_SOURCE);
        data.put("numericAlarmRule", true);

        for (String property : rule.getProperties()) {
            if (!properties.containsKey(property)) {
                continue;
            }
            String currentKey = property + "_current";
            Object currentValue = properties.get(property);
            output.put(currentKey, currentValue);
            data.put(currentKey, currentValue);
        }

        data.put("output", output);
        return data;
    }

    private long resolveMessageTime(DeviceMessage message) {
        long timestamp = message.getTimestamp();
        return timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    private static String createRecordId(RuntimeRule rule, String deviceId) {
        return AlarmRecordEntity.generateId(deviceId, DeviceAlarmTarget.TYPE, rule.getAlarmConfig().getId());
    }

    private static String ownerId(AlarmConfigEntity config, NumericAlarmRuleEntity rule) {
        if (StringUtils.hasText(config.getModifierId())) {
            return config.getModifierId();
        }
        if (StringUtils.hasText(config.getCreatorId())) {
            return config.getCreatorId();
        }
        if (StringUtils.hasText(rule.getModifierId())) {
            return rule.getModifierId();
        }
        return rule.getCreatorId();
    }

    @EventListener
    public void handleRuleSaved(EntitySavedEvent<NumericAlarmRuleEntity> event) {
        event.async(reloadIndex());
    }

    @EventListener
    public void handleRuleCreated(EntityCreatedEvent<NumericAlarmRuleEntity> event) {
        event.async(reloadIndex());
    }

    @EventListener
    public void handleRuleModified(EntityModifyEvent<NumericAlarmRuleEntity> event) {
        event.async(reloadIndex());
    }

    @EventListener
    public void handleRuleDeleted(EntityDeletedEvent<NumericAlarmRuleEntity> event) {
        event.async(reloadIndex());
    }

    @EventListener
    public void handleAlarmConfigSaved(EntitySavedEvent<AlarmConfigEntity> event) {
        event.async(reloadIndex());
    }

    @EventListener
    public void handleAlarmConfigCreated(EntityCreatedEvent<AlarmConfigEntity> event) {
        event.async(reloadIndex());
    }

    @EventListener
    public void handleAlarmConfigModified(EntityModifyEvent<AlarmConfigEntity> event) {
        event.async(reloadIndex());
    }

    @EventListener
    public void handleAlarmConfigDeleted(EntityDeletedEvent<AlarmConfigEntity> event) {
        event.async(reloadIndex());
    }

    @Getter
    private static class RuntimeRule {

        private final NumericAlarmRuleEntity rule;

        private final AlarmConfigEntity alarmConfig;

        private final Set<String> properties;

        private final Set<String> deviceIds;

        private RuntimeRule(NumericAlarmRuleEntity rule,
                            AlarmConfigEntity alarmConfig,
                            Set<String> properties,
                            Set<String> deviceIds) {
            this.rule = rule;
            this.alarmConfig = alarmConfig;
            this.properties = properties;
            this.deviceIds = deviceIds;
        }

        static RuntimeRule of(NumericAlarmRuleEntity rule, AlarmConfigEntity alarmConfig) {
            Set<String> properties = NumericAlarmEvaluator.resolveProperties(rule.getCondition());
            Set<String> deviceIds = normalizeDeviceIds(rule.getDeviceIds());
            NumericAlarmRuleEntity runtimeRule = copyRuntimeRule(rule);
            return new RuntimeRule(runtimeRule,
                                    alarmConfig,
                                    Collections.unmodifiableSet(properties),
                                    Collections.unmodifiableSet(deviceIds));
        }

        private static NumericAlarmRuleEntity copyRuntimeRule(NumericAlarmRuleEntity rule) {
            NumericAlarmRuleEntity runtimeRule = new NumericAlarmRuleEntity();
            runtimeRule.setId(rule.getId());
            runtimeRule.setName(rule.getName());
            runtimeRule.setAlarmConfigId(rule.getAlarmConfigId());
            runtimeRule.setProductId(rule.getProductId());
            runtimeRule.setSelector(rule.getSelector());
            runtimeRule.setCondition(rule.getCondition());
            runtimeRule.setState(rule.getState());
            runtimeRule.setDescription(rule.getDescription());
            runtimeRule.setCreatorId(rule.getCreatorId());
            runtimeRule.setModifierId(rule.getModifierId());
            return runtimeRule;
        }

        boolean isValid() {
            return StringUtils.hasText(rule.getId())
                && StringUtils.hasText(rule.getProductId())
                && !CollectionUtils.isEmpty(properties);
        }

        boolean matchesDevice(String deviceId) {
            if (!StringUtils.hasText(deviceId)) {
                return false;
            }
            if (rule.getSelector() == DeviceSelector.all) {
                return true;
            }
            return deviceIds.contains(deviceId);
        }

        boolean overlaps(RuntimeRule another) {
            if (rule.getSelector() == DeviceSelector.all || another.rule.getSelector() == DeviceSelector.all) {
                return true;
            }
            if (deviceIds.isEmpty() || another.deviceIds.isEmpty()) {
                return false;
            }
            for (String deviceId : deviceIds) {
                if (another.deviceIds.contains(deviceId)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof RuntimeRule)) {
                return false;
            }
            RuntimeRule that = (RuntimeRule) o;
            return Objects.equals(rule.getId(), that.rule.getId());
        }

        @Override
        public int hashCode() {
            return Objects.hash(rule.getId());
        }
    }
}
