package org.jetlinks.community.saas.propertycatalog.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

@Getter
@Setter
@EnableEntityEvent
@Table(name = "dev_property_library", indexes = @Index(name = "uidx_prop_library_code", columnList = "code", unique = true))
public class PropertyLibraryEntity extends PropertyCatalogRecord {
    @Column(length = 64, nullable = false) private String code;
    @Column(length = 128, nullable = false) private String name;
    @Column(length = 1024) private String description;
    @Column(nullable = false) private Boolean enabled = true;
    @Column private Integer sortIndex = 0;
    // Stored on the undeletable standard library; never accepted from public writes.
    @JsonIgnore @Column private Integer seedVersion = 0;
}
