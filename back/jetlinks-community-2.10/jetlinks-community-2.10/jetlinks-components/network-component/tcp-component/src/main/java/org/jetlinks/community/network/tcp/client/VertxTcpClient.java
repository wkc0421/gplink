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
package org.jetlinks.community.network.tcp.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.jetlinks.community.network.tcp.TcpMessage;
import org.jetlinks.community.network.tcp.parser.PayloadParser;
import org.jetlinks.core.message.codec.EncodedMessage;
import org.jetlinks.core.utils.Reactors;
import org.jetlinks.community.network.DefaultNetworkType;
import org.jetlinks.community.network.NetworkType;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class VertxTcpClient implements TcpClient {

    private static final int MESSAGE_BUFFER_SIZE = 1024;

    public volatile NetClient client;

    public volatile NetSocket socket;

    volatile PayloadParser payloadParser;

    private volatile Disposable payloadSubscription;

    @Getter
    private final String id;

    @Setter
    private long keepAliveTimeoutMs = Duration.ofMinutes(10).toMillis();

    private volatile long lastKeepAliveTime = System.currentTimeMillis();

    private final List<Runnable> disconnectListener = new CopyOnWriteArrayList<>();

    // The multicast queue owns heap snapshots. Each subscriber gets its own
    // reference-counted view; slow/cancelled subscribers cannot release another's frame.
    private final Sinks.Many<ReceivedFrame> sink = Reactors.createMany(MESSAGE_BUFFER_SIZE, false);

    private final Sinks.Many<Boolean> connectionStateSink = Sinks.many().replay().latest();

    private boolean connected;

    private final AtomicLong connectionVersion = new AtomicLong();

    private final boolean serverClient;

    @Override
    public void keepAlive() {
        lastKeepAliveTime = System.currentTimeMillis();
    }

    @Override
    public void setKeepAliveTimeout(Duration timeout) {
        keepAliveTimeoutMs = timeout.toMillis();
    }

    @Override
    public void reset() {
        if (null != payloadParser) {
            payloadParser.reset();
        }
    }

    @Override
    public InetSocketAddress address() {
        return getRemoteAddress();
    }

    @Override
    public Mono<Void> sendMessage(EncodedMessage message) {
        return Mono
            .<Void>create((sink) -> {
                ByteBuf buf = message.getPayload();
                NetSocket current = socket;
                if (current == null) {
                    ReferenceCountUtil.safeRelease(buf);
                    sink.error(new SocketException("socket closed"));
                    return;
                }

                Buffer buffer;
                try {
                    // This method owns the encoded payload. Copy it into
                    // Vert.x-managed memory and release the reference-counted
                    // ByteBuf before entering the asynchronous write path.
                    buffer = Buffer.buffer(ByteBufUtil.getBytes(buf));
                } catch (Throwable error) {
                    sink.error(error);
                    return;
                } finally {
                    ReferenceCountUtil.safeRelease(buf);
                }

                try {
                    current.write(buffer, r -> {
                        if (r.succeeded()) {
                            // For an outbound TCP client a successful local write only
                            // means that the bytes entered the operating-system buffer.
                            // It does not prove that the remote server is still responsive.
                            // Refresh outbound-client liveness only when data is received;
                            // otherwise periodic Modbus requests can keep a silent/broken
                            // connection alive forever and prevent reconnection.
                            if (serverClient) {
                                keepAlive();
                            }
                            sink.success();
                        } else {
                            sink.error(r.cause());
                        }
                    });
                } catch (Throwable error) {
                    sink.error(error);
                }
            });
    }

    @Override
    public Flux<EncodedMessage> receiveMessage() {
        return this
            .subscribe()
            .cast(EncodedMessage.class);
    }

    @Override
    public void disconnect() {
        shutdown();
    }

    @Override
    public boolean isAlive() {
        return socket != null && (keepAliveTimeoutMs < 0 || System.currentTimeMillis() - lastKeepAliveTime < keepAliveTimeoutMs);
    }

    @Override
    public boolean isAutoReload() {
        return true;
    }

    public VertxTcpClient(String id) {
        this(id, true);
    }

    public VertxTcpClient(String id, boolean serverClient) {
        this.id = id;
        this.serverClient = serverClient;
        connectionStateSink.tryEmitNext(false);
    }

    protected void received(TcpMessage message) {
        byte[] bytes;
        try {
            bytes = ByteBufUtil.getBytes(message.getPayload());
        } finally {
            ReferenceCountUtil.safeRelease(message.getPayload());
        }
        Sinks.EmitResult result = sink.tryEmitNext(new ReceivedFrame(connectionVersion.get(), bytes));
        if (result != Sinks.EmitResult.OK) {
            log.warn("drop tcp client [{}] payload because receive buffer rejected it: {}",
                     id,
                     result);
        }
    }

    @Override
    public Flux<TcpMessage> subscribe() {
        return sink.asFlux()
            .filter(frame -> frame.version == connectionVersion.get())
            .map(frame -> new TcpMessage(Unpooled.copiedBuffer(frame.bytes)));
    }

    private static final class ReceivedFrame {
        final long version;
        final byte[] bytes;

        private ReceivedFrame(long version, byte[] bytes) {
            this.version = version;
            this.bytes = bytes;
        }
    }

    @Override
    public Flux<Boolean> connectionState() {
        return connectionStateSink.asFlux();
    }

    private void updateConnectionStateLocked(boolean state) {
        if (connected == state) {
            return;
        }
        connected = state;
        Sinks.EmitResult result = connectionStateSink.tryEmitNext(state);
        if (result.isFailure()) {
            log.warn("emit tcp client [{}] connection state [{}] failed: {}", id, state, result);
        }
    }

    private void execute(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            log.warn("close tcp client error", e);
        }
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        if (null == socket) {
            return null;
        }
        SocketAddress socketAddress = socket.remoteAddress();
        return InetSocketAddress.createUnresolved(socketAddress.host(), socketAddress.port());
    }

    @Override
    public NetworkType getType() {
        return DefaultNetworkType.TCP_CLIENT;
    }

    @Override
    public void shutdown() {
        shutdown(null, -1, null);
    }

    private void shutdown(NetSocket expectedSocket, long expectedVersion, PayloadParser expectedParser) {
        NetClient currentClient;
        NetSocket currentSocket;
        PayloadParser currentParser;
        Disposable currentSubscription;
        List<Runnable> disconnectListeners;

        synchronized (this) {
            if (expectedSocket != null && (socket != expectedSocket || connectionVersion.get() != expectedVersion)) {
                return;
            }
            if (expectedParser != null && payloadParser != expectedParser) {
                return;
            }
            connectionVersion.incrementAndGet();
            currentClient = client;
            currentSocket = socket;
            currentParser = payloadParser;
            currentSubscription = payloadSubscription;
            client = null;
            socket = null;
            payloadParser = null;
            payloadSubscription = null;
            disconnectListeners = drainDisconnectListenersLocked();
            updateConnectionStateLocked(false);
        }

        if (currentClient != null || currentSocket != null) {
            log.debug("tcp client [{}] disconnect", getId());
        }

        closeResources(currentClient, currentSocket, currentParser, currentSubscription);
        notifyDisconnectListeners(disconnectListeners);

        // Accepted TCP server connections are one-shot. Outbound TCP clients
        // are reusable and must keep their receive stream alive across reload.
        if (serverClient) {
            sink.tryEmitComplete();
        }
    }

    public void setClient(NetClient client) {
        replaceClient(client);
    }

    public long replaceClient(NetClient client) {
        Objects.requireNonNull(client, "client");
        NetClient previousClient;
        NetSocket previousSocket;
        List<Runnable> disconnectListeners;
        long version;
        synchronized (this) {
            previousClient = this.client;
            previousSocket = this.socket;
            this.client = client;
            this.socket = null;
            version = connectionVersion.incrementAndGet();
            keepAlive();
            disconnectListeners = drainDisconnectListenersLocked();
            updateConnectionStateLocked(false);
        }

        if (previousSocket != null) {
            execute(previousSocket::close);
        }
        if (previousClient != null && previousClient != client) {
            execute(previousClient::close);
        }
        notifyDisconnectListeners(disconnectListeners);
        return version;
    }

    public void setRecordParser(PayloadParser payloadParser) {
        Objects.requireNonNull(payloadParser, "payloadParser");
        PayloadParser previousParser;
        Disposable previousSubscription;
        synchronized (this) {
            previousParser = this.payloadParser;
            previousSubscription = this.payloadSubscription;
            this.payloadParser = payloadParser;
            Disposable nextSubscription = payloadParser
                .handlePayload()
                .subscribe(
                    buffer -> {
                        if (this.payloadParser == payloadParser) {
                            received(new TcpMessage(buffer.getByteBuf()));
                        } else {
                            ReferenceCountUtil.safeRelease(buffer.getByteBuf());
                        }
                    },
                    error -> {
                        log.warn("tcp client [{}] payload parser error", id, error);
                        shutdown(null, -1, payloadParser);
                    });
            // A parser may fail synchronously during subscription and clear itself.
            if (this.payloadParser == payloadParser) {
                this.payloadSubscription = nextSubscription;
            } else {
                nextSubscription.dispose();
            }
        }

        if (previousSubscription != null) {
            execute(previousSubscription::dispose);
        }
        if (previousParser != null && previousParser != payloadParser) {
            execute(previousParser::close);
        }
    }

    public void setSocket(NetSocket socket) {
        long version;
        synchronized (this) {
            version = connectionVersion.incrementAndGet();
        }
        setSocket(socket, version);
    }

    public boolean setSocket(NetSocket socket, long version) {
        Objects.requireNonNull(socket, "socket");
        socket.closeHandler(v -> handleSocketClosed(socket, version));
        socket.handler(buffer -> handleSocketPayload(socket, version, buffer));

        NetSocket previousSocket = null;
        boolean accepted;
        synchronized (this) {
            accepted = connectionVersion.get() == version && payloadParser != null;
            if (accepted) {
                previousSocket = this.socket;
                this.socket = socket;
                keepAlive();
                updateConnectionStateLocked(true);
            }
        }

        if (!accepted) {
            execute(socket::close);
            return false;
        }

        if (previousSocket != null && previousSocket != socket) {
            execute(previousSocket::close);
        }
        return true;
    }

    public void handleConnectFailure(NetClient failedClient, long version) {
        PayloadParser currentParser = null;
        Disposable currentSubscription = null;
        List<Runnable> disconnectListeners = List.of();
        boolean currentConnection;

        synchronized (this) {
            currentConnection = this.client == failedClient
                && this.socket == null
                && connectionVersion.get() == version;
            if (currentConnection) {
                connectionVersion.incrementAndGet();
                this.client = null;
                currentParser = this.payloadParser;
                currentSubscription = this.payloadSubscription;
                this.payloadParser = null;
                this.payloadSubscription = null;
                disconnectListeners = drainDisconnectListenersLocked();
                updateConnectionStateLocked(false);
            }
        }

        execute(failedClient::close);
        if (currentSubscription != null) {
            execute(currentSubscription::dispose);
        }
        if (currentParser != null) {
            execute(currentParser::close);
        }
        notifyDisconnectListeners(disconnectListeners);
    }

    private void handleSocketClosed(NetSocket closedSocket, long version) {
        shutdown(closedSocket, version, null);
    }

    private void handleSocketPayload(NetSocket source, long version, Buffer buffer) {
        PayloadParser parser;
        synchronized (this) {
            if (socket != source || connectionVersion.get() != version) {
                execute(source::close);
                return;
            }
            parser = payloadParser;
        }

        if (parser == null) {
            execute(source::close);
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("handle tcp client[{}] payload:[{}]",
                      source.remoteAddress(),
                      Hex.encodeHexString(buffer.getBytes()));
        }
        keepAlive();
        try {
            parser.handle(buffer);
        } catch (Throwable error) {
            log.warn("tcp client [{}] payload parser failed", id, error);
            shutdown(source, version, parser);
        }
    }

    private void closeResources(NetClient currentClient,
                                NetSocket currentSocket,
                                PayloadParser currentParser,
                                Disposable currentSubscription) {
        if (currentSubscription != null) {
            execute(currentSubscription::dispose);
        }
        if (currentParser != null) {
            execute(currentParser::close);
        }
        if (currentSocket != null) {
            execute(currentSocket::close);
        }
        if (currentClient != null) {
            execute(currentClient::close);
        }
    }

    private List<Runnable> drainDisconnectListenersLocked() {
        List<Runnable> listeners = List.copyOf(disconnectListener);
        disconnectListener.clear();
        return listeners;
    }

    private void notifyDisconnectListeners(List<Runnable> listeners) {
        for (Runnable listener : listeners) {
            execute(listener);
        }
    }

    @Override
    public Mono<Boolean> send(TcpMessage message) {
        return sendMessage(message)
            .thenReturn(true);
    }

    @Override
    public synchronized void onDisconnect(Runnable disconnected) {
        disconnectListener.add(Objects.requireNonNull(disconnected, "disconnected"));
    }
}
