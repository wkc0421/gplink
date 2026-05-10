package org.jetlinks.community.saas.changeproperty.excel;

import org.hswebframework.reactor.excel.Cell;
import org.hswebframework.reactor.excel.converter.RowWrapper;

import java.util.HashMap;
import java.util.Map;

public class ChangePropertyConfigWrapper extends RowWrapper<ChangePropertyConfigExcelInfo> {

    private final Map<String, String> mapping = new HashMap<>();

    public ChangePropertyConfigWrapper() {
        add("productId", "productId");
        add("productName", "productName");
        add("deviceId", "deviceId");
        add("deviceName", "deviceName");
        add("propertyId", "propertyId");
        add("propertyName", "propertyName");
        add("enabled", "enabled");
        add("mqttNetworkId", "mqttNetworkId");
        add("mqttTopicPrefix", "mqttTopicPrefix");
        add("mqttQos", "mqttQos");
        add("remark", "remark");
    }

    private void add(String header, String property) {
        mapping.put(header, property);
    }

    @Override
    protected ChangePropertyConfigExcelInfo newInstance() {
        return new ChangePropertyConfigExcelInfo();
    }

    @Override
    protected ChangePropertyConfigExcelInfo wrap(ChangePropertyConfigExcelInfo instance, Cell header, Cell dataCell) {
        String property = mapping.get(header.valueAsText().orElse(""));
        if (property != null) {
            instance.with(property, dataCell.valueAsText().orElse(""));
        }
        return instance;
    }
}
