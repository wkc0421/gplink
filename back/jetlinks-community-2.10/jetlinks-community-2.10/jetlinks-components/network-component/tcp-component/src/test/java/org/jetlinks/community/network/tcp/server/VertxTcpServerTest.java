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
package org.jetlinks.community.network.tcp.server;

import io.netty.buffer.Unpooled;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.Future;
import io.vertx.core.net.NetServer;
import org.jetlinks.community.gateway.DeviceGateway;
import org.jetlinks.community.gateway.supports.DeviceGatewayProperties;
import org.jetlinks.community.network.DefaultNetworkType;
import org.jetlinks.community.network.NetworkManager;
import org.jetlinks.community.network.security.CertificateManager;
import org.jetlinks.community.network.tcp.TcpMessage;
import org.jetlinks.community.network.tcp.client.TcpClient;
import org.jetlinks.community.network.tcp.gateway.device.TcpServerDeviceGatewayProvider;
import org.jetlinks.community.network.tcp.parser.DefaultPayloadParserBuilder;
import org.jetlinks.community.network.tcp.parser.PayloadParserType;
import org.jetlinks.core.ProtocolSupport;
import org.jetlinks.core.ProtocolSupports;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.device.session.DeviceSessionManager;
import org.jetlinks.core.message.codec.DefaultTransport;
import org.jetlinks.supports.server.DecodedClientMessageHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(30)
class VertxTcpServerTest {

    @Test
    void replacingListenersShouldKeepExistingConnectionSubscription() {
        VertxTcpServer server = new VertxTcpServer("replace-listeners");
        NetServer first = mock(NetServer.class);
        NetServer replacement = mock(NetServer.class);
        when(first.close()).thenReturn(Future.succeededFuture());
        when(replacement.close()).thenReturn(Future.succeededFuture());
        server.setServer(List.of(first));
        AtomicBoolean completed = new AtomicBoolean();
        Disposable subscription = server.handleConnection()
                                        .doOnComplete(() -> completed.set(true))
                                        .subscribe();
        try {
            server.setServer(List.of(replacement));

            verify(first).close();
            assertFalse(completed.get(), "Replacing listeners must not complete the gateway connection stream");
            assertFalse(subscription.isDisposed());
        } finally {
            subscription.dispose();
            server.shutdown();
        }
    }

    @Test
    void shutdownShouldCloseAllListenersAndRemainIdempotent() {
        VertxTcpServer server = new VertxTcpServer("shutdown-listeners");
        NetServer first = mock(NetServer.class);
        NetServer second = mock(NetServer.class);
        when(first.close()).thenReturn(Future.succeededFuture());
        when(second.close()).thenReturn(Future.succeededFuture());
        server.setServer(List.of(first, second));

        server.shutdown();
        server.shutdown();

        verify(first).close();
        verify(second).close();
        assertFalse(server.isAlive());
    }

    @Test
    void existingGatewayShouldAcceptConnectionsAcrossRepeatedReloads() throws Exception {
        try (TestServer fixture = new TestServer()) {
            fixture.startGateway();
            for (int reload = 0; reload < 4; reload++) {
                fixture.assertGatewayAcceptsConnection();
                if (reload < 3) {
                    fixture.reload();
                }
            }
            verify(fixture.protocol, times(4)).onClientConnect(eq(DefaultTransport.TCP), any(), any());
        }
    }

    @Test
    void gatewayShouldBeAbleToSubscribeAgainAfterNetworkStopStart() throws Exception {
        try (TestServer fixture = new TestServer()) {
            DeviceGateway first = fixture.startGateway();
            fixture.assertGatewayAcceptsConnection();
            first.shutdown().block(Duration.ofSeconds(5));
            fixture.server.shutdown();
            assertFalse(fixture.server.isAlive());

            fixture.reload();
            fixture.startGateway();
            fixture.assertGatewayAcceptsConnection();

            verify(fixture.protocol, times(2)).onClientConnect(eq(DefaultTransport.TCP), any(), any());
        }
    }

    @Test
    void connectionsWithoutGatewayShouldStillBeClosed() throws Exception {
        try (TestServer fixture = new TestServer()) {
            fixture.assertConnectionIsRejected();
            DeviceGateway gateway = fixture.startGateway();
            fixture.assertGatewayAcceptsConnection();

            gateway.shutdown().block(Duration.ofSeconds(5));
            fixture.assertConnectionIsRejected();

            fixture.startGateway();
            fixture.assertGatewayAcceptsConnection();
        }
    }

    @Test
    void occupiedPortShouldFailStartupAndAllowRetryAfterRelease() throws Exception {
        try (TestServer fixture = new TestServer()) {
            fixture.startGateway();
            try (ServerSocket occupied = new ServerSocket()) {
                occupied.bind(new InetSocketAddress("127.0.0.1", 0));
                fixture.properties.setPort(occupied.getLocalPort());
                assertThrows(RuntimeException.class, fixture::reload);
                assertFalse(fixture.server.isAlive());
            }

            fixture.reload();
            fixture.assertGatewayAcceptsConnection();
        }
    }

    @Test
    void completedShutdownShouldAllowImmediateRebindToTheSamePort() throws Exception {
        try (TestServer fixture = new TestServer()) {
            fixture.startGateway();
            fixture.properties.setPort(fixture.server.tcpServers.iterator().next().actualPort());
            for (int i = 0; i < 3; i++) {
                fixture.server.shutdownAsync().block(Duration.ofSeconds(5));
                assertFalse(fixture.server.isAlive());
                fixture.reload();
                fixture.assertGatewayAcceptsConnection();
            }
        }
    }

    @Test
    void shutdownShouldCloseAcceptedConnectionsWithoutDependingOnAGateway() throws Exception {
        try (TestServer fixture = new TestServer()) {
            CompletableFuture<TcpClient> accepted = new CompletableFuture<>();
            Disposable subscription = fixture.server.handleConnection().subscribe(accepted::complete);
            try (Socket socket = fixture.connect()) {
                TcpClient client = accepted.get(5, TimeUnit.SECONDS);
                fixture.server.shutdownAsync().block(Duration.ofSeconds(5));

                assertFalse(client.isAlive());
                assertEquals(-1, socket.getInputStream().read());
            } finally {
                subscription.dispose();
            }
        }
    }

    private static class TestServer implements AutoCloseable {
        private final Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(2).setWorkerPoolSize(2));
        private final TcpServerProperties properties = new TcpServerProperties();
        private final DefaultTcpServerProvider provider = new DefaultTcpServerProvider(
            mock(CertificateManager.class), vertx, new DefaultPayloadParserBuilder());
        private final List<DeviceGateway> gateways = new ArrayList<>();
        private final ProtocolSupport protocol = mock(ProtocolSupport.class);
        private final VertxTcpServer server;

        TestServer() {
            properties.setId("tcp-server-reload-test");
            properties.setHost("127.0.0.1");
            properties.setPort(0);
            properties.setInstance(2);
            properties.setParserType(PayloadParserType.DIRECT);
            server = (VertxTcpServer) provider.createNetwork(properties).block(Duration.ofSeconds(5));
            when(protocol.onClientConnect(eq(DefaultTransport.TCP), any(), any()))
                .thenAnswer(call -> {
                    TcpClient client = call.getArgument(1);
                    return client.sendMessage(new TcpMessage(Unpooled.wrappedBuffer(
                        "OK".getBytes(StandardCharsets.US_ASCII))));
                });
        }

        DeviceGateway startGateway() {
            NetworkManager networks = mock(NetworkManager.class);
            when(networks.<TcpServer>getNetwork(DefaultNetworkType.TCP_SERVER, properties.getId()))
                .thenReturn(Mono.just(server));
            ProtocolSupports protocols = mock(ProtocolSupports.class);
            when(protocols.getProtocol("test-protocol")).thenReturn(Mono.just(protocol));
            DeviceSessionManager sessions = mock(DeviceSessionManager.class);
            when(sessions.getSession(anyString())).thenReturn(Mono.empty());
            TcpServerDeviceGatewayProvider gatewaysProvider = new TcpServerDeviceGatewayProvider(
                networks, mock(DeviceRegistry.class), sessions, mock(DecodedClientMessageHandler.class), protocols);
            DeviceGatewayProperties gatewayProperties = new DeviceGatewayProperties();
            gatewayProperties.setId("gateway-" + gateways.size());
            gatewayProperties.setChannelId(properties.getId());
            gatewayProperties.setProtocol("test-protocol");
            DeviceGateway gateway = gatewaysProvider.createDeviceGateway(gatewayProperties)
                                                   .block(Duration.ofSeconds(5));
            gateways.add(gateway);
            gateway.startup().block(Duration.ofSeconds(5));
            return gateway;
        }

        void reload() {
            assertSame(server, provider.reload(server, properties).block(Duration.ofSeconds(5)));
        }

        Socket connect() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (server.tcpServers.stream().anyMatch(listener -> listener.actualPort() <= 0)) {
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError("TCP listeners did not start: " + server.getLastError());
                }
                Thread.sleep(10);
            }
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", server.tcpServers.iterator().next().actualPort()), 2_000);
            socket.setSoTimeout(2_000);
            return socket;
        }

        void assertGatewayAcceptsConnection() throws Exception {
            try (Socket socket = connect()) {
                assertEquals("OK", new String(socket.getInputStream().readNBytes(2), StandardCharsets.US_ASCII),
                             "A real gateway must still handle new TCP connections after reload");
                socket.setSoTimeout(100);
                assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read(),
                             "An accepted connection must stay open");
            }
        }

        void assertConnectionIsRejected() throws Exception {
            try (Socket socket = connect()) {
                assertEquals(-1, socket.getInputStream().read(), "A server without a gateway must close the connection");
            }
        }

        @Override
        public void close() throws Exception {
            try {
                for (DeviceGateway gateway : gateways) {
                    gateway.shutdown().block(Duration.ofSeconds(5));
                }
            } finally {
                server.shutdown();
                vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }
}
