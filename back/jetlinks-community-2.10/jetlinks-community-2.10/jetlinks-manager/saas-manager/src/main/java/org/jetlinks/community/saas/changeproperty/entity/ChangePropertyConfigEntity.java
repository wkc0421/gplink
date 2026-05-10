package org.jetlinks.community.saas.changeproperty.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.DefaultValue;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.api.crud.entity.RecordCreationEntity;
import org.hswebframework.web.api.crud.entity.RecordModifierEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.hswebframework.web.crud.generator.Generators;

import javax.persistence.Column;
import javax.persistence.GeneratedValue;
import javax.persistence.Index;
import javax.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import java.sql.JDBCType;
import java.util.Map;

@Getter
@Setter
@Table(name = "device_change_property_config", indexes = {
    @Index(name = "idx_change_prop_product", columnList = "product_id"),
    @Index(name = "idx_change_prop_device", columnList = "device_id"),
    @Index(name = "idx_change_prop_enabled", columnList = "enabled"),
    @Index(name = "idx_change_prop_unique", columnList = "device_id,property_id", unique = true)
})
@Schema(description = "Change property observation config")
@EnableEntityEvent
public class ChangePropertyConfigEntity extends GenericEntity<String>
    implements RecordCreationEntity, RecordModifierEntity {

    @Override
    @GeneratedValue(generator = Generators.SNOW_FLAKE)
    @Schema(description = "ID")
    public String getId() {
        return super.getId();
    }

    @Column(name = "product_id", length = 64, nullable = false)
    @NotBlank(message = "productId is required")
    @Schema(description = "Product ID")
    private String productId;

    @Column(name = "product_name", length = 256)
    @Schema(description = "Product name")
    private String productName;

    @Column(name = "device_id", length = 64, nullable = false)
    @NotBlank(message = "deviceId is required")
    @Schema(description = "Device ID")
    private String deviceId;

    @Column(name = "device_name", length = 256)
    @Schema(description = "Device name")
    private String deviceName;

    @Column(name = "property_id", length = 128, nullable = false)
    @NotBlank(message = "propertyId is required")
    @Schema(description = "Property ID")
    private String propertyId;

    @Column(name = "property_name", length = 256)
    @Schema(description = "Property name")
    private String propertyName;

    @Column(name = "enabled")
    @DefaultValue("true")
    @Schema(description = "Whether this config is enabled")
    private Boolean enabled;

    @Column(name = "mqtt_network_id", length = 128)
    @Schema(description = "MQTT client network ID")
    private String mqttNetworkId;

    @Column(name = "mqtt_topic_prefix", length = 256)
    @Schema(description = "MQTT topic prefix")
    private String mqttTopicPrefix;

    @Column(name = "mqtt_qos")
    @DefaultValue("0")
    @Schema(description = "MQTT QoS")
    private Integer mqttQos;

    @Column(name = "value_mapping", length = 3000)
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @Schema(description = "Value mapping used by Change MQTT payload")
    private Map<String, String> valueMapping;

    @Column(name = "remark", length = 1024)
    @Schema(description = "Remark")
    private String remark;

    @Column(name = "creator_id", length = 64, updatable = false)
    @Schema(description = "Creator ID")
    private String creatorId;

    @Column(name = "creator_name", length = 256, updatable = false)
    @Schema(description = "Creator name")
    private String creatorName;

    @Column(name = "create_time", updatable = false)
    @DefaultValue(generator = Generators.CURRENT_TIME)
    @Schema(description = "Create time")
    private Long createTime;

    @Column(name = "modifier_id", length = 64)
    @Schema(description = "Modifier ID")
    private String modifierId;

    @Column(name = "modifier_name", length = 256)
    @Schema(description = "Modifier name")
    private String modifierName;

    @Column(name = "modify_time")
    @DefaultValue(generator = Generators.CURRENT_TIME)
    @Schema(description = "Modify time")
    private Long modifyTime;
}
