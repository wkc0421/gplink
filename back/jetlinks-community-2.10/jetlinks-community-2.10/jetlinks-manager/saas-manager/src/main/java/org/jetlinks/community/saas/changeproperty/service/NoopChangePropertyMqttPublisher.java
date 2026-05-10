package org.jetlinks.community.saas.changeproperty.service;

import reactor.core.publisher.Mono;

public class NoopChangePropertyMqttPublisher implements ChangePropertyMqttPublisher {

    @Override
    public Mono<Void> publish(ChangePropertyMqttPayload payload) {
        return Mono.empty();
    }
}
