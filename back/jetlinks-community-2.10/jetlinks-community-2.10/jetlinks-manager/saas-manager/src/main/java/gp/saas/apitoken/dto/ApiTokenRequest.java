package gp.saas.apitoken.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class ApiTokenRequest {
    @NotBlank
    private String name;
    private String description;
    private Long expiresAt;
    private Map<String, List<String>> permissions;
    private List<String> productIds;
    private List<String> deviceIds;
}
