package gp.saas.legacy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotNull;

@Getter
@Setter
public class SimpleDeviceProperty {

    @Schema(description = "id")
    private String id;

    @NotNull
    @Schema(description = "property value")
    private Object value;

    @NotNull
    @Schema(description = "data timestamp")
    private Long timestamp;
}
