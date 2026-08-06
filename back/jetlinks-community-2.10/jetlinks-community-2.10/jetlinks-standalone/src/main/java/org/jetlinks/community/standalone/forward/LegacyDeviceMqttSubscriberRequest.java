package org.jetlinks.community.standalone.forward;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LegacyDeviceMqttSubscriberRequest {

    @NotBlank
    @Schema(description = "Product ID")
    private String productId;

    @NotBlank
    @Schema(description = "Device ID")
    private String deviceId;

    @Schema(description = "Property IDs. Comma or semicolon separated; blank means all properties.")
    private String properties;

    @Schema(description = "Legacy subscription name")
    private String subscribeName = "GpSaaS_MQTT";

    @Schema(description = "Legacy subscriber")
    private String subscriber = "GpSaaS";

    @Schema(description = "Legacy subscriber type")
    private String subscriberType = "GpSaaS_system";

    @Schema(description = "Exact MQTT topic name. Comma or semicolon separated values create multiple leases.")
    private String topicName = "";
}
