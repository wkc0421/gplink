package gp.saas.legacy.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TimerInvokeFunctionEntity {
    @NotNull
    private String name;
    @NotNull
    private String productId;
    @NotNull
    private String deviceIds;
    @NotNull
    private String functionId;
    @NotNull
    private String functionInput;
    @NotNull
    private String cronExpression;
}
