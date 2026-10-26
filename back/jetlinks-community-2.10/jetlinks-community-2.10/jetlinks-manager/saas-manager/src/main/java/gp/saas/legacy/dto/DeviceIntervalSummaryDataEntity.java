package gp.saas.legacy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The GpLink 1.0 two-point API exposes each available reading independently. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceIntervalSummaryDataEntity extends DeviceIntervalDataEntity {
    public DeviceIntervalSummaryDataEntity(String deviceId, String property, long start, long end,
                                           Object startValue, Object endValue) {
        super(deviceId, property, start, end, startValue, endValue);
    }

    @Override
    public Object getStartValue() {
        return startValue;
    }

    @Override
    public Object getEndValue() {
        return endValue;
    }
}
