package gp.saas.legacy.service;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class LegacyMessagePublishService {

    public Mono<Void> mqttPublisher(Map<String, Object> payload, String topic) {
        return Mono.empty();
    }
}
