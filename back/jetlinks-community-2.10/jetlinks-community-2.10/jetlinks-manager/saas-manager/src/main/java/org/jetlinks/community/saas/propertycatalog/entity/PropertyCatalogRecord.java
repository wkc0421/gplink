package org.jetlinks.community.saas.propertycatalog.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.api.crud.entity.RecordCreationEntity;
import org.hswebframework.web.api.crud.entity.RecordModifierEntity;
import org.hswebframework.ezorm.rdb.mapping.annotation.DefaultValue;
import org.hswebframework.web.crud.generator.Generators;
import javax.persistence.Column;
import javax.persistence.GeneratedValue;

@Getter
@Setter
public abstract class PropertyCatalogRecord extends GenericEntity<String>
    implements RecordCreationEntity, RecordModifierEntity {
    @Override
    @GeneratedValue(generator = Generators.SNOW_FLAKE)
    public String getId() { return super.getId(); }
    @Column(length = 64, updatable = false) private String creatorId;
    @Column(updatable = false) @DefaultValue(generator = Generators.CURRENT_TIME) private Long createTime;
    @Column(length = 64) private String modifierId;
    @Column @DefaultValue(generator = Generators.CURRENT_TIME) private Long modifyTime;
}
