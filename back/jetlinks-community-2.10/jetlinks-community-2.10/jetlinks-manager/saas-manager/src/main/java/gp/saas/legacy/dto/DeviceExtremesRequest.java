package gp.saas.legacy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Schema(description = "批量查询原始采样极值，时间范围为闭区间")
public class DeviceExtremesRequest {
    @Schema(description = "设备 ID，最多 500 个，不能为空")
    private List<String> devices;
    @Schema(description = "属性 ID，最多 100 个，不能为空")
    private List<String> properties;
    @Schema(description = "开始时间，Unix 毫秒时间戳，包含该时刻")
    private Long startTime;
    @Schema(description = "结束时间，Unix 毫秒时间戳，包含该时刻，必须晚于开始时间")
    private Long endTime;
}
