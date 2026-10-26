package org.jetlinks.community.network.tcp.gateway.device;

import org.jetlinks.community.gateway.DeviceGatewayHelper;
import org.jetlinks.community.PropertyConstants;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jetlinks.community.network.tcp.TcpMessage;
import org.jetlinks.community.gateway.monitor.DeviceGatewayMonitor;
import org.jetlinks.community.network.tcp.client.TcpClient;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.message.DeviceOnlineMessage;
import org.jetlinks.core.message.codec.DefaultTransport;
import org.jetlinks.supports.device.session.LocalDeviceSessionManager;
import org.jetlinks.supports.server.DecodedClientMessageHandler;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TcpSessionOwnershipTest {
    @Test
    void sendCreatedBeforeCloseCannotWriteToAReconnectedClient() {
        TcpSessionOwner owner = new TcpSessionOwner("access", new LocalDeviceSessionManager());
        TcpClient client = client();
        TcpDeviceSession session = new TcpDeviceSession(device(), client, DefaultTransport.TCP,
            mock(DeviceGatewayMonitor.class), false, owner);
        ByteBuf payload = Unpooled.buffer().writeByte(1);
        Mono<Boolean> send = session.send(new TcpMessage(payload));
        owner.invalidate();
        assertThrows(IllegalStateException.class, send::block);
        verify(client, never()).send(any());
        assertEquals(0, payload.refCnt());
    }

    @Test
    void registeredDeviceRequiresMatchingAccessAndUnknownDeviceDoesNotBind() {
        DeviceRegistry registry = mock(DeviceRegistry.class);
        DeviceOperator device = device();
        when(registry.getDevice("device")).thenReturn(Mono.just(device));
        when(registry.getDevice("unknown")).thenReturn(Mono.empty());
        when(device.getConfig(PropertyConstants.accessId)).thenReturn(Mono.empty());
        assertThrows(IllegalStateException.class,
            () -> TcpSessionOwner.validateAccess(registry, "device", "access", true).block());
        when(device.getConfig(PropertyConstants.accessId)).thenReturn(Mono.just("other"));
        assertThrows(IllegalStateException.class,
            () -> TcpSessionOwner.validateAccess(registry, "device", "access", true).block());
        when(device.getConfig(PropertyConstants.accessId)).thenReturn(Mono.just("access"));
        TcpSessionOwner.validateAccess(registry, "device", "access", true).block();
        TcpSessionOwner.validateAccess(registry, "unknown", "access", true).block();
        TcpSessionOwner.validateAccess(registry, null, "access", true).block();
        verify(registry, never()).getDevice(isNull());
    }

    @Test
    void rejectedOwnerMustNotMutateOrRemoveExistingSession() {
        LocalDeviceSessionManager manager = new LocalDeviceSessionManager();
        DeviceOperator device = device();
        DeviceRegistry registry = mock(DeviceRegistry.class);
        when(registry.getDevice("device")).thenReturn(Mono.just(device));
        DeviceGatewayHelper helper = new DeviceGatewayHelper(registry, manager, mock(DecodedClientMessageHandler.class));
        TcpSessionOwner first = new TcpSessionOwner("access-1", manager);
        TcpSessionOwner second = new TcpSessionOwner("access-2", manager);
        TcpClient firstClient = client();
        TcpDeviceSession original = new TcpDeviceSession(device, firstClient, DefaultTransport.TCP,
            mock(DeviceGatewayMonitor.class), false, first);
        DeviceOnlineMessage online = new DeviceOnlineMessage();
        online.setDeviceId("device");
        helper.handleDeviceMessage(online, ignored -> Mono.just(original), ignored -> Mono.empty(), Mono::empty, first).block();
        assertThrows(IllegalStateException.class, () -> helper.handleDeviceMessage(online,
            ignored -> Mono.just(new TcpDeviceSession(device, client(), DefaultTransport.TCP,
                mock(DeviceGatewayMonitor.class), false, second)), ignored -> Mono.empty(), Mono::empty, second).block());
        second.close().block();
        assertSame(original, manager.getSession("device").block());
        assertTrue(original.isAlive());
        first.close().block();
        assertNull(manager.getSession("device", false).block());
    }

    @Test
    void sameAccessReconnectReplacesSessionAndOldCleanupCannotRemoveIt() {
        LocalDeviceSessionManager manager = new LocalDeviceSessionManager();
        DeviceOperator device = device();
        DeviceRegistry registry = mock(DeviceRegistry.class);
        when(registry.getDevice("device")).thenReturn(Mono.just(device));
        DeviceGatewayHelper helper = new DeviceGatewayHelper(registry, manager, mock(DecodedClientMessageHandler.class));
        TcpSessionOwner oldOwner = new TcpSessionOwner("access", manager);
        TcpSessionOwner newOwner = new TcpSessionOwner("access", manager);
        TcpDeviceSession oldSession = new TcpDeviceSession(device, client(), DefaultTransport.TCP,
            mock(DeviceGatewayMonitor.class), false, oldOwner);
        TcpDeviceSession replacement = new TcpDeviceSession(device, client(), DefaultTransport.TCP,
            mock(DeviceGatewayMonitor.class), false, newOwner);
        DeviceOnlineMessage online = new DeviceOnlineMessage();
        online.setDeviceId("device");
        helper.handleDeviceMessage(online, ignored -> Mono.just(oldSession), ignored -> Mono.empty(), Mono::empty, oldOwner).block();
        helper.handleDeviceMessage(online, ignored -> Mono.just(replacement), ignored -> Mono.empty(), Mono::empty, newOwner).block();
        oldOwner.close().block();
        assertSame(replacement, manager.getSession("device").block());
        assertFalse(oldSession.isAlive());
        newOwner.close().block();
        assertFalse(replacement.isAlive());
        assertNull(manager.getSession("device", false).block());
    }

    private static TcpClient client() {
        TcpClient client = mock(TcpClient.class);
        when(client.isAlive()).thenReturn(true);
        return client;
    }

    private static DeviceOperator device() {
        DeviceOperator device = mock(DeviceOperator.class);
        when(device.getDeviceId()).thenReturn("device");
        when(device.online(any(), any(), anyLong())).thenReturn(Mono.just(true));
        when(device.offline()).thenReturn(Mono.just(true));
        return device;
    }
}
