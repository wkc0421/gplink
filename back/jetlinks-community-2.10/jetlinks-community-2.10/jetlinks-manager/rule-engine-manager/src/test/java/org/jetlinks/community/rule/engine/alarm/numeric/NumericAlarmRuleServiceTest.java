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

import org.jetlinks.community.rule.engine.entity.NumericAlarmRuleEntity;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumericAlarmRuleServiceTest {

    @Test
    void allDeviceSelectorOverlapsAnyRuleInSameProductAndAlarmConfig() {
        assertTrue(NumericAlarmRuleService.isTargetScopeOverlapped(rule(NumericAlarmRuleEntity.DeviceSelector.all),
                                                                  rule(NumericAlarmRuleEntity.DeviceSelector.fixed, "device-1")));
    }

    @Test
    void fixedDeviceSelectorOverlapsOnlyWhenDeviceIntersects() {
        assertTrue(NumericAlarmRuleService.isTargetScopeOverlapped(rule(NumericAlarmRuleEntity.DeviceSelector.fixed,
                                                                        "device-1", "device-2"),
                                                                  rule(NumericAlarmRuleEntity.DeviceSelector.fixed,
                                                                       "device-2", "device-3")));

        assertFalse(NumericAlarmRuleService.isTargetScopeOverlapped(rule(NumericAlarmRuleEntity.DeviceSelector.fixed,
                                                                         "device-1"),
                                                                   rule(NumericAlarmRuleEntity.DeviceSelector.fixed,
                                                                        "device-2")));
    }

    @Test
    void fixedRuleWithoutDevicesDoesNotOverlap() {
        assertFalse(NumericAlarmRuleService.isTargetScopeOverlapped(rule(NumericAlarmRuleEntity.DeviceSelector.fixed),
                                                                   rule(NumericAlarmRuleEntity.DeviceSelector.fixed,
                                                                        "device-1")));
    }

    private static NumericAlarmRuleEntity rule(NumericAlarmRuleEntity.DeviceSelector selector, String... deviceIds) {
        NumericAlarmRuleEntity rule = new NumericAlarmRuleEntity();
        rule.setSelector(selector);
        rule.setDeviceIds(deviceIds.length == 0 ? Collections.emptyList() : Arrays.asList(deviceIds));
        return rule;
    }
}
