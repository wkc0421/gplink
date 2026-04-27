package gp.saas.legacy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SimpleDeviceInstanceEntity {

    @NotNull
    @Schema(description = "device id")
    private String id;

    @NotNull
    @Schema(description = "device name")
    private String name;

    @NotNull
    @Schema(description = "product id")
    private String productId;
}
