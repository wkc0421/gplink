package org.jetlinks.community.saas.propertycatalog.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import java.util.List;

@Getter
@Setter
@EnableEntityEvent
@Table(name = "dev_property_library_category", indexes = {
    @Index(name = "idx_prop_category_library", columnList = "library_id"),
    @Index(name = "uidx_prop_category_name", columnList = "library_id,parent_id,name", unique = true)
})
public class PropertyCategoryEntity extends PropertyCatalogRecord {
    @Column(name = "library_id", length = 64, nullable = false) private String libraryId;
    @Column(name = "parent_id", length = 64, nullable = false) private String parentId = "";
    @Column(length = 128, nullable = false) private String name;
    @Column private Integer sortIndex = 0;
    private List<PropertyCategoryEntity> children;
}
