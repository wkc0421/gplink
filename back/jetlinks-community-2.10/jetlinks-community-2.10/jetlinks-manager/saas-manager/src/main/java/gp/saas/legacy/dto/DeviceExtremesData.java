package gp.saas.legacy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jetlinks.community.things.data.PropertyExtremes;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "设备属性极值；时间为最早命中极值的原始采样毫秒时间戳")
public record DeviceExtremesData(String deviceId, String property, Status status,
                                Double minValue, Long minTime, Double maxValue, Long maxTime) {
    public enum Status { OK, NO_DATA, UNKNOWN_PROPERTY, NON_NUMERIC, UNSUPPORTED_STORAGE }

    public static DeviceExtremesData empty(PropertyExtremes.Target target, Status status) {
        return new DeviceExtremesData(target.thingId(), target.property(), status, null, null, null, null);
    }

    public static DeviceExtremesData of(PropertyExtremes result) {
        return new DeviceExtremesData(result.thingId(), result.property(), Status.valueOf(result.status().name()),
            result.minValue(), result.minTime(), result.maxValue(), result.maxTime());
    }
}
