package org.jetlinks.community.standalone.forward;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class DeviceSubscribeRequest {

    @NotEmpty
    @Schema(description = "Device IDs to create one temporary lease for")
    private List<String> deviceIds;

    @NotBlank
    @Schema(description = "Product ID used to subscribe device EventBus topics")
    private String productId;

    @NotBlank
    @Schema(description = "MQTT client network ID in NetworkManager")
    private String mqttNetworkId;

    @Schema(description = "MQTT topic prefix, defaults to IOT/Business")
    private String mqttTopicPrefix;

    @Schema(description = "MQTT QoS, defaults to 0")
    private int mqttQos;

    @Schema(description = "Comma-separated property IDs; blank means all properties")
    private String watchedProperties;

    @Deprecated
    @Schema(description = "Deprecated. Temporary leases always forward property report/read-reply data.")
    private String messageTypes;

    @Deprecated
    @Schema(description = "Deprecated. Temporary leases are active until closed or expired.")
    private String state = "enabled";

    @Deprecated
    @Schema(description = "Deprecated.")
    private String description;
}
