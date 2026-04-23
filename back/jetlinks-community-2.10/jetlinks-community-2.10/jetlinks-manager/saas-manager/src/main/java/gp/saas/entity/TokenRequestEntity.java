package gp.saas.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotNull;

@Getter
@Setter
public class TokenRequestEntity {

    @NotNull
    @Schema(description = "用户名")
    private String user;

    @NotNull
    @Schema(description = "密码")
    private String password;
}
