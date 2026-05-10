package org.jetlinks.community.saas.changeproperty.excel;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.reactor.excel.CellDataType;
import org.hswebframework.reactor.excel.ExcelHeader;
import org.hswebframework.web.bean.FastBeanCopier;
import org.jetlinks.community.saas.changeproperty.entity.ChangePropertyConfigEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class ChangePropertyConfigExcelInfo {

    @Schema(description = "Product ID")
    private String productId;

    @Schema(description = "Product name")
    private String productName;

    @Schema(description = "Device ID")
    private String deviceId;

    @Schema(description = "Device name")
    private String deviceName;

    @Schema(description = "Property ID")
    private String propertyId;

    @Schema(description = "Property name")
    private String propertyName;

    @Schema(description = "Enabled")
    private String enabled;

    @Schema(description = "MQTT network ID")
    private String mqttNetworkId;

    @Schema(description = "MQTT topic prefix")
    private String mqttTopicPrefix;

    @Schema(description = "MQTT QoS")
    private String mqttQos;

    @Schema(description = "Remark")
    private String remark;

    public void with(String key, Object value) {
        FastBeanCopier.copy(Map.of(key, value), this);
    }

    public ChangePropertyConfigEntity toEntity() {
        ChangePropertyConfigEntity entity = new ChangePropertyConfigEntity();
        entity.setProductId(trim(productId));
        entity.setProductName(trim(productName));
        entity.setDeviceId(trim(deviceId));
        entity.setDeviceName(trim(deviceName));
        entity.setPropertyId(trim(propertyId));
        entity.setPropertyName(trim(propertyName));
        entity.setEnabled(parseEnabled(enabled));
        entity.setMqttNetworkId(trim(mqttNetworkId));
        entity.setMqttTopicPrefix(trim(mqttTopicPrefix));
        entity.setMqttQos(parseInteger(mqttQos));
        entity.setRemark(trim(remark));
        return entity;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("productId", productId);
        map.put("productName", productName);
        map.put("deviceId", deviceId);
        map.put("deviceName", deviceName);
        map.put("propertyId", propertyId);
        map.put("propertyName", propertyName);
        map.put("enabled", enabled);
        map.put("mqttNetworkId", mqttNetworkId);
        map.put("mqttTopicPrefix", mqttTopicPrefix);
        map.put("mqttQos", mqttQos);
        map.put("remark", remark);
        return map;
    }

    public static ChangePropertyConfigExcelInfo fromEntity(ChangePropertyConfigEntity entity) {
        ChangePropertyConfigExcelInfo info = new ChangePropertyConfigExcelInfo();
        info.setProductId(entity.getProductId());
        info.setProductName(entity.getProductName());
        info.setDeviceId(entity.getDeviceId());
        info.setDeviceName(entity.getDeviceName());
        info.setPropertyId(entity.getPropertyId());
        info.setPropertyName(entity.getPropertyName());
        info.setEnabled(String.valueOf(Boolean.TRUE.equals(entity.getEnabled())));
        info.setMqttNetworkId(entity.getMqttNetworkId());
        info.setMqttTopicPrefix(entity.getMqttTopicPrefix());
        info.setMqttQos(entity.getMqttQos() == null ? null : String.valueOf(entity.getMqttQos()));
        info.setRemark(entity.getRemark());
        return info;
    }

    public static List<ExcelHeader> getTemplateHeaderMapping() {
        return List.of(
            new ExcelHeader("productId", "productId", CellDataType.STRING),
            new ExcelHeader("productName", "productName", CellDataType.STRING),
            new ExcelHeader("deviceId", "deviceId", CellDataType.STRING),
            new ExcelHeader("deviceName", "deviceName", CellDataType.STRING),
            new ExcelHeader("propertyId", "propertyId", CellDataType.STRING),
            new ExcelHeader("propertyName", "propertyName", CellDataType.STRING),
            new ExcelHeader("enabled", "enabled", CellDataType.STRING),
            new ExcelHeader("mqttNetworkId", "mqttNetworkId", CellDataType.STRING),
            new ExcelHeader("mqttTopicPrefix", "mqttTopicPrefix", CellDataType.STRING),
            new ExcelHeader("mqttQos", "mqttQos", CellDataType.STRING),
            new ExcelHeader("remark", "remark", CellDataType.STRING)
        );
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static Boolean parseEnabled(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim().toLowerCase();
        return !("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized));
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Integer.parseInt(value.trim());
    }
}
