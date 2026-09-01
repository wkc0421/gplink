package gp.saas.apitoken.dto;

import gp.saas.apitoken.entity.ApiTokenEntity;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
public class ApiTokenResponse {
    private final String id;
    private final String name;
    private final String description;
    private final String status;
    private final Long expiresAt;
    private final String tokenHint;
    private final Map<String, List<String>> permissions;
    private final List<String> productIds;
    private final List<String> deviceIds;
    private final String creatorId;
    private final String creatorName;
    private final Long createTime;
    private final Long revokedAt;
    private final Long lastUsedAt;
    private final String lastUsedIp;
    private final String token;

    private ApiTokenResponse(ApiTokenEntity e, String token) {
        this.id = e.getId(); this.name = e.getName(); this.description = e.getDescription();
        this.status = e.getStatus(); this.expiresAt = e.getExpiresAt(); this.tokenHint = e.getTokenHint();
        this.permissions = e.getPermissions(); this.productIds = e.getProductIds(); this.deviceIds = e.getDeviceIds();
        this.creatorId = e.getCreatorId(); this.creatorName = e.getCreatorName(); this.createTime = e.getCreateTime();
        this.revokedAt = e.getRevokedAt(); this.lastUsedAt = e.getLastUsedAt(); this.lastUsedIp = e.getLastUsedIp();
        this.token = token;
    }

    public static ApiTokenResponse masked(ApiTokenEntity e) { return new ApiTokenResponse(e, null); }
    public static ApiTokenResponse issued(ApiTokenEntity e, String token) { return new ApiTokenResponse(e, token); }
}
