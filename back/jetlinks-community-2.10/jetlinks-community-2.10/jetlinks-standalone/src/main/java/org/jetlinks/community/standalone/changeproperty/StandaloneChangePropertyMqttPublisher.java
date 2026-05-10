package org.jetlinks.community.standalone.changeproperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.community.network.DefaultNetworkType;
import org.jetlinks.community.network.NetworkManager;
import org.jetlinks.community.network.mqtt.client.MqttClient;
import org.jetlinks.community.saas.changeproperty.service.ChangePropertyMqttPayload;
import org.jetlinks.community.saas.changeproperty.service.ChangePropertyMqttPublisher;
import org.jetlinks.core.message.codec.SimpleMqttMessage;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class StandaloneChangePropertyMqttPublisher implements ChangePropertyMqttPublisher {

    private final NetworkManager networkManager;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> publish(ChangePropertyMqttPayload payload) {
        if (payload == null
            || !StringUtils.hasText(payload.getMqttNetworkId())
            || !StringUtils.hasText(payload.getTopic())) {
            return Mono.empty();
        }

        return networkManager
            .<MqttClient>getNetwork(DefaultNetworkType.MQTT_CLIENT, payload.getMqttNetworkId())
            .flatMap(client -> Mono
                .fromCallable(() -> {
                    SimpleMqttMessage message = new SimpleMqttMessage();
                    message.setTopic(payload.getTopic());
                    message.setQosLevel(payload.getQos());
                    message.setPayload(Unpooled.copiedBuffer(objectMapper.writeValueAsBytes(payload.getPayload())));
                    return message;
                })
                .flatMap(client::publish));
    }
}
