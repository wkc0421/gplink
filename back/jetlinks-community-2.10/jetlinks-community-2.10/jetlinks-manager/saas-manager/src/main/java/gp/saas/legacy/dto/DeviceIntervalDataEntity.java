package gp.saas.legacy.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeviceIntervalDataEntity {

    private String deviceId;

    private String property;

    private Long startTime;

    private Long endTime;

    private Object startValue;

    private Object endValue;

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
