package org.jetlinks.community.network.mqtt.gateway.device;

import org.jetlinks.community.gateway.supports.DeviceGatewayProperties;
import org.jetlinks.community.network.DefaultNetworkType;
import org.jetlinks.community.network.NetworkManager;
import org.jetlinks.community.network.mqtt.client.MqttClient;
import org.jetlinks.core.ProtocolSupport;
import org.jetlinks.core.ProtocolSupports;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.device.session.DeviceSessionManager;
import org.jetlinks.core.message.Headers;
import org.jetlinks.core.message.codec.DefaultTransport;
import org.jetlinks.core.message.codec.DeviceMessageCodec;
import org.jetlinks.core.message.codec.MqttMessage;
import org.jetlinks.core.message.codec.SimpleMqttMessage;
import org.jetlinks.core.message.property.ReportPropertyMessage;
import org.jetlinks.core.route.MqttRoute;
import org.jetlinks.core.server.session.DeviceSession;
import org.jetlinks.supports.server.DecodedClientMessageHandler;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MqttClientDeviceGatewayTest {

    @Test
    void firstUpstreamMessageShouldCreateSessionWithFifteenMinuteTimeout() throws Exception {
        Fixture fixture = new Fixture();
        MqttClientDeviceGateway gateway = fixture.create();
        try {
            fixture.report();
            assertEquals(Duration.ofMinutes(15), fixture.session.get().getKeepAliveTimeout());
        } finally {
            gateway.shutdown().block();
        }
    }

    @Test
    void configuredTimeoutShouldApplyOnCreationAndAfterGatewayReload() throws Exception {
        Fixture fixture = new Fixture();
        fixture.properties.getConfiguration().put("keepAliveTimeout", 1200);
        MqttClientDeviceGateway gateway = fixture.create();
        try {
            fixture.report();
            DeviceSession original = fixture.session.get();
            assertEquals(Duration.ofMinutes(20), original.getKeepAliveTimeout());

            fixture.properties.getConfiguration().put("keepAliveTimeout", "900");
            fixture.provider.reloadDeviceGateway(gateway, fixture.properties).block();
            fixture.report();

            assertSame(original, fixture.session.get());
            assertEquals(Duration.ofMinutes(15), original.getKeepAliveTimeout());
        } finally {
            gateway.shutdown().block();
        }
    }

    @Test
    void protocolTimeoutShouldApplyToFirstMessageAndLaterMessages() throws Exception {
        Fixture fixture = new Fixture();
        fixture.message.addHeader(Headers.keepOnlineTimeoutSeconds, 1800);
        MqttClientDeviceGateway gateway = fixture.create();
        try {
            fixture.report();
            assertEquals(Duration.ofMinutes(30), fixture.session.get().getKeepAliveTimeout());

            fixture.message.addHeader(Headers.keepOnlineTimeoutSeconds, 600);
            fixture.report();
            assertEquals(Duration.ofMinutes(10), fixture.session.get().getKeepAliveTimeout());
        } finally {
            gateway.shutdown().block();
        }
    }

    @Test
    void gatewayShouldRejectNonPositiveConfiguredTimeout() {
        Fixture fixture = new Fixture();
        fixture.properties.getConfiguration().put("keepAliveTimeout", 0);
        assertThrows(IllegalArgumentException.class,
                     () -> fixture.provider.createDeviceGateway(fixture.properties).block());
    }

    private static class Fixture {
        final DeviceRegistry registry = mock(DeviceRegistry.class);
        final DeviceSessionManager sessions = mock(DeviceSessionManager.class);
        final MqttClient client = mock(MqttClient.class);
        final NetworkManager networks = mock(NetworkManager.class);
        final ProtocolSupports protocols = mock(ProtocolSupports.class);
        final ProtocolSupport protocol = mock(ProtocolSupport.class);
        final DeviceMessageCodec codec = mock(DeviceMessageCodec.class);
        final DeviceOperator device = mock(DeviceOperator.class);
        final DecodedClientMessageHandler handler = mock(DecodedClientMessageHandler.class);
        final AtomicReference<DeviceSession> session = new AtomicReference<>();
        final Sinks.Many<MqttMessage> upstream = Sinks.many().unicast().onBackpressureBuffer();
        final Semaphore handled = new Semaphore(0);
        final ReportPropertyMessage message = new ReportPropertyMessage();
        final DeviceGatewayProperties properties = new DeviceGatewayProperties();
        final MqttClientDeviceGatewayProvider provider =
            new MqttClientDeviceGatewayProvider(networks, registry, sessions, handler, protocols);

        @SuppressWarnings("unchecked")
        Fixture() {
            message.setDeviceId("test-device");
            message.setProperties(Collections.singletonMap("temperature", 20));
            properties.setId("test-gateway");
            properties.setChannelId("test-network");
            properties.setProtocol("test-protocol");

            MqttRoute route = mock(MqttRoute.class);
            when(route.isUpstream()).thenReturn(true);
            when(route.getTopic()).thenReturn("/test/#");
            when(client.getId()).thenReturn("test-network");
            when(client.subscribe(Collections.singletonList("/test/#"), 0)).thenReturn(upstream.asFlux());
            when(networks.<MqttClient>getNetwork(DefaultNetworkType.MQTT_CLIENT, "test-network"))
                .thenReturn(Mono.just(client));
            when(protocols.getProtocol("test-protocol")).thenReturn(Mono.just(protocol));
            when(protocol.getRoutes(DefaultTransport.MQTT)).thenReturn(Flux.just(route));
            when(protocol.getMessageCodec(DefaultTransport.MQTT)).thenAnswer(ignore -> Mono.just(codec));
            when(codec.decode(any())).thenAnswer(ignore -> Flux.just(message));
            when(device.getDeviceId()).thenReturn("test-device");
            when(registry.getDevice("test-device")).thenReturn(Mono.just(device));
            when(sessions.getSession("test-device", false))
                .thenReturn(Mono.defer(() -> Mono.justOrEmpty(session.get())));
            when(sessions.compute(eq("test-device"), any(Mono.class), any()))
                .thenAnswer(invocation -> {
                    Mono<DeviceSession> created = invocation.getArgument(1);
                    Function<DeviceSession, Mono<DeviceSession>> update = invocation.getArgument(2);
                    return (session.get() == null ? created : update.apply(session.get()))
                        .doOnNext(session::set);
                });
            when(handler.handleMessage(any(), any()))
                .thenAnswer(ignore -> Mono.fromRunnable(handled::release));
        }

        MqttClientDeviceGateway create() {
            MqttClientDeviceGateway gateway = (MqttClientDeviceGateway) provider.createDeviceGateway(properties).block();
            gateway.startup().block();
            return gateway;
        }

        void report() throws InterruptedException {
            assertEquals(Sinks.EmitResult.OK, upstream.tryEmitNext(new SimpleMqttMessage()));
            assertTrue(handled.tryAcquire(5, TimeUnit.SECONDS), "upstream message was not handled");
        }
    }
}
