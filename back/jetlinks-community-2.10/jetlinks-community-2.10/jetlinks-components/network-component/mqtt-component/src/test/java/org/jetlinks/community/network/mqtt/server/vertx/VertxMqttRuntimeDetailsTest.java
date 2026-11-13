package org.jetlinks.community.network.mqtt.server.vertx;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.mqtt.MqttEndpoint;
import io.vertx.mqtt.MqttServer;
import org.jetlinks.community.network.NetworkRuntimeDetails;
import org.jetlinks.community.network.mqtt.client.MqttClientProperties;
import org.jetlinks.community.network.mqtt.client.MqttClientProvider;
import org.jetlinks.community.network.mqtt.client.VertxMqttClient;
import org.jetlinks.community.network.mqtt.server.MqttConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(30)
class VertxMqttRuntimeDetailsTest {
    private static final String HOST = "127.0.0.1";

    @Test
    void reportsAcceptedClientsAndRemovesDisconnectedClients() throws Exception {
        Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(2));
        try {
            VertxMqttServer server = createServer(vertx, 0);
            await(() -> server.getRuntimeDetails().getListening());
            String address = server.getRuntimeDetails().getBindAddress();
            assertTrue(address.startsWith(HOST + ":"));
            int port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
            CompletableFuture<MqttConnection> incoming = new CompletableFuture<>();
            server.handleConnection().subscribe(connection -> {
                if (!incoming.complete(connection)) {
                    connection.accept();
                }
            });

            MqttClientProperties options = new MqttClientProperties();
            options.setId("test-client");
            options.setClientId("meter-a");
            options.setRemoteHost(HOST);
            options.setRemotePort(port);
            MqttClientProvider clients = new MqttClientProvider(null, vertx, null);
            VertxMqttClient client = clients.createNetwork(options)
                .cast(VertxMqttClient.class).block(Duration.ofSeconds(5));

            MqttConnection handshake = incoming.get(5, TimeUnit.SECONDS);
            assertTrue(client.getRuntimeDetails().getConnecting());
            assertFalse(client.getRuntimeDetails().getConnected());
            assertEquals(0, server.getRuntimeDetails().getConnectionCount());
            vertx.runOnContext(ignore -> handshake.accept());

            await(() -> client.getRuntimeDetails().getConnected() && server.getRuntimeDetails().getConnectionCount() == 1);
            handshake.setKeepAliveTimeout(Duration.ZERO);
            assertFalse(handshake.isAlive());
            assertEquals(1, server.getRuntimeDetails().getConnectionCount());
            NetworkRuntimeDetails.Connection peer = server.getRuntimeDetails().getConnections().get(0);
            assertEquals("meter-a", peer.getClientId());
            assertEquals(HOST, peer.getRemoteHost());
            assertTrue(peer.getRemotePort() > 0);
            assertEquals(address, client.getRuntimeDetails().getRemoteAddress());
            assertEquals("meter-a", client.getRuntimeDetails().getClientId());
            assertFalse(client.getRuntimeDetails().getConnecting());

            client.shutdown();
            await(() -> server.getRuntimeDetails().getConnectionCount() == 0);
            assertFalse(client.getRuntimeDetails().getConnected());
            assertTrue(server.getRuntimeDetails().getConnections().isEmpty());
            clients.reload(client, options).block(Duration.ofSeconds(5));
            await(() -> client.getRuntimeDetails().getConnected() && server.getRuntimeDetails().getConnectionCount() == 1);
            server.shutdown();
            assertFalse(server.getRuntimeDetails().getListening());
            assertNull(server.getRuntimeDetails().getBindAddress());
            assertTrue(server.getRuntimeDetails().getConnections().isEmpty());
            client.shutdown();
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void failedBindIsNotReportedAsListening() throws Exception {
        Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(2));
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
            VertxMqttServer server = createServer(vertx, occupied.getLocalPort());
            await(() -> server.getLastError() != null);
            assertFalse(server.getRuntimeDetails().getListening());
            assertNull(server.getRuntimeDetails().getBindAddress());
            assertEquals(0, server.getRuntimeDetails().getConnectionCount());
            server.shutdown();
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void capsThePeerListWithoutTruncatingTheTotal() {
        VertxMqttServer broker = new VertxMqttServer("broker");
        MqttServer listener = mock(MqttServer.class, RETURNS_SELF);
        broker.setMqttServer(List.of(listener));
        broker.handleConnection().subscribe(MqttConnection::accept);
        ArgumentCaptor<Handler<MqttEndpoint>> incoming = ArgumentCaptor.forClass(Handler.class);
        verify(listener).endpointHandler(incoming.capture());

        List<String> clientIds = new ArrayList<>();
        ArgumentCaptor<Handler<Void>> firstClose = ArgumentCaptor.forClass(Handler.class);
        for (int i = 0; i < 101; i++) {
            String clientId = "client-" + i;
            clientIds.add(clientId);
            MqttEndpoint endpoint = mock(MqttEndpoint.class, RETURNS_SELF);
            when(endpoint.isConnected()).thenReturn(true);
            when(endpoint.clientIdentifier()).thenReturn(clientId);
            incoming.getValue().handle(endpoint);
            if (i == 0) {
                verify(endpoint).closeHandler(firstClose.capture());
            }
        }

        NetworkRuntimeDetails beforeClose = broker.getRuntimeDetails();
        assertEquals(101, beforeClose.getConnectionCount());
        assertEquals(clientIds.subList(0, 100), beforeClose.getConnections().stream()
            .map(NetworkRuntimeDetails.Connection::getClientId).toList());

        firstClose.getValue().handle(null);
        NetworkRuntimeDetails afterClose = broker.getRuntimeDetails();
        assertEquals(100, afterClose.getConnectionCount());
        assertEquals(clientIds.subList(1, 101), afterClose.getConnections().stream()
            .map(NetworkRuntimeDetails.Connection::getClientId).toList());
        broker.shutdown();
    }

    private static VertxMqttServer createServer(Vertx vertx, int port) {
        VertxMqttServerProperties options = new VertxMqttServerProperties();
        options.setId("test-server");
        options.setHost(HOST);
        options.setPort(port);
        options.setInstance(1);
        return new DefaultVertxMqttServerProvider(null, vertx).createNetwork(options)
            .cast(VertxMqttServer.class).block(Duration.ofSeconds(5));
    }

    private static void await(BooleanSupplier condition) {
        Flux.interval(Duration.ZERO, Duration.ofMillis(10))
            .filter(tick -> condition.getAsBoolean()).next().block(Duration.ofSeconds(5));
    }
}
