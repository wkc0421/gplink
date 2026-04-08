package org.jetlinks.community.device.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.DefaultValue;
import org.hswebframework.ezorm.rdb.mapping.annotation.EnumCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.api.crud.entity.RecordCreationEntity;
import org.hswebframework.web.api.crud.entity.RecordModifierEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.hswebframework.web.crud.generator.Generators;
import org.jetlinks.community.TimerSpec;
import org.jetlinks.community.device.enums.ExecutionMode;
import org.jetlinks.community.device.enums.ScheduledTaskState;
import org.jetlinks.community.device.enums.ScheduledTaskType;

import javax.persistence.Column;
import javax.persistence.GeneratedValue;
import javax.persistence.Index;
import javax.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import org.hswebframework.web.validator.CreateGroup;
import java.sql.JDBCType;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Table(name = "dev_scheduled_task", indexes = {
    @Index(name = "idx_sched_task_product", columnList = "product_id"),
    @Index(name = "idx_sched_task_state", columnList = "state")
})
@Schema(description = "定时任务")
@EnableEntityEvent
public class ScheduledTaskEntity extends GenericEntity<String> implements RecordCreationEntity, RecordModifierEntity {

    @Override
    @GeneratedValue(generator = Generators.SNOW_FLAKE)
    @Schema(description = "ID")
    public String getId() {
        return super.getId();
    }

    @Column(name = "name", length = 256, nullable = false)
    @NotBlank(message = "任务名称不能为空", groups = CreateGroup.class)
    @Schema(description = "任务名称")
    private String name;

    @Column(name = "product_id", length = 64, nullable = false)
    @NotBlank(message = "产品ID不能为空", groups = CreateGroup.class)
    @Schema(description = "产品ID")
    private String productId;

    @Column(name = "product_name", length = 256)
    @Schema(description = "产品名称")
    private String productName;

    @Column(name = "device_ids")
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @JsonCodec
    @Schema(description = "设备ID列表,为空则代表产品下全部设备")
    private List<String> deviceIds;

    @Column(name = "task_type", length = 32, nullable = false)
    @EnumCodec
    @ColumnType(jdbcType = JDBCType.VARCHAR)
    @Schema(description = "任务类型: READ_PROPERTY(读取属性) | INVOKE_FUNCTION(调用功能)")
    private ScheduledTaskType taskType;

    @Column(name = "properties")
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @JsonCodec
    @Schema(description = "属性ID列表(任务类型为READ_PROPERTY时使用)")
    private List<String> properties;

    @Column(name = "function_id", length = 64)
    @Schema(description = "功能ID(任务类型为INVOKE_FUNCTION时使用)")
    private String functionId;

    @Column(name = "function_params")
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @JsonCodec
    @Schema(description = "功能调用参数(任务类型为INVOKE_FUNCTION时使用)")
    private Map<String, Object> functionParams;

    @Column(name = "timer_spec", nullable = false)
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @JsonCodec
    @Schema(description = "定时规则")
    private TimerSpec timerSpec;

    @Column(name = "execution_mode", length = 16)
    @EnumCodec
    @ColumnType(jdbcType = JDBCType.VARCHAR)
    @Schema(description = "执行模式: PARALLEL(并行) | SERIAL(串行)")
    private ExecutionMode executionMode;

    @Column(name = "serial_interval_ms")
    @Schema(description = "串行模式下设备间执行间隔(毫秒)")
    private Long serialIntervalMs;

    @Column(name = "property_interval_ms")
    @Schema(description = "读取属性时每个属性间的间隔(毫秒),默认500")
    private Long propertyIntervalMs;

    @Column(name = "state", length = 16)
    @EnumCodec
    @ColumnType(jdbcType = JDBCType.VARCHAR)
    @DefaultValue("disabled")
    @Schema(description = "状态: enabled(已启用) | disabled(已禁用)")
    private ScheduledTaskState state;

    @Column(name = "log_enabled")
    @Schema(description = "是否开启执行日志")
    private Boolean logEnabled;

    @Column(name = "creator_id", length = 64, updatable = false)
    @Schema(description = "创建人ID")
    private String creatorId;

    @Column(name = "creator_name", length = 256, updatable = false)
    @Schema(description = "创建人名称")
    private String creatorName;

    @Column(name = "create_time", updatable = false)
    @DefaultValue(generator = Generators.CURRENT_TIME)
    @Schema(description = "创建时间")
    private Long createTime;

    @Column(name = "modifier_id", length = 64)
    @Schema(description = "最后修改人ID")
    private String modifierId;

    @Column(name = "modifier_name", length = 256)
    @Schema(description = "最后修改人名称")
    private String modifierName;

    @Column(name = "modify_time")
    @DefaultValue(generator = Generators.CURRENT_TIME)
    @Schema(description = "最后修改时间")
    private Long modifyTime;
}
