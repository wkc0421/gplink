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
    @Schema(description = "设备ID列表")
    private List<String> deviceIds;

    @NotBlank
    @Schema(description = "产品ID")
    private String productId;

    @NotBlank
    @Schema(description = "NetworkManager 中 MQTT 客户端网络组件 ID")
    private String mqttNetworkId;

    @Schema(description = "MQTT Topic 前缀，默认 IOT/Business")
    private String mqttTopicPrefix;

    @Schema(description = "MQTT QoS，默认 0")
    private int mqttQos;

    @Schema(description = "消息类型，逗号分隔：PROPERTY_REPORT,PROPERTY_READ_REPLY,PROPERTY_CHANGE,ONLINE,OFFLINE")
    private String messageTypes;

    @Schema(description = "只关注的采集项ID，逗号分隔；为空则转发所有属性")
    private String watchedProperties;

    @Schema(description = "状态：enabled / disabled，默认 enabled")
    private String state = "enabled";

    @Schema(description = "描述")
    private String description;
}
