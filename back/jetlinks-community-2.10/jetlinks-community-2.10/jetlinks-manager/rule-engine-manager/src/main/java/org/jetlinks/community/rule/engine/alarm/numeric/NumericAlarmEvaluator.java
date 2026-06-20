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

import org.jetlinks.community.rule.engine.entity.NumericAlarmRuleEntity.Condition;
import org.jetlinks.community.rule.engine.entity.NumericAlarmRuleEntity.ConditionGroup;
import org.jetlinks.community.rule.engine.entity.NumericAlarmRuleEntity.NumericLogic;
import org.jetlinks.community.rule.engine.entity.NumericAlarmRuleEntity.NumericOperator;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class NumericAlarmEvaluator {

    private NumericAlarmEvaluator() {
    }

    public static EvaluationResult evaluate(ConditionGroup group, Map<String, Object> properties) {
        if (group == null || CollectionUtils.isEmpty(group.getTerms()) || CollectionUtils.isEmpty(properties)) {
            return EvaluationResult.SKIPPED;
        }

        NumericLogic logic = group.getLogic();
        boolean matched = logic == NumericLogic.and;
        for (Condition term : group.getTerms()) {
            Optional<Boolean> termResult = evaluateTerm(term, properties);
            if (!termResult.isPresent()) {
                return EvaluationResult.SKIPPED;
            }
            if (logic == NumericLogic.or && termResult.get()) {
                matched = true;
            }
            if (logic == NumericLogic.and && !termResult.get()) {
                matched = false;
            }
        }
        return matched ? EvaluationResult.MATCHED : EvaluationResult.UNMATCHED;
    }

    public static Set<String> resolveProperties(ConditionGroup group) {
        Set<String> properties = new LinkedHashSet<>();
        if (group == null || CollectionUtils.isEmpty(group.getTerms())) {
            return properties;
        }
        for (Condition term : group.getTerms()) {
            if (term == null) {
                continue;
            }
            String property = term.getProperty();
            if (StringUtils.hasText(property)) {
                properties.add(property);
            }
        }
        return properties;
    }

    private static Optional<Boolean> evaluateTerm(Condition term, Map<String, Object> properties) {
        if (term == null || term.getOperator() == null || !StringUtils.hasText(term.getProperty())) {
            return Optional.empty();
        }
        String property = term.getProperty();
        if (!properties.containsKey(property)) {
            return Optional.empty();
        }
        Optional<BigDecimal> current = toDecimal(properties.get(property));
        Optional<BigDecimal> expected = toDecimal(term.getValue());
        if (!current.isPresent() || !expected.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(match(current.get(), expected.get(), term.getOperator()));
    }

    static Optional<BigDecimal> toDecimal(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof BigDecimal) {
            return Optional.of((BigDecimal) value);
        }
        if (value instanceof Number) {
            if (value instanceof Double && !Double.isFinite((Double) value)) {
                return Optional.empty();
            }
            if (value instanceof Float && !Float.isFinite((Float) value)) {
                return Optional.empty();
            }
            return parseDecimal(String.valueOf(value));
        }
        if (value instanceof CharSequence) {
            return parseDecimal(value.toString());
        }
        return Optional.empty();
    }

    private static Optional<BigDecimal> parseDecimal(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(value.trim()));
        } catch (NumberFormatException ignore) {
            return Optional.empty();
        }
    }

    private static boolean match(BigDecimal current, BigDecimal expected, NumericOperator operator) {
        int compare = current.compareTo(expected);
        switch (operator) {
            case gt:
                return compare > 0;
            case gte:
                return compare >= 0;
            case lt:
                return compare < 0;
            case lte:
                return compare <= 0;
            case eq:
                return compare == 0;
            case neq:
                return compare != 0;
            default:
                return false;
        }
    }

    public enum EvaluationResult {
        MATCHED,
        UNMATCHED,
        SKIPPED
    }
}
