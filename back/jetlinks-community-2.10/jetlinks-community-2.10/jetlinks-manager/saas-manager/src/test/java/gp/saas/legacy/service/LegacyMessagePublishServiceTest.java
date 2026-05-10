package gp.saas.legacy.service;

import org.junit.jupiter.api.Test;
import org.jetlinks.community.saas.changeproperty.service.ChangePropertyMqttPayload;
import org.jetlinks.community.saas.changeproperty.service.ChangePropertyMqttPublisher;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyMessagePublishServiceTest {

    @Test
    void shouldPublishStandardDeviceStatePayload() {
        ChangePropertyMqttPublisher publisher = mock(ChangePropertyMqttPublisher.class);
        when(publisher.publish(any())).thenReturn(Mono.empty());

        LegacyMessagePublishService service = new LegacyMessagePublishService(publisher);
        ReflectionTestUtils.setField(service, "mqttNetworkId", "GP_MQTT");
        ReflectionTestUtils.setField(service, "defaultQos", 1);
        ReflectionTestUtils.setField(service, "deviceStateTopicPrefix", "GPLink/");

        StepVerifier
            .create(service.standardMqttDeviceStatePublisher("product-1", "device-1", "Online", 100L))
            .expectNext(true)
            .verifyComplete();

        ArgumentCaptor<ChangePropertyMqttPayload> captor =
            ArgumentCaptor.forClass(ChangePropertyMqttPayload.class);
        verify(publisher).publish(captor.capture());

        ChangePropertyMqttPayload message = captor.getValue();
        assertEquals("GP_MQTT", message.getMqttNetworkId());
        assertEquals("GPLink/product-1/device-1/Online", message.getTopic());
        assertEquals(1, message.getQos());

        Map<String, Object> payload = message.getPayload();
        assertEquals("Online", payload.get("MsgType"));
        assertEquals("Online", payload.get("Style"));
        assertEquals("GPLink", payload.get("Sender"));
        assertEquals(100L, payload.get("Time"));
        assertEquals("product-1", payload.get("ProductId"));
        assertEquals("device-1", payload.get("DeviceId"));

        Object dataObject = payload.get("DataObject");
        assertTrue(dataObject instanceof List);
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) ((List<?>) dataObject).get(0);
        assertEquals("Status", item.get("Key"));
        assertEquals("Online", item.get("Value"));
    }
}
