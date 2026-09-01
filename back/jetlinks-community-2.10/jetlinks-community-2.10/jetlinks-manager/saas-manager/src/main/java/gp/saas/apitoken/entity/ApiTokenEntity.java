package gp.saas.apitoken.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.DefaultValue;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.generator.Generators;

import javax.persistence.Column;
import javax.persistence.GeneratedValue;
import javax.persistence.Index;
import javax.persistence.Table;
import java.sql.JDBCType;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Table(name = "api_token", indexes = {
    @Index(name = "idx_api_token_hash", columnList = "token_hash", unique = true),
    @Index(name = "idx_api_token_status", columnList = "status"),
    @Index(name = "idx_api_token_expires", columnList = "expires_at")
})
@Schema(description = "Administrator issued API token")
public class ApiTokenEntity extends GenericEntity<String> {

    @Override
    @GeneratedValue(generator = Generators.SNOW_FLAKE)
    public String getId() {
        return super.getId();
    }

    @Column(name = "name", length = 128, nullable = false)
    private String name;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "token_hash", length = 64, nullable = false)
    private String tokenHash;

    @Column(name = "token_hint", length = 16, nullable = false)
    private String tokenHint;

    @Column(name = "principal_id", length = 128, nullable = false)
    private String principalId;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "expires_at", nullable = false)
    private Long expiresAt;

    @Column(name = "permissions", length = 16000)
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private Map<String, List<String>> permissions;

    @Column(name = "product_ids", length = 16000)
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private List<String> productIds;

    @Column(name = "device_ids", length = 16000)
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private List<String> deviceIds;

    @Column(name = "creator_id", length = 64, updatable = false)
    private String creatorId;

    @Column(name = "creator_name", length = 256, updatable = false)
    private String creatorName;

    @Column(name = "create_time", updatable = false)
    @DefaultValue(generator = Generators.CURRENT_TIME)
    private Long createTime;

    @Column(name = "revoked_at")
    private Long revokedAt;

    @Column(name = "last_used_at")
    private Long lastUsedAt;

    @Column(name = "last_used_ip", length = 64)
    private String lastUsedIp;
}
