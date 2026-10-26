package org.jetlinks.community.network.tcp.gateway.device;

import org.jetlinks.community.PropertyConstants;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jetlinks.community.network.tcp.TcpMessage;
import org.jetlinks.community.network.tcp.client.TcpClient;
import org.jetlinks.community.network.tcp.server.TcpServer;
import org.jetlinks.core.ProtocolSupport;
import org.jetlinks.core.device.DeviceConfigKey;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.message.DeviceOnlineMessage;
import org.jetlinks.core.message.codec.DefaultTransport;
import org.jetlinks.core.message.codec.DeviceMessageCodec;
import org.jetlinks.core.server.session.ChildrenDeviceSession;
import org.jetlinks.core.server.session.DeviceSession;
import org.jetlinks.supports.device.session.LocalDeviceSessionManager;
import org.jetlinks.supports.server.DecodedClientMessageHandler;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TcpServerDeviceGatewayTest {
    @Test
    void invalidChildConfigurationKeepsExistingChildAndParentSessions() throws Exception {
        Fixture fixture = new Fixture("collector", List.of("meter"));
        DeviceOperator foreign = fixture.device("foreign");
        when(foreign.getConfig(DeviceConfigKey.parentGatewayId)).thenReturn(Mono.just("another-parent"));
        when(fixture.registry.getDevice("foreign")).thenReturn(Mono.just(foreign));
        fixture.gateway.startup().block();
        fixture.incoming.tryEmitNext(fixture.client);
        assertTrue(fixture.connected.tryAcquire(5, TimeUnit.SECONDS));
        try {
            DeviceSession parent = fixture.manager.getSession("collector").block();
            DeviceSession meter = fixture.manager.getSession("meter").block();
            assertThrows(IllegalStateException.class,
                () -> fixture.gateway.reloadChildDevices(List.of("foreign")).block());
            assertSame(parent, fixture.manager.getSession("collector").block());
            assertSame(meter, fixture.manager.getSession("meter").block());
            assertNull(fixture.manager.getSession("foreign", false).block());
            assertTrue(parent.isAlive());
        } finally {
            fixture.gateway.shutdown().block();
        }
    }

    @Test
    void inboundPayloadIsReleasedOnDecodeCompletionAndCancellation() throws InterruptedException {
        Fixture fixture = new Fixture(null, List.of());
        DeviceMessageCodec codec = mock(DeviceMessageCodec.class);
        doReturn(Mono.just(codec)).when(fixture.protocol).getMessageCodec(DefaultTransport.TCP);
        when(codec.decode(any())).thenReturn(Flux.empty());
        TcpServerDeviceGateway.TcpConnection connection = fixture.gateway.new TcpConnection(fixture.client);
        ByteBuf completed = Unpooled.buffer().writeByte(1);
        connection.handleTcpMessage(new TcpMessage(completed)).block();
        awaitReleased(completed);

        when(codec.decode(any())).thenReturn(Flux.never());
        ByteBuf cancelled = Unpooled.buffer().writeByte(2);
        Disposable decoding = connection.handleTcpMessage(new TcpMessage(cancelled)).subscribe();
        decoding.dispose();
        awaitReleased(cancelled);
    }

    private static void awaitReleased(ByteBuf payload) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (payload.refCnt() > 0 && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(0, payload.refCnt());
    }

    @Test
    void reloadAddsRemovesAndRebindsChildrenWithoutClosingParent() throws Exception {
        Fixture fixture = new Fixture("collector", List.of("meter"));
        fixture.gateway.startup().block();
        fixture.incoming.tryEmitNext(fixture.client);
        assertTrue(fixture.connected.tryAcquire(5, TimeUnit.SECONDS));
        try {
            DeviceSession parent = fixture.manager.getSession("collector").block();
            DeviceSession meter = fixture.manager.getSession("meter").block();
            assertNotNull(parent);
            assertNotNull(meter);
            fixture.gateway.reloadChildDevices(List.of("meter", "water")).block();
            assertSame(meter, fixture.manager.getSession("meter").block());
            DeviceSession water = fixture.manager.getSession("water").block();
            assertSame(parent, water.unwrap(ChildrenDeviceSession.class).getParent());
            fixture.gateway.reloadChildDevices(List.of("water")).block();
            assertNull(fixture.manager.getSession("meter", false).block());
            assertSame(water, fixture.manager.getSession("water").block());
            assertTrue(parent.isAlive());
            verify(fixture.client, never()).shutdown();
        } finally {
            fixture.gateway.shutdown().block();
        }
        assertNull(fixture.manager.getSession("collector", false).block());
        assertNull(fixture.manager.getSession("water", false).block());
        assertEquals(0, fixture.gateway.totalConnection());
    }

    @Test
    void unregisteredMessageDoesNotCancelLegalityCheck() throws Exception {
        Fixture fixture = new Fixture(null, List.of());
        when(fixture.registry.getDevice("unknown")).thenReturn(Mono.empty());
        fixture.gateway.startup().block();
        TcpServerDeviceGateway.TcpConnection connection = fixture.gateway.new TcpConnection(fixture.client);
        Disposable accepted = connection.accept().subscribe();
        try {
            assertTrue(fixture.connected.tryAcquire(5, TimeUnit.SECONDS));
            DeviceOnlineMessage unknown = new DeviceOnlineMessage();
            unknown.setDeviceId("unknown");
            connection.handleDeviceMessage(unknown).block();
            assertFalse(connection.legalityChecker.isDisposed());
            connection.checkLegality();
            verify(fixture.client, atLeastOnce()).shutdown();
        } finally {
            accepted.dispose();
            fixture.gateway.shutdown().block();
        }
    }

    @Test
    void constructorDoesNotBindAndCancelledPrebindingCannotLeaveSessions() throws Exception {
        Fixture fixture = new Fixture("collector", List.of());
        Sinks.One<DeviceOperator> delayed = Sinks.one();
        when(fixture.registry.getDevice("collector")).thenReturn(delayed.asMono());
        TcpServerDeviceGateway.TcpConnection connection = fixture.gateway.new TcpConnection(fixture.client);
        verify(fixture.registry, never()).getDevice("collector");
        fixture.gateway.startup().block();
        Disposable accepted = connection.accept().subscribe();
        accepted.dispose();
        delayed.tryEmitValue(fixture.device("collector"));
        connection.cleanup().block(Duration.ofSeconds(5));
        assertNull(fixture.manager.getSession("collector", false).block());
        fixture.gateway.shutdown().block();
    }

    @Test
    void unconfiguredGatewayKeepsProtocolLoginPath() throws Exception {
        Fixture fixture = new Fixture(null, List.of());
        fixture.gateway.startup().block();
        fixture.incoming.tryEmitNext(fixture.client);
        try {
            assertTrue(fixture.connected.tryAcquire(5, TimeUnit.SECONDS));
            verify(fixture.registry, never()).getDevice(anyString());
        } finally {
            fixture.gateway.shutdown().block();
        }
    }

    private static final class Fixture {
        final TcpServer server = mock(TcpServer.class);
        final TcpClient client = mock(TcpClient.class);
        final ProtocolSupport protocol = mock(ProtocolSupport.class);
        final DeviceRegistry registry = mock(DeviceRegistry.class);
        final LocalDeviceSessionManager manager = new LocalDeviceSessionManager();
        final Sinks.Many<TcpClient> incoming = Sinks.many().multicast().onBackpressureBuffer();
        final Semaphore connected = new Semaphore(0);
        final AtomicBoolean alive = new AtomicBoolean(true);
        final List<Runnable> disconnected = new CopyOnWriteArrayList<>();
        final TcpServerDeviceGateway gateway;

        Fixture(String deviceId, List<String> children) {
            when(server.getId()).thenReturn("network");
            when(server.handleConnection()).thenReturn(incoming.asFlux());
            when(client.getId()).thenReturn("connection");
            when(client.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 502));
            when(client.isAlive()).thenAnswer(ignored -> alive.get());
            when(client.subscribe()).thenReturn(Flux.never());
            doAnswer(call -> { disconnected.add(call.getArgument(0)); return null; }).when(client).onDisconnect(any());
            doAnswer(call -> {
                if (alive.getAndSet(false)) disconnected.forEach(Runnable::run);
                return null;
            }).when(client).shutdown();
            when(registry.getDevice(anyString())).thenAnswer(call -> Mono.just(device(call.getArgument(0))));
            when(protocol.onClientConnect(eq(DefaultTransport.TCP), eq(client), any())).thenAnswer(call -> {
                connected.release();
                return Mono.empty();
            });
            gateway = new TcpServerDeviceGateway("access", Mono.just(protocol), registry,
                mock(DecodedClientMessageHandler.class), manager, server, deviceId, children);
        }

        DeviceOperator device(String id) {
            DeviceOperator device = mock(DeviceOperator.class);
            when(device.getDeviceId()).thenReturn(id);
            when(device.getConfig(PropertyConstants.accessId)).thenReturn(Mono.just("access"));
            when(device.getConfig(DeviceConfigKey.parentGatewayId)).thenReturn(Mono.just("collector"));
            when(device.online(any(), any(), anyLong())).thenReturn(Mono.just(true));
            when(device.offline()).thenReturn(Mono.just(true));
            return device;
        }
    }
}
