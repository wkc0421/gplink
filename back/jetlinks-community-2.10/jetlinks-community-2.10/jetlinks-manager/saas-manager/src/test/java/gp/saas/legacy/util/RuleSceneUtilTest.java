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
package gp.saas.legacy.util;

import gp.saas.legacy.dto.AlarmRuleEntity;
import org.hswebframework.ezorm.core.param.Term;
import org.jetlinks.community.rule.engine.entity.SceneEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleSceneUtilTest {

    @Test
    void shouldRecognizeNumericAlarmScene() {
        SceneEntity scene = new SceneEntity();
        scene.setTriggerType("device");
        scene.setTrigger(RuleSceneUtil.createDeviceTrigger("product-1", null));
        scene.setBranches(List.of(
            RuleSceneUtil.createTriggerAlarmBranch(false, null, List.of()),
            RuleSceneUtil.createRelieveAlarmBranch(false, null)
        ));

        assertTrue(RuleSceneUtil.isNumericAlarmRule(scene));
    }

    @Test
    void shouldRejectNonNumericDeviceScene() {
        SceneEntity scene = new SceneEntity();
        scene.setTriggerType("device");
        scene.setTrigger(RuleSceneUtil.createDeviceTrigger("product-1", null));
        scene.getTrigger().getDevice().getOperation()
            .setOperator(org.jetlinks.community.rule.engine.scene.DeviceOperation.Operator.reportEvent);
        scene.setBranches(List.of(
            RuleSceneUtil.createTriggerAlarmBranch(false, null, List.of()),
            RuleSceneUtil.createRelieveAlarmBranch(false, null)
        ));

        assertFalse(RuleSceneUtil.isNumericAlarmRule(scene));
    }

    @Test
    void shouldValidateNumericPropertyTerms() {
        Term term = new Term();
        term.setColumn("properties.temperature");
        term.setTermType("gt");
        term.setValue(20);

        AlarmRuleEntity request = new AlarmRuleEntity();
        request.setName("temperature alarm");
        request.setProductId("product-1");
        request.setType(Term.Type.and);
        request.setTermList(List.of(term));

        assertDoesNotThrow(() -> RuleSceneUtil.validateNumericAlarmRequest(request));
    }

    @Test
    void shouldRejectNonPropertyTerms() {
        Term term = new Term();
        term.setColumn("event.data");
        term.setTermType("eq");
        term.setValue(1);

        AlarmRuleEntity request = new AlarmRuleEntity();
        request.setName("invalid alarm");
        request.setProductId("product-1");
        request.setType(Term.Type.and);
        request.setTermList(List.of(term));

        assertThrows(RuntimeException.class, () -> RuleSceneUtil.validateNumericAlarmRequest(request));
    }
}
