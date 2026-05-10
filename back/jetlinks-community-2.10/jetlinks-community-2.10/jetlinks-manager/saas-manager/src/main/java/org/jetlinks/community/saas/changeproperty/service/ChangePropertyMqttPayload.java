package org.jetlinks.community.saas.changeproperty.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChangePropertyMqttPayload {

    private String mqttNetworkId;

    private String topic;

    private int qos;

    private Map<String, Object> payload;
}
