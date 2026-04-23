package gp.saas.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.Comment;
import org.hswebframework.ezorm.rdb.mapping.annotation.DefaultValue;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.api.crud.entity.RecordCreationEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.hswebframework.web.crud.generator.Generators;

import javax.persistence.Column;
import javax.persistence.Table;

@Getter
@Setter
@Table(name = "saas_tenant")
@Comment("SaaS租户信息表")
@EnableEntityEvent
public class TenantEntity extends GenericEntity<String> implements RecordCreationEntity {

    @Column(length = 64, nullable = false)
    @Schema(description = "租户名称")
    private String name;

    @Column(length = 64, nullable = false, unique = true)
    @Schema(description = "租户编码")
    private String code;

    @Column(length = 16)
    @Schema(description = "状态(enabled/disabled)")
    @DefaultValue("enabled")
    private String state;

    @Column(length = 512)
    @Schema(description = "描述")
    private String description;

    @Column(updatable = false)
    @Schema(description = "创建者ID", accessMode = Schema.AccessMode.READ_ONLY)
    private String creatorId;

    @Column(updatable = false)
    @DefaultValue(generator = Generators.CURRENT_TIME)
    @Schema(description = "创建时间", accessMode = Schema.AccessMode.READ_ONLY)
    private Long createTime;
}
