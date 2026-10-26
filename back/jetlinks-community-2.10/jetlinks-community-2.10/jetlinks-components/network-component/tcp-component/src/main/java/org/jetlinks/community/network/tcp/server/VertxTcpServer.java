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

import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.core.utils.Reactors;
import org.jetlinks.community.network.DefaultNetworkType;
import org.jetlinks.community.network.NetworkType;
import org.jetlinks.community.network.tcp.client.TcpClient;
import org.jetlinks.community.network.tcp.client.VertxTcpClient;
import org.jetlinks.community.network.tcp.parser.PayloadParser;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * @author bsetfeng
 * @since 1.0
 **/
@Slf4j
public class VertxTcpServer implements TcpServer {

    volatile Collection<NetServer> tcpServers;

    private ServerState current;
    private Mono<Void> pendingShutdown = Mono.empty();

    private Supplier<PayloadParser> parserSupplier;

    @Setter
    private long keepAliveTimeout = Duration.ofMinutes(10).toMillis();

    @Getter
    private final String id;


    private final Sinks.Many<TcpClient> sink = Reactors.createMany(8192, false);

    @Getter
    @Setter
    private String lastError;

    @Setter(AccessLevel.PACKAGE)
    private InetSocketAddress bind;

    public VertxTcpServer(String id) {
        this.id = id;
    }

    @Override
    public Flux<TcpClient> handleConnection() {
        return sink.asFlux();
    }

    private void execute(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            log.warn("close tcp server error", e);
        }
    }

    @Override
    public InetSocketAddress getBindAddress() {
        return bind;
    }

    public void setParserSupplier(Supplier<PayloadParser> parserSupplier) {
        this.parserSupplier = parserSupplier;
    }

    public void setServer(Collection<NetServer> servers) {
        ServerState state = installServers(servers);
        try {
            initializeHandlers(state);
        } catch (RuntimeException | Error error) {
            shutdownAsync(state).subscribe(null, closeError -> log.warn("close TCP server after initialization failure", closeError));
            throw error;
        }
    }

    protected void acceptTcpConnection(NetSocket socket) {
        synchronized (this) {
            acceptTcpConnection(current, socket);
        }
    }

    synchronized ServerState installServers(Collection<NetServer> servers) {
        shutdown();
        ServerState state = new ServerState(List.copyOf(servers), parserSupplier, keepAliveTimeout);
        current = state;
        tcpServers = state.servers;
        return state;
    }

    synchronized void initializeHandlers(ServerState state) {
        for (NetServer server : state.servers) {
            server.connectHandler(socket -> acceptTcpConnection(state, socket));
        }
    }

    synchronized boolean isCurrent(ServerState state) {
        return state != null && current == state && state.active;
    }

    synchronized boolean markListening(ServerState state) {
        if (!isCurrent(state)) {
            return false;
        }
        state.listening = true;
        lastError = null;
        return true;
    }

    private synchronized void acceptTcpConnection(ServerState state, NetSocket socket) {
        if (!isCurrent(state) || sink.currentSubscriberCount() == 0) {
            execute(socket::close);
            return;
        }
        VertxTcpClient client = new VertxTcpClient(id + "_" + socket.remoteAddress());
        client.setKeepAliveTimeoutMs(state.keepAliveTimeout);
        try {
            socket.exceptionHandler(err -> {
                log.error("tcp server client [{}] error", socket.remoteAddress(), err);
                client.shutdown();
            });
            client.setRecordParser(state.parserSupplier.get());
            state.clients.put(socket, client);
            client.onDisconnect(() -> {
                synchronized (VertxTcpServer.this) {
                    state.clients.remove(socket);
                }
            });
            client.setSocket(socket);
            if (sink.tryEmitNext(client).isFailure()) {
                client.shutdown();
            }
            log.debug("accept tcp client [{}] connection", socket.remoteAddress());
        } catch (Exception e) {
            log.error("create tcp server client error", e);
            client.shutdown();
            execute(socket::close);
        }
    }

    @Override
    public NetworkType getType() {
        return DefaultNetworkType.TCP_SERVER;
    }

    @Override
    public void shutdown() {
        shutdownAsync().subscribe(null, error -> log.warn("close tcp server [{}] failed", id, error));
    }

    @Override
    public Mono<Void> shutdownAsync() {
        return Mono.defer(() -> {
            synchronized (this) {
                return current == null ? pendingShutdown : shutdownAsync(current);
            }
        });
    }

    synchronized Mono<Void> shutdownAsync(ServerState state) {
        if (state.closeCompletion != null) {
            return state.closeCompletion;
        }
        state.active = false;
        if (current == state) {
            current = null;
            tcpServers = null;
        }
        List<Mono<Void>> closes = new ArrayList<>();
        state.clients.forEach((socket, client) -> closes.add(Mono.defer(() -> {
            client.shutdown();
            return Mono.fromCompletionStage(socket.close().toCompletionStage());
        })));
        state.clients.clear();
        state.servers.forEach(server -> closes.add(Mono.defer(() ->
                Mono.fromCompletionStage(server.close().toCompletionStage()))));
        state.closeCompletion = Mono.whenDelayError(closes).cache();
        AtomicReference<Mono<Void>> reference = new AtomicReference<>();
        Mono<Void> completion = Mono.whenDelayError(pendingShutdown, state.closeCompletion)
                .doFinally(ignore -> {
                    synchronized (VertxTcpServer.this) {
                        if (pendingShutdown == reference.get()) {
                            pendingShutdown = Mono.empty();
                        }
                    }
                })
                .cache();
        reference.set(completion);
        pendingShutdown = completion;
        return completion;
    }

    @Override
    public synchronized boolean isAlive() {
        return current != null && current.active && current.listening;
    }

    @Override
    public boolean isAutoReload() {
        return false;
    }

    static final class ServerState {
        final List<NetServer> servers;
        final Supplier<PayloadParser> parserSupplier;
        final long keepAliveTimeout;
        final Map<NetSocket, VertxTcpClient> clients = new HashMap<>();
        boolean active = true;
        boolean listening;
        Mono<Void> closeCompletion;

        ServerState(List<NetServer> servers, Supplier<PayloadParser> parserSupplier, long keepAliveTimeout) {
            this.servers = servers;
            this.parserSupplier = parserSupplier;
            this.keepAliveTimeout = keepAliveTimeout;
        }
    }
}
