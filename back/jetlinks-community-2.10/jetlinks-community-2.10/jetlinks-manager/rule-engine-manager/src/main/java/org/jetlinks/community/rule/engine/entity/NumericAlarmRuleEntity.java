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
package org.jetlinks.community.rule.engine.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.Comment;
import org.hswebframework.ezorm.rdb.mapping.annotation.DefaultValue;
import org.hswebframework.ezorm.rdb.mapping.annotation.EnumCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.api.crud.entity.RecordCreationEntity;
import org.hswebframework.web.api.crud.entity.RecordModifierEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.hswebframework.web.crud.generator.Generators;
import org.jetlinks.community.rule.engine.enums.AlarmState;
import org.springframework.util.StringUtils;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import java.sql.JDBCType;
import java.util.List;

@Getter
@Setter
@Table(name = "numeric_alarm_rule", indexes = {
    @Index(name = "idx_num_alarm_cfg_id", columnList = "alarmConfigId"),
    @Index(name = "idx_num_alarm_product_id", columnList = "productId")
})
@Comment("Numeric device alarm rule")
@EnableEntityEvent
public class NumericAlarmRuleEntity extends GenericEntity<String> implements RecordCreationEntity, RecordModifierEntity {

    @Column(length = 64, nullable = false)
    @Schema(description = "Rule name")
    @NotBlank
    private String name;

    @Column(length = 64, nullable = false)
    @Schema(description = "Alarm config ID")
    @NotBlank
    private String alarmConfigId;

    @Column(length = 64, nullable = false)
    @Schema(description = "Product ID")
    @NotBlank
    private String productId;

    @Column(length = 16, nullable = false)
    @EnumCodec
    @ColumnType(javaType = String.class)
    @DefaultValue("all")
    @Schema(description = "Device selector: all or fixed")
    private DeviceSelector selector;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR)
    @Schema(description = "Fixed device IDs when selector is fixed")
    private List<String> deviceIds;

    @Column(nullable = false)
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR)
    @Schema(description = "Numeric alarm conditions")
    @NotNull
    private ConditionGroup condition;

    @Column(length = 32, nullable = false)
    @EnumCodec
    @ColumnType(javaType = String.class)
    @DefaultValue("enabled")
    @Schema(description = "Rule state")
    private AlarmState state;

    @Column
    @Schema(description = "Debounce count reserved for future use")
    private Integer shakeLimit;

    @Column
    @Schema(description = "Debounce window in milliseconds reserved for future use")
    private Long shakeTimeMs;

    @Column(length = 256)
    @Schema(description = "Description")
    private String description;

    @Column(length = 64, updatable = false)
    @Schema(description = "Creator ID", accessMode = Schema.AccessMode.READ_ONLY)
    private String creatorId;

    @Column(updatable = false)
    @DefaultValue(generator = Generators.CURRENT_TIME)
    @Schema(description = "Create time", accessMode = Schema.AccessMode.READ_ONLY)
    private Long createTime;

    @Column(name = "creator_name", updatable = false)
    @Schema(description = "Creator name", accessMode = Schema.AccessMode.READ_ONLY)
    private String creatorName;

    @Column(length = 64)
    @Schema(description = "Modifier ID", accessMode = Schema.AccessMode.READ_ONLY)
    private String modifierId;

    @Column
    @DefaultValue(generator = Generators.CURRENT_TIME)
    @Schema(description = "Modify time")
    private Long modifyTime;

    @Column(length = 64)
    @Schema(description = "Modifier name")
    private String modifierName;

    public DeviceSelector getSelector() {
        return selector == null ? DeviceSelector.all : selector;
    }

    public AlarmState getState() {
        return state == null ? AlarmState.enabled : state;
    }

    @Getter
    @Setter
    public static class ConditionGroup {

        @Schema(description = "Condition logic: and or or")
        private NumericLogic logic;

        @Schema(description = "Compatibility alias for logic")
        private NumericLogic type;

        @Schema(description = "Condition terms")
        private List<Condition> terms;

        public NumericLogic getLogic() {
            if (logic != null) {
                return logic;
            }
            return type == null ? NumericLogic.and : type;
        }
    }

    @Getter
    @Setter
    public static class Condition {

        @Schema(description = "Property ID")
        private String property;

        @Schema(description = "Compatibility alias for property")
        private String propertyId;

        @Schema(description = "Property name")
        private String propertyName;

        @Schema(description = "Numeric operator")
        private NumericOperator operator;

        @Schema(description = "Expected value")
        private Object value;

        public String getProperty() {
            return StringUtils.hasText(property) ? property : propertyId;
        }
    }

    public enum DeviceSelector {
        all,
        fixed
    }

    public enum NumericLogic {
        and,
        or
    }

    public enum NumericOperator {
        gt,
        gte,
        lt,
        lte,
        eq,
        neq
    }
}
