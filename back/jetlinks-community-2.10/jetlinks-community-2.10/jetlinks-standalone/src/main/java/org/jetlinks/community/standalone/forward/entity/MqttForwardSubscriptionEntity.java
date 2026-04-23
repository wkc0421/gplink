package org.jetlinks.community.standalone.forward.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.api.crud.entity.RecordCreationEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.hswebframework.web.crud.generator.Generators;

import javax.persistence.Column;
import javax.persistence.GeneratedValue;
import javax.persistence.Table;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Setter
@Table(name = "mqtt_forward_subscriptions")
@EnableEntityEvent
public class MqttForwardSubscriptionEntity extends GenericEntity<String> implements RecordCreationEntity {

    @Override
    @GeneratedValue(generator = Generators.SNOW_FLAKE)
    public String getId() {
        return super.getId();
    }

    @Column(length = 256)
    @Schema(description = "订阅名称")
    private String name;

    @Column(nullable = false, length = 64)
    @NotBlank
    @Schema(description = "产品ID")
    private String productId;

    @Column(length = 64)
    @Schema(description = "设备ID；为空或 * 表示订阅整个产品下所有设备")
    private String deviceId;

    @Column(nullable = false, length = 64)
    @NotBlank
    @Schema(description = "NetworkManager 中 MQTT 客户端网络组件 ID")
    private String mqttNetworkId;

    @Column(length = 256)
    @Schema(description = "MQTT Topic 前缀，如 /forward")
    private String mqttTopicPrefix;

    @Column
    @Schema(description = "MQTT QoS，默认 0")
    private int mqttQos;

    @Column(length = 256)
    @Schema(description = "订阅消息类型，逗号分隔：PROPERTY_REPORT,PROPERTY_READ_REPLY,PROPERTY_CHANGE,ONLINE,OFFLINE")
    private String messageTypes;

    @Column(length = 1024)
    @Schema(description = "只关注的采集项ID，逗号分隔；为空则转发所有属性")
    private String watchedProperties;

    @Column(nullable = false, length = 32)
    @Schema(description = "状态：enabled / disabled")
    private String state = "enabled";

    @Column(length = 512)
    @Schema(description = "描述")
    private String description;

    // RecordCreationEntity
    @Column(updatable = false)
    private Long createTime;

    @Column(updatable = false, length = 64)
    private String creatorId;

    @Column(updatable = false, length = 128)
    private String creatorName;

    public boolean isEnabled() {
        return "enabled".equals(state);
    }

    /** deviceId is null / blank / "*"  →  product-level wildcard subscription */
    public boolean isProductLevel() {
        return deviceId == null || deviceId.isBlank() || "*".equals(deviceId.trim());
    }

    public String getEffectiveDeviceId() {
        return isProductLevel() ? "*" : deviceId.trim();
    }

    public Set<String> getMessageTypeSet() {
        if (messageTypes == null || messageTypes.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(messageTypes.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    }

    /** Empty set means "watch all properties". */
    public Set<String> getWatchedPropertySet() {
        if (watchedProperties == null || watchedProperties.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(watchedProperties.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    }
}
