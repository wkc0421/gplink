package org.jetlinks.community.device.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.DefaultValue;
import org.hswebframework.ezorm.rdb.mapping.annotation.EnumCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.generator.Generators;
import org.jetlinks.community.device.enums.LogStatus;
import org.jetlinks.community.device.enums.ScheduledTaskType;

import javax.persistence.Column;
import javax.persistence.GeneratedValue;
import javax.persistence.Index;
import javax.persistence.Table;
import java.sql.JDBCType;

@Getter
@Setter
@Table(name = "dev_scheduled_task_log", indexes = {
    @Index(name = "idx_sched_log_task_id", columnList = "task_id"),
    @Index(name = "idx_sched_log_device_id", columnList = "device_id"),
    @Index(name = "idx_sched_log_time", columnList = "create_time")
})
@Schema(description = "定时任务执行日志")
public class ScheduledTaskLogEntity extends GenericEntity<String> {

    @Override
    @GeneratedValue(generator = Generators.SNOW_FLAKE)
    @Schema(description = "ID")
    public String getId() {
        return super.getId();
    }

    @Column(name = "task_id", length = 64, nullable = false)
    @Schema(description = "任务ID")
    private String taskId;

    @Column(name = "task_name", length = 256)
    @Schema(description = "任务名称")
    private String taskName;

    @Column(name = "device_id", length = 64)
    @Schema(description = "设备ID")
    private String deviceId;

    @Column(name = "device_name", length = 256)
    @Schema(description = "设备名称")
    private String deviceName;

    @Column(name = "task_type", length = 32)
    @EnumCodec
    @ColumnType(jdbcType = JDBCType.VARCHAR)
    @Schema(description = "任务类型")
    private ScheduledTaskType taskType;

    @Column(name = "status", length = 16)
    @EnumCodec
    @ColumnType(jdbcType = JDBCType.VARCHAR)
    @Schema(description = "执行结果: SUCCESS(成功) | FAILED(失败)")
    private LogStatus status;

    @Column(name = "detail")
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @JsonCodec
    @Schema(description = "执行详情(成功时为返回值,失败时为错误信息)")
    private Object detail;

    @Column(name = "create_time", updatable = false)
    @DefaultValue(generator = Generators.CURRENT_TIME)
    @Schema(description = "执行时间")
    private Long createTime;
}
