/*
 * Copyright 2025 JetLinks https://www.jetlinks.cn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetlinks.community.network.tcp.gateway.device;

import org.jetlinks.community.gateway.monitor.DeviceGatewayMonitor;
import org.jetlinks.community.PropertyConstants;
import java.util.function.Predicate;
import org.jetlinks.community.network.tcp.TcpMessage;
import org.jetlinks.community.network.tcp.client.TcpClient;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.device.session.DeviceSessionManager;
import org.jetlinks.core.ProtocolSupport;
import org.jetlinks.core.message.codec.DefaultTransport;
import org.jetlinks.supports.server.DecodedClientMessageHandler;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class TcpClientDeviceGatewayTest {

    @Test
    void shutdownWaitsForNetworkCleanupBeforeCompleting() {
        TcpClient client = mock(TcpClient.class);
        Sinks.Empty<Void> closed = Sinks.empty();
        when(client.getId()).thenReturn("client");
        when(client.connectionState()).thenReturn(Flux.never());
        when(client.subscribe()).thenReturn(Flux.never());
        when(client.shutdownAsync()).thenReturn(closed.asMono());
        TcpClientDeviceGateway gateway = new TcpClientDeviceGateway("access", Mono.empty(),
            mock(DeviceRegistry.class), mock(DecodedClientMessageHandler.class),
            mock(DeviceSessionManager.class), client, null);
        gateway.startup().block();
        AtomicBoolean completed = new AtomicBoolean();
        gateway.shutdown().subscribe(ignored -> { }, error -> { throw new AssertionError(error); }, () -> completed.set(true));
        assertFalse(completed.get());
        closed.tryEmitEmpty();
        assertTrue(completed.get());
    }

    @Test
    void startupAfterShutdownShouldCreateActiveSubscriptions() throws InterruptedException {
        TcpClient tcpClient = mock(TcpClient.class);
        AtomicInteger cancellations = new AtomicInteger();
        Semaphore subscriptions = new Semaphore(0);
        Semaphore cancelled = new Semaphore(0);
        when(tcpClient.shutdownAsync()).thenCallRealMethod();
        when(tcpClient.getId()).thenReturn("test-client");
        when(tcpClient.connectionState()).thenReturn(Flux.never());
        when(tcpClient.subscribe())
            .thenAnswer(ignore -> Flux
                .<TcpMessage>never()
                .doOnSubscribe(subscription -> subscriptions.release())
                .doOnCancel(() -> {
                    cancellations.incrementAndGet();
                    cancelled.release();
                }));

        TcpClientDeviceGateway gateway = new TcpClientDeviceGateway(
            "test-gateway",
            Mono.empty(),
            mock(DeviceRegistry.class),
            mock(DecodedClientMessageHandler.class),
            mock(DeviceSessionManager.class),
            tcpClient,
            null);

        gateway.startup().block();
        assertTrue(subscriptions.tryAcquire(5, TimeUnit.SECONDS));
        gateway.shutdown().block();
        assertTrue(cancelled.tryAcquire(5, TimeUnit.SECONDS));
        assertEquals(1, cancellations.get());

        gateway.startup().block();
        assertTrue(subscriptions.tryAcquire(5, TimeUnit.SECONDS));
        assertEquals(1, cancellations.get());

        gateway.shutdown().block();
        assertTrue(cancelled.tryAcquire(5, TimeUnit.SECONDS));
        assertEquals(2, cancellations.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void physicalConnectionShouldBindConfiguredGatewayAndRebindAfterReconnect() throws InterruptedException {
        TcpClient tcpClient = mock(TcpClient.class);
        DeviceRegistry registry = mock(DeviceRegistry.class);
        DeviceSessionManager sessionManager = mock(DeviceSessionManager.class);
        DeviceOperator device = mock(DeviceOperator.class);
        ProtocolSupport protocol = mock(ProtocolSupport.class);
        Sinks.Many<Boolean> connectionStates = Sinks.many().replay().latest();
        Semaphore bindings = new Semaphore(0);
        Semaphore protocolHooks = new Semaphore(0);

        connectionStates.tryEmitNext(false);
        when(tcpClient.shutdownAsync()).thenCallRealMethod();
        when(tcpClient.getId()).thenReturn("test-client");
        when(tcpClient.connectionState()).thenReturn(connectionStates.asFlux());
        when(tcpClient.subscribe()).thenReturn(Flux.never());
        when(registry.getDevice("gateway-device")).thenReturn(Mono.just(device));
        when(device.getDeviceId()).thenReturn("gateway-device");
        when(device.getConfig(PropertyConstants.accessId)).thenReturn(Mono.just("test-gateway"));
        when(sessionManager.getSession("gateway-device", false)).thenReturn(Mono.empty());
        when(sessionManager.remove(eq("gateway-device"), any(Predicate.class))).thenReturn(Mono.just(1L));
        when(sessionManager.compute(eq("gateway-device"), any(Mono.class), any()))
            .thenAnswer(invocation -> {
                bindings.release();
                return invocation.getArgument(1, Mono.class);
            });
        when(protocol.onClientConnect(eq(DefaultTransport.TCP), eq(tcpClient), any()))
            .thenAnswer(ignore -> {
                protocolHooks.release();
                return Mono.empty();
            });

        TcpClientDeviceGateway gateway = new TcpClientDeviceGateway(
            "test-gateway",
            Mono.just(protocol),
            registry,
            mock(DecodedClientMessageHandler.class),
            sessionManager,
            tcpClient,
            "gateway-device");

        gateway.startup().block();
        assertEquals(0, gateway.totalConnection());

        connectionStates.tryEmitNext(true);
        assertTrue(bindings.tryAcquire(5, TimeUnit.SECONDS));
        assertTrue(protocolHooks.tryAcquire(5, TimeUnit.SECONDS));
        assertEquals(1, gateway.totalConnection());

        connectionStates.tryEmitNext(false);
        verify(sessionManager).remove(eq("gateway-device"), any(Predicate.class));
        assertEquals(0, gateway.totalConnection());
        connectionStates.tryEmitNext(true);
        assertTrue(bindings.tryAcquire(5, TimeUnit.SECONDS));
        assertTrue(protocolHooks.tryAcquire(5, TimeUnit.SECONDS));
        assertEquals(1, gateway.totalConnection());

        verify(registry, atLeastOnce()).getDevice("gateway-device");
        gateway.shutdown().block();
    }

    @Test
    void gatewayWithoutConfiguredDeviceShouldKeepProtocolDrivenLoginPath() throws InterruptedException {
        TcpClient tcpClient = mock(TcpClient.class);
        ProtocolSupport protocol = mock(ProtocolSupport.class);
        DeviceRegistry registry = mock(DeviceRegistry.class);
        Sinks.Many<Boolean> connectionStates = Sinks.many().replay().latest();
        Semaphore protocolHooks = new Semaphore(0);

        connectionStates.tryEmitNext(true);
        when(tcpClient.shutdownAsync()).thenCallRealMethod();
        when(tcpClient.getId()).thenReturn("test-client");
        when(tcpClient.connectionState()).thenReturn(connectionStates.asFlux());
        when(tcpClient.subscribe()).thenReturn(Flux.never());
        when(protocol.onClientConnect(eq(DefaultTransport.TCP), eq(tcpClient), any()))
            .thenAnswer(ignore -> {
                protocolHooks.release();
                return Mono.empty();
            });

        TcpClientDeviceGateway gateway = new TcpClientDeviceGateway(
            "test-gateway",
            Mono.just(protocol),
            registry,
            mock(DecodedClientMessageHandler.class),
            mock(DeviceSessionManager.class),
            tcpClient,
            null);

        gateway.startup().block();
        assertTrue(protocolHooks.tryAcquire(5, TimeUnit.SECONDS));
        assertEquals(1, gateway.totalConnection());
        verify(registry, never()).getDevice(anyString());
        gateway.shutdown().block();
    }

    @Test
    void sharedClientSessionShouldNotShutdownNetworkWhenChildSessionCloses() {
        TcpClient tcpClient = mock(TcpClient.class);
        TcpDeviceSession session = new TcpDeviceSession(
            mock(DeviceOperator.class),
            tcpClient,
            DefaultTransport.TCP,
            mock(DeviceGatewayMonitor.class),
            false);

        session.close();
        verify(tcpClient, never()).shutdown();

        new TcpDeviceSession(mock(DeviceOperator.class), tcpClient, DefaultTransport.TCP,
            mock(DeviceGatewayMonitor.class), true).close();
        verify(tcpClient).shutdown();
    }
}
