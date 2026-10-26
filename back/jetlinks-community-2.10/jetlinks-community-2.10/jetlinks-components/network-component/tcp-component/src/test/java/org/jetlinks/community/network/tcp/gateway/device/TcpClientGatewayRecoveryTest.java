package org.jetlinks.community.network.tcp.gateway.device;

import org.jetlinks.community.network.tcp.client.TcpClient;
import org.jetlinks.community.PropertyConstants;
import java.util.function.Predicate;
import org.jetlinks.core.ProtocolSupport;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.device.session.DeviceSessionManager;
import org.jetlinks.core.message.codec.DefaultTransport;
import org.jetlinks.core.server.session.DeviceSession;
import org.jetlinks.supports.server.DecodedClientMessageHandler;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TcpClientGatewayRecoveryTest {

    @Test
    void shutdownRemovesOwnedSessionAndNetworkReconnectCannotReviveIt() {
        Fixture fixture = new Fixture();
        fixture.active.set(true);
        fixture.gateway.startup().block();
        fixture.connection(true);
        DeviceSession session = fixture.sessions.get("collector");
        assertNotNull(session);
        fixture.gateway.shutdown().block();
        fixture.connection(false);
        fixture.connection(true);
        assertFalse(session.isAlive());
        assertTrue(fixture.sessions.isEmpty());
        assertEquals(1, fixture.bindings.get());
    }

    @Test
    void disabledDeviceShouldNotBeRegisteredByReconciliation() {
        Fixture fixture = new Fixture();
        try {
            StepVerifier.withVirtualTime(() -> Mono.fromRunnable(() -> {
                    fixture.gateway.startup().block();
                    fixture.connection(true);
                }).then(Mono.delay(Duration.ofSeconds(16))))
                .thenAwait(Duration.ofSeconds(16))
                .assertNext(ignore -> {
                    assertTrue(fixture.sessions.isEmpty());
                    assertEquals(0, fixture.bindings.get());
                    verify(fixture.tcpClient, never()).keepAlive();
                })
                .expectComplete().verify(Duration.ofSeconds(10));
        } finally {
            fixture.gateway.shutdown().block();
        }
    }

    @Test
    void enablingDeviceAfterTcpConnectedShouldRecoverWithoutReconnect() {
        Fixture fixture = new Fixture();
        try {
            StepVerifier.withVirtualTime(() -> Mono.fromRunnable(() -> {
                    fixture.gateway.startup().block();
                    fixture.connection(true);
                    assertTrue(fixture.sessions.isEmpty());
                    fixture.active.set(true);
                }).then(Mono.delay(Duration.ofSeconds(6))))
                .thenAwait(Duration.ofSeconds(6))
                .assertNext(ignore -> {
                    assertNotNull(fixture.sessions.get("collector"));
                    assertEquals(1, fixture.bindings.get());
                    assertEquals(1, fixture.gateway.totalConnection());
                    verify(fixture.tcpClient, never()).shutdown();
                })
                .expectComplete().verify(Duration.ofSeconds(10));
        } finally {
            fixture.gateway.shutdown().block();
        }
    }

    @Test
    void healthySessionShouldNotBeRepeatedlyRegisteredOrKeptAliveByReconciliation() {
        Fixture fixture = new Fixture();
        fixture.active.set(true);
        try {
            StepVerifier.withVirtualTime(() -> Mono.fromRunnable(() -> {
                    fixture.gateway.startup().block();
                    fixture.connection(true);
                    assertEquals(1, fixture.bindings.get());
                }).then(Mono.delay(Duration.ofSeconds(16))))
                .thenAwait(Duration.ofSeconds(16))
                .assertNext(ignore -> {
                    assertEquals(1, fixture.bindings.get());
                    verify(fixture.tcpClient, never()).keepAlive();
                })
                .expectComplete().verify(Duration.ofSeconds(10));
        } finally {
            fixture.gateway.shutdown().block();
        }
    }

    @Test
    void transientRegistrationFailureShouldRetryButDisconnectAndShutdownShouldStopRetrying() {
        Fixture fixture = new Fixture();
        fixture.active.set(true);
        fixture.failNextBinding.set(true);
        try {
            StepVerifier.withVirtualTime(() -> Mono.fromRunnable(() -> {
                    fixture.gateway.startup().block();
                    fixture.connection(true);
                    assertTrue(fixture.sessions.isEmpty());
                }).thenMany(Flux.concat(
                    Mono.delay(Duration.ofSeconds(6)),
                    Mono.delay(Duration.ofSeconds(6)),
                    Mono.delay(Duration.ofSeconds(6)))))
                .thenAwait(Duration.ofSeconds(6))
                .assertNext(ignore -> {
                    assertNotNull(fixture.sessions.get("collector"));
                    assertEquals(2, fixture.bindings.get());
                    fixture.connection(false);
                    fixture.sessions.clear();
                })
                .thenAwait(Duration.ofSeconds(6))
                .assertNext(ignore -> {
                    assertEquals(2, fixture.bindings.get());
                    fixture.gateway.shutdown().block();
                })
                .thenAwait(Duration.ofSeconds(6))
                .assertNext(ignore -> assertEquals(2, fixture.bindings.get()))
                .expectComplete().verify(Duration.ofSeconds(10));
        } finally {
            fixture.gateway.shutdown().block();
        }
        assertTrue(fixture.sessions.isEmpty());
    }

    private static final class Fixture {
        final TcpClient tcpClient = mock(TcpClient.class);
        final DeviceRegistry registry = mock(DeviceRegistry.class);
        final DeviceSessionManager manager = mock(DeviceSessionManager.class);
        final AtomicBoolean active = new AtomicBoolean();
        final AtomicBoolean physical = new AtomicBoolean();
        final AtomicBoolean failNextBinding = new AtomicBoolean();
        final AtomicInteger bindings = new AtomicInteger();
        final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
        final Sinks.Many<Boolean> states = Sinks.many().replay().latest();
        final TcpClientDeviceGateway gateway;

        @SuppressWarnings("unchecked")
        Fixture() {
            DeviceOperator device = mock(DeviceOperator.class);
            ProtocolSupport protocol = mock(ProtocolSupport.class);
            states.tryEmitNext(false);
            when(tcpClient.shutdownAsync()).thenCallRealMethod();
        when(tcpClient.getId()).thenReturn("network-10002");
            when(tcpClient.connectionState()).thenReturn(states.asFlux());
            when(tcpClient.subscribe()).thenReturn(Flux.never());
            when(tcpClient.isAlive()).thenAnswer(ignore -> physical.get());
            when(device.getDeviceId()).thenReturn("collector");
            when(device.getConfig(PropertyConstants.accessId)).thenReturn(Mono.just("access-10002"));
            when(registry.getDevice("collector"))
                .thenReturn(Mono.defer(() -> active.get() ? Mono.just(device) : Mono.empty()));
            when(manager.getSession(anyString(), eq(false)))
                .thenAnswer(call -> Mono.defer(() -> Mono.justOrEmpty(sessions.get(call.getArgument(0)))));
            when(manager.remove(anyString(), any(Predicate.class))).thenAnswer(call -> Mono.fromSupplier(() -> {
                String id = call.getArgument(0);
                DeviceSession session = sessions.get(id);
                Predicate<DeviceSession> predicate = call.getArgument(1);
                if (session == null || !predicate.test(session)) return 0L;
                sessions.remove(id, session);
                session.close();
                return 1L;
            }));
            when(manager.compute(anyString(), any(), any())).thenAnswer(call -> {
                String id = call.getArgument(0);
                Mono<DeviceSession> create = call.getArgument(1);
                Function<DeviceSession, Mono<DeviceSession>> update = call.getArgument(2);
                return Mono.defer(() -> {
                    bindings.incrementAndGet();
                    if (failNextBinding.getAndSet(false)) return Mono.error(new IllegalStateException("temporary registry failure"));
                    DeviceSession existing = sessions.get(id);
                    return existing == null ? create : update.apply(existing);
                }).doOnNext(session -> sessions.put(id, session));
            });
            when(protocol.onClientConnect(eq(DefaultTransport.TCP), eq(tcpClient), any())).thenReturn(Mono.empty());
            gateway = new TcpClientDeviceGateway("access-10002", Mono.just(protocol), registry,
                mock(DecodedClientMessageHandler.class), manager, tcpClient, "collector");
        }

        void connection(boolean connected) {
            physical.set(connected);
            states.tryEmitNext(connected);
        }
    }
}
