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

import lombok.Getter;
import org.jetlinks.community.gateway.monitor.DeviceGatewayMonitor;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.message.codec.EncodedMessage;
import org.jetlinks.core.message.codec.Transport;
import org.jetlinks.core.server.session.DeviceSession;
import org.jetlinks.community.network.tcp.TcpMessage;
import org.jetlinks.community.network.tcp.client.TcpClient;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;

class TcpDeviceSession implements DeviceSession {


    @Getter
    private final DeviceOperator operator;

    private final TcpClient client;

    @Getter
    private final Transport transport;

    private long lastPingTime = System.currentTimeMillis();

    private final long connectTime = System.currentTimeMillis();

    private final DeviceGatewayMonitor monitor;

    private final boolean shutdownClientOnClose;
    private final TcpSessionOwner owner;
    private volatile boolean closed;
    private final List<Runnable> closeListeners = new ArrayList<>();

    TcpDeviceSession(DeviceOperator operator,
                     TcpClient client,
                     Transport transport,
                     DeviceGatewayMonitor monitor) {
        this(operator, client, transport, monitor, true);
    }

    TcpDeviceSession(DeviceOperator operator,
                     TcpClient client,
                     Transport transport,
                     DeviceGatewayMonitor monitor,
                     boolean shutdownClientOnClose) {
        this(operator, client, transport, monitor, shutdownClientOnClose, new TcpSessionOwner("legacy", null));
    }

    TcpDeviceSession(DeviceOperator operator, TcpClient client, Transport transport,
                     DeviceGatewayMonitor monitor, boolean shutdownClientOnClose, TcpSessionOwner owner) {
        this.operator = operator;
        this.client = client;
        this.transport = transport;
        this.monitor=monitor;
        this.shutdownClientOnClose = shutdownClientOnClose;
        this.owner = owner;
    }

    TcpSessionOwner owner() {
        return owner;
    }

    @Override
    public String getId() {
        return getDeviceId();
    }

    @Override
    public String getDeviceId() {
        return operator.getDeviceId();
    }

    @Override
    public long lastPingTime() {
        return lastPingTime;
    }

    @Override
    public long connectTime() {
        return connectTime;
    }

    @Override
    public Mono<Boolean> send(EncodedMessage encodedMessage) {
        // A publisher created before disconnect may be subscribed after reconnect.
        return Mono.defer(() -> {
            if (!isAlive()) {
                io.netty.util.ReferenceCountUtil.safeRelease(encodedMessage.getPayload());
                return Mono.error(new IllegalStateException("TCP device session has closed"));
            }
            monitor.sentMessage();
            return client.send(new TcpMessage(encodedMessage.getPayload()));
        });
    }

    @Override
    public void close() {
        List<Runnable> listeners;
        synchronized (this) {
            if (closed) return;
            closed = true;
            listeners = new ArrayList<>(closeListeners);
            closeListeners.clear();
        }
        for (Runnable listener : listeners) {
            try { listener.run(); } catch (Throwable ignored) { }
        }
        if (shutdownClientOnClose) {
            client.shutdown();
        }
    }

    @Override
    public void ping() {
        if (closed || !owner.isActive()) return;
        lastPingTime = System.currentTimeMillis();
        client.keepAlive();
    }

    @Override
    public void setKeepAliveTimeout(Duration timeout) {
        client.setKeepAliveTimeout(timeout);
    }

    @Override
    public boolean isAlive() {
        return !closed && owner.isActive() && client.isAlive();
    }

    @Override
    public Optional<InetSocketAddress> getClientAddress() {
        return Optional.ofNullable(client.getRemoteAddress());
    }

    @Override
    public void onClose(Runnable call) {
        synchronized (this) {
            if (!closed) {
                closeListeners.add(call);
                return;
            }
        }
        call.run();
    }

    @Override
    public boolean isChanged(DeviceSession another) {
        if (another.isWrapFrom(TcpDeviceSession.class)) {
            return this.owner != another.unwrap(TcpDeviceSession.class).owner;
        }
        return true;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TcpDeviceSession session = (TcpDeviceSession) o;
        return owner == session.owner && Objects.equals(getDeviceId(), session.getDeviceId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, getDeviceId());
    }
}
