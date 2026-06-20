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

import org.junit.jupiter.api.Test;
import org.jetlinks.community.rule.engine.alarm.numeric.NumericAlarmEvaluator.EvaluationResult;
import org.jetlinks.community.rule.engine.entity.NumericAlarmRuleEntity.Condition;
import org.jetlinks.community.rule.engine.entity.NumericAlarmRuleEntity.ConditionGroup;
import org.jetlinks.community.rule.engine.entity.NumericAlarmRuleEntity.NumericLogic;
import org.jetlinks.community.rule.engine.entity.NumericAlarmRuleEntity.NumericOperator;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NumericAlarmEvaluatorTest {

    @Test
    void compareAllOperators() {
        assertEquals(EvaluationResult.MATCHED,
                     evaluate(condition("temp", NumericOperator.gt, 10), props("temp", 10.1)));
        assertEquals(EvaluationResult.MATCHED,
                     evaluate(condition("temp", NumericOperator.gte, "10.0"), props("temp", 10)));
        assertEquals(EvaluationResult.MATCHED,
                     evaluate(condition("temp", NumericOperator.lt, 10), props("temp", "9.9")));
        assertEquals(EvaluationResult.MATCHED,
                     evaluate(condition("temp", NumericOperator.lte, 10), props("temp", "10.00")));
        assertEquals(EvaluationResult.MATCHED,
                     evaluate(condition("temp", NumericOperator.eq, "10.0"), props("temp", "10.00")));
        assertEquals(EvaluationResult.MATCHED,
                     evaluate(condition("temp", NumericOperator.neq, "10.0"), props("temp", 11)));
    }

    @Test
    void falseConditionCanRelieve() {
        assertEquals(EvaluationResult.UNMATCHED,
                     evaluate(condition("temp", NumericOperator.gt, 10), props("temp", 10)));
        assertEquals(EvaluationResult.UNMATCHED,
                     evaluate(condition("temp", NumericOperator.lte, 10), props("temp", 11)));
    }

    @Test
    void invalidNumberSkipsMessage() {
        assertEquals(EvaluationResult.SKIPPED,
                     evaluate(condition("temp", NumericOperator.gt, 10), props("temp", "bad-number")));
        assertEquals(EvaluationResult.SKIPPED,
                     evaluate(condition("temp", NumericOperator.gt, "bad-number"), props("temp", 11)));
    }

    @Test
    void evaluateAndConditions() {
        ConditionGroup group = group(NumericLogic.and,
                                     condition("temp", NumericOperator.gt, 30),
                                     condition("humidity", NumericOperator.lte, 80));

        assertEquals(EvaluationResult.MATCHED,
                     NumericAlarmEvaluator.evaluate(group, props("temp", 31, "humidity", 80)));
        assertEquals(EvaluationResult.UNMATCHED,
                     NumericAlarmEvaluator.evaluate(group, props("temp", 31, "humidity", 81)));
    }

    @Test
    void evaluateOrConditions() {
        ConditionGroup group = group(NumericLogic.or,
                                     condition("temp", NumericOperator.gt, 30),
                                     condition("humidity", NumericOperator.lt, 20));

        assertEquals(EvaluationResult.MATCHED,
                     NumericAlarmEvaluator.evaluate(group, props("temp", 20, "humidity", 19)));
        assertEquals(EvaluationResult.UNMATCHED,
                     NumericAlarmEvaluator.evaluate(group, props("temp", 20, "humidity", 30)));
    }

    @Test
    void missingConditionPropertySkipsEvenWhenAnotherTermIsKnown() {
        assertEquals(EvaluationResult.SKIPPED,
                     NumericAlarmEvaluator.evaluate(
                         group(NumericLogic.and,
                               condition("temp", NumericOperator.lt, 10),
                               condition("humidity", NumericOperator.lt, 20)),
                         props("temp", 30)));

        assertEquals(EvaluationResult.SKIPPED,
                     NumericAlarmEvaluator.evaluate(
                         group(NumericLogic.or,
                               condition("temp", NumericOperator.gt, 10),
                               condition("humidity", NumericOperator.lt, 20)),
                         props("temp", 30)));
    }

    @Test
    void propertyIdAliasIsSupported() {
        Condition condition = condition(null, NumericOperator.gt, 10);
        condition.setPropertyId("temp");

        assertEquals(EvaluationResult.MATCHED,
                     NumericAlarmEvaluator.evaluate(group(NumericLogic.and, condition), props("temp", 11)));
    }

    private static EvaluationResult evaluate(Condition condition, Map<String, Object> properties) {
        return NumericAlarmEvaluator.evaluate(group(NumericLogic.and, condition), properties);
    }

    private static Condition condition(String property, NumericOperator operator, Object value) {
        Condition condition = new Condition();
        condition.setProperty(property);
        condition.setOperator(operator);
        condition.setValue(value);
        return condition;
    }

    private static ConditionGroup group(NumericLogic logic, Condition... conditions) {
        ConditionGroup group = new ConditionGroup();
        group.setLogic(logic);
        group.setTerms(java.util.Arrays.asList(conditions));
        return group;
    }

    private static Map<String, Object> props(Object... keyValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            values.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return values;
    }
}
