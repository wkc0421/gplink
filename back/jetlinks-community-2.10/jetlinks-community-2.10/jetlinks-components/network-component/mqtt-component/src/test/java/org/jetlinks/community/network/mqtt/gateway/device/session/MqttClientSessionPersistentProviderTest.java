package org.jetlinks.community.network.mqtt.gateway.device.session;

import org.jetlinks.community.network.DefaultNetworkType;
import org.jetlinks.community.network.NetworkManager;
import org.jetlinks.community.network.mqtt.client.MqttClient;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MqttClientSessionPersistentProviderTest {

    @Test
    void restoredSessionShouldPreserveTimeoutAndLastDeviceMessageTime() {
        Fixture fixture = new Fixture();
        MqttClientSession original = fixture.session();
        original.setKeepAliveTimeout(Duration.ofMinutes(20));
        original.setLastPingTime(System.currentTimeMillis() - Duration.ofMinutes(16).toMillis());

        MqttClientSession restored = fixture.restore(original);

        assertEquals(Duration.ofMinutes(20), restored.getKeepAliveTimeout());
        assertEquals(original.lastPingTime(), restored.lastPingTime());
        assertEquals(original.connectTime(), restored.connectTime());
        assertEquals("test-gateway", restored.getGatewayId());
        assertTrue(restored.isAlive());
    }

    @Test
    void legacyZeroTimeoutShouldUseFifteenMinutesWithoutRefreshingLastMessageTime() {
        Fixture fixture = new Fixture();
        MqttClientSession legacy = new MqttClientSession("test-device", fixture.device, fixture.client, null) {
            @Override
            public Duration getKeepAliveTimeout() {
                // Before the fix, DeviceSession's default getter always stored zero.
                return Duration.ZERO;
            }
        };
        legacy.setGatewayId("test-gateway");
        legacy.setLastPingTime(System.currentTimeMillis() - Duration.ofMinutes(16).toMillis());

        MqttClientSession restored = fixture.restore(legacy);

        assertEquals(Duration.ofMinutes(15), restored.getKeepAliveTimeout());
        assertEquals(legacy.lastPingTime(), restored.lastPingTime());
        assertFalse(restored.isAlive());
    }

    @Test
    void explicitlyConfiguredUnlimitedTimeoutShouldStillRoundTrip() {
        Fixture fixture = new Fixture();
        MqttClientSession original = fixture.session();
        original.setKeepAliveTimeout(Duration.ofSeconds(-1));
        original.setLastPingTime(1);

        MqttClientSession restored = fixture.restore(original);

        assertEquals(Duration.ofSeconds(-1), restored.getKeepAliveTimeout());
        assertTrue(restored.isAlive());
    }

    private static class Fixture {
        final DeviceOperator device = mock(DeviceOperator.class);
        final MqttClient client = mock(MqttClient.class);
        final DeviceRegistry registry = mock(DeviceRegistry.class);
        final NetworkManager networks = mock(NetworkManager.class);
        final MqttClientSessionPersistentProvider provider = new MqttClientSessionPersistentProvider(networks);

        Fixture() {
            when(device.getDeviceId()).thenReturn("test-device");
            when(client.getId()).thenReturn("test-network");
            when(registry.getDevice("test-device")).thenReturn(Mono.just(device));
            when(networks.<MqttClient>getNetwork(DefaultNetworkType.MQTT_CLIENT, "test-network"))
                .thenReturn(Mono.just(client));
        }

        MqttClientSession session() {
            MqttClientSession session = new MqttClientSession("test-device", device, client, null);
            session.setGatewayId("test-gateway");
            return session;
        }

        MqttClientSession restore(MqttClientSession original) {
            byte[] bytes = provider.serialize(original, registry).block();
            assertNotNull(bytes);
            return (MqttClientSession) provider.deserialize(bytes, registry).block();
        }
    }
}
