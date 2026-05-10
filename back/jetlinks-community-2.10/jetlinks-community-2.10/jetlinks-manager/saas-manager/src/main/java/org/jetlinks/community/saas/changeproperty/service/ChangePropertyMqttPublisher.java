package org.jetlinks.community.saas.changeproperty.service;

import reactor.core.publisher.Mono;

public interface ChangePropertyMqttPublisher {

    Mono<Void> publish(ChangePropertyMqttPayload payload);
}
