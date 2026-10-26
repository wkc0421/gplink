package org.jetlinks.community.network.mqtt.gateway.device.session;

import org.jetlinks.community.network.mqtt.client.MqttClient;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.session.DeviceSessionEvent;
import org.jetlinks.core.device.session.DeviceSessionInfo;
import org.jetlinks.core.message.codec.SimpleMqttMessage;
import org.jetlinks.core.server.session.DeviceSession;
import org.jetlinks.supports.device.session.AbstractDeviceSessionManager;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MqttClientSessionTest {

    @Test
    void silentDeviceShouldExpireAfterFifteenMinutesEvenWithBrokerConnected() {
        MqttClient client = mock(MqttClient.class);
        when(client.isAlive()).thenReturn(true);
        MqttClientSession session = session(client);

        session.setLastPingTime(System.currentTimeMillis() - Duration.ofMinutes(14).toMillis());
        assertTrue(session.isAlive());
        session.setLastPingTime(System.currentTimeMillis() - Duration.ofMinutes(16).toMillis());
        assertFalse(session.isAlive());
        assertFalse(session.isAliveAsync().block());
        assertEquals(Duration.ofMinutes(15), session.getKeepAliveTimeout());
    }

    @Test
    void deviceMessageShouldRefreshItsOwnLiveness() {
        MqttClientSession active = session(mock(MqttClient.class));
        MqttClientSession silent = session(mock(MqttClient.class));
        long expired = System.currentTimeMillis() - Duration.ofMinutes(16).toMillis();
        active.setLastPingTime(expired);
        silent.setLastPingTime(expired);

        active.keepAlive();

        assertTrue(active.isAlive());
        assertFalse(silent.isAlive());
    }

    @Test
    void publishingDownstreamShouldNotKeepSilentDeviceOnline() {
        MqttClient client = mock(MqttClient.class);
        when(client.publish(any())).thenReturn(Mono.empty());
        MqttClientSession session = session(client);
        long expired = System.currentTimeMillis() - Duration.ofMinutes(16).toMillis();
        session.setLastPingTime(expired);

        assertTrue(session.send(new SimpleMqttMessage()).block());

        assertEquals(expired, session.lastPingTime());
        assertFalse(session.isAlive());
    }

    @Test
    void periodicSessionCheckShouldUnregisterExpiredDeviceAndAllowItBackOnline() throws Exception {
        MqttClientSession session = session(mock(MqttClient.class));
        DeviceOperator device = session.getOperator();
        when(device.online(anyString(), nullable(String.class), anyLong())).thenReturn(Mono.just(true));
        when(device.offline()).thenReturn(Mono.just(true));
        LocalSessionManager manager = new LocalSessionManager();
        manager.setSessionCheckDelay(Duration.ofMillis(20));
        manager.setSessionCheckInterval(Duration.ofMillis(20));
        CountDownLatch offline = new CountDownLatch(1);
        manager.listenEvent(event -> Mono.fromRunnable(() -> {
            if (event.getType() == DeviceSessionEvent.Type.unregister && !event.isClusterExists()) {
                offline.countDown();
            }
        }));
        manager.init();
        try {
            manager.compute(session.getDeviceId(), Mono.just(session), Mono::just).block();
            session.setLastPingTime(System.currentTimeMillis() - Duration.ofMinutes(16).toMillis());

            assertTrue(offline.await(5, TimeUnit.SECONDS), "session checker did not emit offline event");
            assertEquals(0L, manager.totalSessions(true).block());
            verify(device).offline();

            session.keepAlive();
            manager.compute(session.getDeviceId(), Mono.just(session), Mono::just).block();
            assertEquals(1L, manager.totalSessions(true).block());
            assertTrue(manager.isAlive(session.getDeviceId(), true).block());
        } finally {
            manager.shutdown();
        }
    }

    private MqttClientSession session(MqttClient client) {
        DeviceOperator device = mock(DeviceOperator.class);
        when(device.getDeviceId()).thenReturn("test-device");
        return new MqttClientSession("test-device", device, client, null);
    }

    private static class LocalSessionManager extends AbstractDeviceSessionManager {
        @Override
        public String getCurrentServerId() {
            return "test-server";
        }

        @Override
        protected Mono<Boolean> initSessionConnection(DeviceSession session) {
            return Mono.just(false);
        }

        @Override
        protected Mono<Long> removeRemoteSession(String deviceId) {
            return Mono.just(0L);
        }

        @Override
        protected Mono<Long> getRemoteTotalSessions() {
            return Mono.just(0L);
        }

        @Override
        protected Mono<Boolean> remoteSessionIsAlive(String deviceId) {
            return Mono.just(false);
        }

        @Override
        protected Mono<Boolean> checkRemoteSessionIsAlive(String deviceId) {
            return Mono.just(false);
        }

        @Override
        protected Flux<DeviceSessionInfo> remoteSessions(String serverId) {
            return Flux.empty();
        }
    }
}
