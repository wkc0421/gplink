package org.jetlinks.community.standalone.forward;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetlinks.community.network.NetworkManager;
import org.jetlinks.core.event.EventBus;
import org.jetlinks.core.event.Subscription;
import org.jetlinks.core.message.DeviceMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MqttForwardSubscriptionServiceTest {

    @Test
    void shouldRejectOversizedDeviceList() {
        MqttForwardSubscriptionService service = service(Flux.never());
        DeviceSubscribeRequest request = request();
        request.setDeviceIds(new ArrayList<>(java.util.Collections.nCopies(1_001, "device")));

        StepVerifier
            .create(service.createByDevices(request))
            .expectErrorMatches(error -> error instanceof ResponseStatusException response
                && response.getStatusCode() == HttpStatus.BAD_REQUEST)
            .verify();
    }

    @Test
    void shouldReleaseIndexesAndDeviceSubscriptionWhenLeaseCloses() {
        MqttForwardSubscriptionService service = service(Flux.never());

        MqttForwardLeaseResponse lease = service.createByDevices(request()).block();
        Map<String, Object> active = service.getActiveSubscriptions();
        assertEquals(1, active.get("leaseCount"));
        assertEquals(1, active.get("deviceSubscriptionCount"));

        service.closeLease(lease.getLeaseId()).block();

        Map<String, Object> closed = service.getActiveSubscriptions();
        assertEquals(0, closed.get("leaseCount"));
        assertEquals(0, closed.get("deviceSubscriptionCount"));
        assertEquals(0, closed.get("indexLeaseRefCount"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private MqttForwardSubscriptionService service(Flux<DeviceMessage> messages) {
        EventBus eventBus = mock(EventBus.class);
        when(eventBus.subscribe(any(Subscription.class), eq(DeviceMessage.class))).thenReturn((Flux) messages);
        return new MqttForwardSubscriptionService(
            eventBus,
            mock(NetworkManager.class),
            new ObjectMapper()
        );
    }

    private DeviceSubscribeRequest request() {
        DeviceSubscribeRequest request = new DeviceSubscribeRequest();
        request.setProductId("product");
        request.setDeviceIds(List.of("device"));
        request.setWatchedProperties("temperature");
        request.setMqttNetworkId("mqtt-network");
        return request;
    }
}
