package gp.saas.legacy.service;

import org.jetlinks.community.saas.changeproperty.service.ChangePropertyMqttPayload;
import org.jetlinks.community.saas.changeproperty.service.ChangePropertyMqttPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LegacyMessagePublishService {

    private final ChangePropertyMqttPublisher mqttPublisher;

    @Value("${gplink.legacy.mqtt-network-id:${gplink.change-property.mqtt-network-id:}}")
    private String mqttNetworkId;

    @Value("${gplink.legacy.default-qos:0}")
    private int defaultQos;

    @Value("${gplink.legacy.device-state-topic-prefix:GPLink}")
    private String deviceStateTopicPrefix;

    public LegacyMessagePublishService(ChangePropertyMqttPublisher mqttPublisher) {
        this.mqttPublisher = mqttPublisher;
    }

    public Mono<Boolean> standardMqttAlarmPublisher(String productId,
                                                    String deviceId,
                                                    Object alarm,
                                                    Long timestamp) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("Key", "Alarm");
        item.put("Value", alarm);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("MsgType", "MQData");
        payload.put("Style", "Alarm");
        payload.put("Sender", "GPLink");
        payload.put("Time", timestamp == null ? System.currentTimeMillis() : timestamp);
        payload.put("DataObject", List.of(item));

        return publishConfigured(payload, "IOT/" + productId + "/" + deviceId + "/Data/Alarm");
    }

    public Mono<Boolean> standardMqttDeviceStatePublisher(String productId,
                                                          String deviceId,
                                                          String state,
                                                          Long timestamp) {
        if (!StringUtils.hasText(productId)
            || !StringUtils.hasText(deviceId)
            || !StringUtils.hasText(state)) {
            return Mono.just(false);
        }

        String normalizedState = state.trim();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("Key", "Status");
        item.put("Value", normalizedState);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("MsgType", normalizedState);
        payload.put("Style", normalizedState);
        payload.put("Sender", "GPLink");
        payload.put("Time", timestamp == null ? System.currentTimeMillis() : timestamp);
        payload.put("ProductId", productId);
        payload.put("DeviceId", deviceId);
        payload.put("DataObject", List.of(item));

        return publishConfigured(payload, buildDeviceStateTopic(productId, deviceId, normalizedState));
    }

    public Mono<Void> mqttPublisher(Map<String, Object> payload, String topic) {
        return publishConfigured(payload, topic).then();
    }

    private Mono<Boolean> publishConfigured(Map<String, Object> payload, String topic) {
        if (!StringUtils.hasText(mqttNetworkId)) {
            return Mono.just(false);
        }
        return mqttPublisher
            .publish(new ChangePropertyMqttPayload(mqttNetworkId, topic, defaultQos, payload))
            .thenReturn(true);
    }

    private String buildDeviceStateTopic(String productId, String deviceId, String state) {
        String prefix = StringUtils.hasText(deviceStateTopicPrefix)
            ? deviceStateTopicPrefix.trim()
            : "GPLink";
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + "/" + productId + "/" + deviceId + "/" + state;
    }
}
