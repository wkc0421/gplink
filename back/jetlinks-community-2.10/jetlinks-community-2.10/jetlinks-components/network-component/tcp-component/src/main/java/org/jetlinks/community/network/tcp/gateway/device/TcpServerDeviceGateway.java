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
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import io.netty.util.ReferenceCountUtil;
import org.hswebframework.web.logger.ReactiveLogger;
import org.jetlinks.community.gateway.AbstractDeviceGateway;
import org.jetlinks.community.gateway.DeviceGateway;
import org.jetlinks.community.gateway.DeviceGatewayHelper;
import org.jetlinks.community.gateway.monitor.MonitorSupportDeviceGateway;
import org.jetlinks.community.network.tcp.TcpMessage;
import org.jetlinks.community.network.tcp.client.TcpClient;
import org.jetlinks.community.network.tcp.server.TcpServer;
import org.jetlinks.community.utils.TimeUtils;
import org.jetlinks.core.ProtocolSupport;
import org.jetlinks.core.device.DeviceConfigKey;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceProductOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.device.session.DeviceSessionManager;
import org.jetlinks.core.message.DeviceMessage;
import org.jetlinks.core.message.DeviceOnlineMessage;
import org.jetlinks.core.message.codec.DefaultTransport;
import org.jetlinks.core.message.codec.FromDeviceMessageContext;
import org.jetlinks.core.server.DeviceGatewayContext;
import org.jetlinks.core.server.session.ChildrenDeviceSession;
import org.jetlinks.core.server.session.DeviceSession;
import org.jetlinks.supports.server.DecodedClientMessageHandler;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
class TcpServerDeviceGateway extends AbstractDeviceGateway implements DeviceGateway, MonitorSupportDeviceGateway {
    final TcpServer tcpServer;
    @Getter
    volatile Mono<ProtocolSupport> protocol;
    private final DeviceRegistry registry;
    private final DeviceSessionManager sessionManager;
    private final LongAdder counter = new LongAdder();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Disposable disposable;
    private final DeviceGatewayHelper helper;
    private final Set<TcpConnection> connections = ConcurrentHashMap.newKeySet();
    @Getter
    private final String gatewayDeviceId;
    private volatile List<String> childDeviceIds;
    @Setter
    private Duration connectCheckTimeout = TimeUtils.parse(System.getProperty("gateway.tcp.network.connect-check-timeout", "10s"));

    TcpServerDeviceGateway(String id, Mono<ProtocolSupport> protocol, DeviceRegistry registry,
                           DecodedClientMessageHandler handler, DeviceSessionManager sessionManager,
                           TcpServer tcpServer, String gatewayDeviceId, List<String> childDeviceIds) {
        super(id);
        this.protocol = protocol;
        this.registry = registry;
        this.sessionManager = sessionManager;
        this.tcpServer = tcpServer;
        this.gatewayDeviceId = gatewayDeviceId;
        this.childDeviceIds = childDeviceIds == null ? Collections.emptyList() : List.copyOf(childDeviceIds);
        this.helper = new DeviceGatewayHelper(registry, sessionManager, handler);
    }

    @Override
    public long totalConnection() { return counter.sum(); }

    Mono<Void> reloadChildDevices(List<String> configuredChildren) {
        return Mono.defer(() -> {
            List<String> previous = childDeviceIds;
            List<String> next = List.copyOf(configuredChildren);
            return TcpSessionOwner.validateChildren(registry, gatewayDeviceId, next).then(Mono.defer(() -> {
            List<String> removed = new ArrayList<>(previous);
            removed.removeAll(next);
            childDeviceIds = next;
            return Flux.fromIterable(connections)
                .concatMap(connection -> Flux.fromIterable(removed).concatMap(connection.owner::remove)
                    .then(connection.bindConfiguredChildren()))
                .then()
                .onErrorResume(error -> {
                    childDeviceIds = previous;
                    List<String> added = new ArrayList<>(next);
                    added.removeAll(previous);
                    return Flux.fromIterable(connections)
                        .concatMap(connection -> Flux.fromIterable(added).concatMap(connection.owner::remove)
                            .then(connection.bindConfiguredChildren()))
                        .then().onErrorResume(rollback -> { error.addSuppressed(rollback); return Mono.empty(); })
                        .then(Mono.error(error));
                });
            }));
        });
    }

    Mono<Void> bindConfiguredChildren(DeviceSession parent) {
        TcpDeviceSession tcp = TcpSessionOwner.tcpSession(parent);
        if (tcp == null || !tcp.owner().accessId.equals(getId())) return Mono.empty();
        TcpSessionOwner owner = tcp.owner();
        return owner.track(() -> Flux.fromIterable(childDeviceIds)
            .concatMap(id -> registry.getDevice(id).flatMap(child -> child.getConfig(DeviceConfigKey.parentGatewayId)
                .filter(parent.getDeviceId()::equals)
                .switchIfEmpty(Mono.error(new IllegalStateException("Child [" + id + "] does not belong to parent [" + parent.getDeviceId() + "]")))
                .then(Mono.defer(() -> {
                    if (!owner.isActive() || !childDeviceIds.contains(id)) return Mono.empty();
                    AtomicReference<Throwable> failure = new AtomicReference<>();
                    Mono<DeviceSession> create = Mono.fromSupplier(() -> new ChildrenDeviceSession(id, parent, child));
                    return sessionManager.compute(id, create, existing -> owner.update(existing, () -> create,
                        () -> Mono.just(existing)).onErrorResume(error -> {
                            failure.set(error);
                            return Mono.just(existing);
                        }))
                        .flatMap(session -> failure.get() == null ? Mono.just(session) : Mono.error(failure.get()))
                        .doOnNext(owner::accepted);
                }))))
            .then());
    }

    class TcpConnection implements DeviceGatewayContext {
        final TcpClient client;
        final AtomicReference<DeviceSession> sessionRef = new AtomicReference<>();
        final InetSocketAddress address;
        final TcpSessionOwner owner = new TcpSessionOwner(getId(), sessionManager);
        final Sinks.Empty<Void> disconnected = Sinks.empty();
        final AtomicBoolean cleaned = new AtomicBoolean();
        volatile Disposable legalityChecker;

        TcpConnection(TcpClient client) {
            this.client = client;
            this.address = client.getRemoteAddress();
            sessionRef.set(new UnknownTcpDeviceSession(client.getId(), client, DefaultTransport.TCP, monitor));
        }

        void terminate() {
            owner.invalidate();
            disconnected.tryEmitEmpty();
            closeClient(client);
        }

        Mono<Void> cleanup() {
            owner.invalidate();
            if (cleaned.compareAndSet(false, true)) {
                Disposable checker = legalityChecker;
                if (checker != null) checker.dispose();
                if (connections.remove(this)) {
                    counter.decrement();
                    monitor.disconnected();
                    monitor.totalConnection(counter.sum());
                }
                closeClient(client);
            }
            return owner.close();
        }

        private Mono<Void> bindConfiguredChildren() {
            return Mono.defer(() -> Mono.justOrEmpty(sessionRef.get())
                .filter(session -> session.isWrapFrom(TcpDeviceSession.class) && owner.owns(session))
                .flatMap(TcpServerDeviceGateway.this::bindConfiguredChildren));
        }

        public void checkLegality() {
            if (sessionRef.get() instanceof UnknownTcpDeviceSession) {
                log.info("tcp [{}] connection has no registered device, close it", address);
                terminate();
            }
        }

        Mono<Void> accept() {
            return Mono.defer(() -> {
                synchronized (connections) {
                    if (!started.get()) { closeClient(client); return Mono.empty(); }
                    connections.add(this);
                    counter.increment();
                }
                monitor.connected();
                monitor.totalConnection(counter.sum());
                client.onDisconnect(() -> { owner.invalidate(); disconnected.tryEmitEmpty(); });
                legalityChecker = Schedulers.parallel().schedule(this::checkLegality,
                    connectCheckTimeout.toMillis(), TimeUnit.MILLISECONDS);
                Mono<Void> prebind = Mono.empty();
                if (gatewayDeviceId != null && !gatewayDeviceId.isEmpty()) {
                    DeviceOnlineMessage online = new DeviceOnlineMessage();
                    online.setDeviceId(gatewayDeviceId);
                    prebind = handleDeviceMessage(online).then(bindConfiguredChildren());
                }
                Mono<Void> accepted = prebind
                    .then(getProtocol().flatMap(pt -> pt.onClientConnect(DefaultTransport.TCP, client, this)))
                    .then(client.subscribe().filter(message -> started.get() && owner.isActive())
                        .concatMap(this::handleTcpMessage)
                        .doOnDiscard(TcpMessage.class, message -> ReferenceCountUtil.safeRelease(message.getPayload()))
                        .then())
                    .takeUntilOther(disconnected.asMono());
                return Mono.usingWhen(Mono.just(this), ignored -> accepted,
                    ignored -> cleanup(), (ignored, error) -> cleanup(), ignored -> cleanup());
            });
        }

        Mono<Void> handleTcpMessage(TcpMessage message) {
            return getProtocol().flatMap(pt -> pt.getMessageCodec(DefaultTransport.TCP))
                .flatMapMany(codec -> codec.decode(FromDeviceMessageContext.of(sessionRef.get(), message,
                    registry, decoded -> handleDeviceMessage(decoded).then())))
                .cast(DeviceMessage.class).concatMap(this::handleDeviceMessage)
                .onErrorResume(error -> { log.warn("handle TCP [{}] message failed", address, error); return Mono.empty(); })
                .subscribeOn(Schedulers.parallel()).then()
                .doFinally(ignored -> ReferenceCountUtil.safeRelease(message.getPayload()));
        }

        Mono<DeviceMessage> handleDeviceMessage(DeviceMessage message) {
            if (message.getDeviceId() == null || message.getDeviceId().trim().isEmpty()) return Mono.empty();
            monitor.receivedMessage();
            return owner.track(() -> owner.validate(registry, message.getDeviceId(), true)
                .then(helper.handleDeviceMessage(message,
                    device -> owner.validate(registry, device.getDeviceId(), true)
                        .then(Mono.defer(() -> owner.isActive()
                            ? Mono.just(new TcpDeviceSession(device, client, DefaultTransport.TCP, monitor, true, owner)) : Mono.empty())),
                    session -> {
                        if (owner.isActive() && owner.owns(session)) {
                            sessionRef.set(session);
                            Disposable checker = legalityChecker;
                            if (checker != null) checker.dispose();
                        }
                        return Mono.empty();
                    }, () -> { log.warn("TCP [{}] device [{}] is not registered", address, message.getDeviceId()); return Mono.empty(); }, owner))
                .map(ignored -> message));
        }

        @Override
        public Mono<DeviceOperator> getDevice(String deviceId) { return registry.getDevice(deviceId); }
        @Override
        public Mono<DeviceProductOperator> getProduct(String productId) { return registry.getProduct(productId); }
        @Override
        public Mono<Void> onMessage(DeviceMessage message) { return handleDeviceMessage(message).then(); }
    }

    private void closeClient(TcpClient client) {
        try { client.shutdown(); } catch (Throwable error) { log.debug("close TCP client failed", error); }
    }

    private void doStart() {
        if (started.getAndSet(true)) return;
        disposable = tcpServer.handleConnection()
            .onBackpressureBuffer(1024, this::closeClient)
            .publishOn(Schedulers.parallel())
            .flatMap(client -> new TcpConnection(client).accept().onErrorResume(error -> {
                log.warn("TCP client acceptance failed", error);
                return Mono.empty();
            }), Integer.MAX_VALUE)
            .doOnDiscard(TcpClient.class, this::closeClient)
            .contextWrite(ReactiveLogger.start("network", tcpServer.getId()))
            .subscribe(ignored -> { }, error -> log.error("TCP gateway accept stream failed", error));
    }

    @Override
    protected Mono<Void> doStartup() { return Mono.fromRunnable(this::doStart); }

    @Override
    protected Mono<Void> doShutdown() {
        return Mono.defer(() -> {
            List<TcpConnection> closing;
            synchronized (connections) {
                started.set(false);
                closing = new ArrayList<>(connections);
                closing.forEach(connection -> connection.owner.invalidate());
            }
            Disposable current = disposable;
            disposable = null;
            if (current != null) current.dispose();
            return Flux.fromIterable(closing).flatMapDelayError(TcpConnection::cleanup, 32, 1).then();
        });
    }
}
