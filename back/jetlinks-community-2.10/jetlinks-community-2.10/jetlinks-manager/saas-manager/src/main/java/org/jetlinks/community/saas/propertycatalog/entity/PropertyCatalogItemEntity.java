package org.jetlinks.community.saas.propertycatalog.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import java.sql.JDBCType;
import java.util.Map;

@Getter
@Setter
@EnableEntityEvent
@Table(name = "dev_property_library_item", indexes = {
    @Index(name = "uidx_prop_item_code", columnList = "library_id,code", unique = true),
    @Index(name = "idx_prop_item_category", columnList = "library_id,category_id")
})
public class PropertyCatalogItemEntity extends PropertyCatalogRecord {
    @Column(name = "library_id", length = 64, nullable = false) private String libraryId;
    @Column(name = "category_id", length = 64) private String categoryId;
    @Column(length = 64, nullable = false) private String code;
    @Column(length = 128, nullable = false) private String name;
    @Column(nullable = false) private Boolean enabled = true;
    @Column private Integer sortIndex = 0;
    @Column(length = 1024) private String remark;
    @Column @JsonCodec @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private Map<String, Object> source;
}
