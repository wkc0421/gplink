package org.jetlinks.community.standalone.forward;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class MqttForwardLeaseResponse {

    @Schema(description = "Temporary subscription lease ID")
    private String leaseId;

    @Schema(description = "Product ID used to subscribe device EventBus topics")
    private String productId;

    @Schema(description = "MQTT client network ID in NetworkManager")
    private String mqttNetworkId;

    @Schema(description = "MQTT topic prefix")
    private String mqttTopicPrefix;

    @Schema(description = "MQTT QoS")
    private int mqttQos;

    @Schema(description = "Lease creation time, epoch milliseconds")
    private long createdAt;

    @Schema(description = "Lease expiration time, epoch milliseconds")
    private long expiresAt;

    @Schema(description = "Lease TTL in seconds")
    private long ttlSeconds;

    @Schema(description = "Subscribed device IDs")
    private List<String> deviceIds;

    @Schema(description = "Subscribed property IDs; empty means all properties")
    private List<String> watchedProperties;

    @Schema(description = "Successful MQTT publish count for this lease")
    private long forwardCount;

    @Schema(description = "Last successful forward time, epoch milliseconds")
    private long lastForwardTime;

    @Schema(description = "Last forwarded device ID")
    private String lastForwardDeviceId;

    @Schema(description = "Last forwarded property IDs")
    private List<String> lastForwardProperties;
}
