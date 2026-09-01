package gp.saas.apitoken.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.DefaultValue;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.generator.Generators;

import javax.persistence.Column;
import javax.persistence.GeneratedValue;
import javax.persistence.Index;
import javax.persistence.Table;

@Getter
@Setter
@Table(name = "api_token_audit", indexes = {
    @Index(name = "idx_api_token_audit_token", columnList = "token_id,create_time")
})
@Schema(description = "API token lifecycle audit")
public class ApiTokenAuditEntity extends GenericEntity<String> {

    @Override
    @GeneratedValue(generator = Generators.SNOW_FLAKE)
    public String getId() {
        return super.getId();
    }

    @Column(name = "token_id", length = 64, nullable = false)
    private String tokenId;

    @Column(name = "event_type", length = 32, nullable = false)
    private String eventType;

    @Column(name = "operator_id", length = 64)
    private String operatorId;

    @Column(name = "operator_name", length = 256)
    private String operatorName;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "detail", length = 4000)
    private String detail;

    @Column(name = "create_time", updatable = false)
    @DefaultValue(generator = Generators.CURRENT_TIME)
    private Long createTime;
}
