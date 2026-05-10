package gp.saas.legacy.service;

import org.junit.jupiter.api.Test;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceStateInfo;
import org.jetlinks.community.device.enums.DeviceState;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.core.message.DeviceOfflineMessage;
import org.jetlinks.core.message.DeviceOnlineMessage;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyDeviceStatePushServiceTest {

    private final LocalDeviceInstanceService deviceService = mock(LocalDeviceInstanceService.class);
    private final LegacyMessagePublishService messagePublishService = mock(LegacyMessagePublishService.class);
    private final LegacyDeviceStatePushService service =
        new LegacyDeviceStatePushService(deviceService, messagePublishService);

    @Test
    void shouldPublishOnlineMessageForNonDisabledDevice() {
        DeviceInstanceEntity device = device("product-1", "device-1", DeviceState.offline);
        when(deviceService.findById("device-1")).thenReturn(Mono.just(device));
        when(messagePublishService.standardMqttDeviceStatePublisher(
            "product-1", "device-1", "Online", 100L))
            .thenReturn(Mono.just(true));

        DeviceOnlineMessage message = new DeviceOnlineMessage();
        message.setDeviceId("device-1");
        message.setTimestamp(100L);

        StepVerifier
            .create(service.onlineOfflineMessageHandler(message))
            .verifyComplete();

        verify(messagePublishService).standardMqttDeviceStatePublisher(
            "product-1", "device-1", "Online", 100L);
    }

    @Test
    void shouldSkipDisabledDevice() {
        DeviceInstanceEntity device = device("product-1", "device-1", DeviceState.notActive);
        when(deviceService.findById("device-1")).thenReturn(Mono.just(device));

        DeviceOfflineMessage message = new DeviceOfflineMessage();
        message.setDeviceId("device-1");
        message.setTimestamp(100L);

        StepVerifier
            .create(service.onlineOfflineMessageHandler(message))
            .verifyComplete();

        verify(messagePublishService, never())
            .standardMqttDeviceStatePublisher(any(), any(), eq("Offline"), any());
    }

    @Test
    void shouldSkipRealtimePushWhenDisabledByConfig() {
        ReflectionTestUtils.setField(service, "enabled", false);

        DeviceOnlineMessage message = new DeviceOnlineMessage();
        message.setDeviceId("device-1");

        StepVerifier
            .create(service.onlineOfflineMessageHandler(message))
            .verifyComplete();

        verify(deviceService, never()).findById(any(String.class));
        verify(messagePublishService, never())
            .standardMqttDeviceStatePublisher(any(), any(), any(), any());
    }

    @Test
    void shouldNotCountRefreshChangeWhenMqttIsNotPublished() {
        DeviceInstanceEntity previous = device("product-1", "device-1", DeviceState.offline);
        when(messagePublishService.standardMqttDeviceStatePublisher(
            eq("product-1"), eq("device-1"), eq("Online"), any(Long.class)))
            .thenReturn(Mono.just(false));

        @SuppressWarnings("unchecked")
        Mono<Integer> result = (Mono<Integer>) ReflectionTestUtils.invokeMethod(
            service,
            "publishIfChanged",
            previous,
            DeviceStateInfo.of("device-1", DeviceState.online));

        StepVerifier
            .create(result)
            .expectNext(0)
            .verifyComplete();
    }

    private static DeviceInstanceEntity device(String productId, String deviceId, DeviceState state) {
        DeviceInstanceEntity device = new DeviceInstanceEntity();
        device.setProductId(productId);
        device.setId(deviceId);
        device.setState(state);
        return device;
    }
}
