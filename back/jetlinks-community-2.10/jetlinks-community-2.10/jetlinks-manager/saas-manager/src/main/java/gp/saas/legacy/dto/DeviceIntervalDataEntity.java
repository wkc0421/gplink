package gp.saas.legacy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
// Legacy consumers use field presence to decide whether a boundary can participate in a delta.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceIntervalDataEntity {

    private String deviceId;

    private String property;

    private Long startTime;

    private Long endTime;

    protected Object startValue;

    protected Object endValue;

    // A delta is available only when both readings exist. Apply the same rule
    // to raw queries, counter snapshots, cache copies and JSON serialization.
    public Object getStartValue() {
        return startValue != null && endValue != null ? startValue : null;
    }

    public Object getEndValue() {
        return startValue != null && endValue != null ? endValue : null;
    }

    public DeviceIntervalDataEntity() {
    }

    public DeviceIntervalDataEntity(String deviceId,
                                    String property,
                                    Long startTime,
                                    Long endTime,
                                    Object startValue,
                                    Object endValue) {
        this.deviceId = deviceId;
        this.property = property;
        this.startTime = startTime;
        this.endTime = endTime;
        this.startValue = startValue;
        this.endValue = endValue;
    }
}
