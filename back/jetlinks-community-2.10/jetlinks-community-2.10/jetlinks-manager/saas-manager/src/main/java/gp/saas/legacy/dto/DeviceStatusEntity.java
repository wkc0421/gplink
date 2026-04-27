package gp.saas.legacy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeviceStatusEntity {

    @Schema(description = "last message time")
    private Long lastMessageTime;

    @Schema(description = "online flag")
    private Boolean isOnline;

    @Schema(description = "device status")
    private String status;
}
