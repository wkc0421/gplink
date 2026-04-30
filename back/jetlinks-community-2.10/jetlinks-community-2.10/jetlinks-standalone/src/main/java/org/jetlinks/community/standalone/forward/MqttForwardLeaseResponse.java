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

    @Schema(description = "Lease expiration time, epoch milliseconds")
    private long expiresAt;

    @Schema(description = "Lease TTL in seconds")
    private long ttlSeconds;

    @Schema(description = "Subscribed device IDs")
    private List<String> deviceIds;

    @Schema(description = "Subscribed property IDs; empty means all properties")
    private List<String> watchedProperties;
}
