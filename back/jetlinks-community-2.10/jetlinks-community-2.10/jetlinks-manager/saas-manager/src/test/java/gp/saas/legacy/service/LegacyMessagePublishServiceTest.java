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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        StepVerifier
            .create(service.standardMqttDeviceStatePublisher("product-1", "device-1", "Online", 100L))
            .expectNext(true)
            .verifyComplete();

        ArgumentCaptor<ChangePropertyMqttPayload> captor =
            ArgumentCaptor.forClass(ChangePropertyMqttPayload.class);
        verify(publisher).publish(captor.capture());

        ChangePropertyMqttPayload message = captor.getValue();
        assertEquals("GP_MQTT", message.getMqttNetworkId());
        assertEquals("IOT/Business/product-1/device-1/Data/Online", message.getTopic());
        assertEquals(1, message.getQos());

        Map<String, Object> payload = message.getPayload();
        assertEquals("MQData", payload.get("MsgType"));
        assertEquals("Online", payload.get("Style"));
        assertEquals("GPLink", payload.get("Sender"));
        assertEquals(100L, payload.get("Time"));
        assertEquals("", payload.get("Channel"));
        assertFalse(payload.containsKey("ProductId"));
        assertFalse(payload.containsKey("DeviceId"));

        Object dataObject = payload.get("DataObject");
        assertTrue(dataObject instanceof List);
        assertTrue(((List<?>) dataObject).isEmpty());
    }
}
