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
import lombok.extern.slf4j.Slf4j;
import io.netty.util.ReferenceCountUtil;
import org.hswebframework.web.logger.ReactiveLogger;
import org.jetlinks.community.gateway.AbstractDeviceGateway;
import org.jetlinks.community.gateway.DeviceGateway;
import org.jetlinks.community.gateway.DeviceGatewayHelper;
import org.jetlinks.community.gateway.monitor.MonitorSupportDeviceGateway;
import org.jetlinks.community.network.tcp.TcpMessage;
import org.jetlinks.community.network.tcp.client.TcpClient;
import org.jetlinks.core.ProtocolSupport;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceProductOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.device.session.DeviceSessionManager;
import org.jetlinks.core.message.DeviceMessage;
import org.jetlinks.core.message.DeviceOnlineMessage;
import org.jetlinks.core.message.codec.DefaultTransport;
import org.jetlinks.core.message.codec.FromDeviceMessageContext;
import org.jetlinks.core.server.DeviceGatewayContext;
import org.jetlinks.core.server.session.DeviceSession;
import org.jetlinks.core.trace.DeviceTracer;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.supports.server.DecodedClientMessageHandler;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TCP-Client variant of the device gateway. The platform dials out to a
 * remote TCP endpoint (e.g. a Modbus gateway that only exposes a TCP
 * server) and treats the single client connection as one logical
 * gateway session. Mirrors {@link TcpServerDeviceGateway} but without
 * the accept-loop.
 */
@Slf4j
class TcpClientDeviceGateway extends AbstractDeviceGateway
        implements DeviceGateway, MonitorSupportDeviceGateway, DeviceGatewayContext {

    final TcpClient tcpClient;

    @Getter
    Mono<ProtocolSupport> protocol;

    private final DeviceRegistry registry;

    private final DeviceSessionManager sessionManager;

    private final DeviceGatewayHelper helper;

    /**
     * Optional pre-configured gateway device id. Modbus RTU has no identity
     * handshake, so TCP client mode needs an explicit id to bind the logical
     * gateway session before child-device reads can be sent.
     */
    private final AtomicReference<String> gatewayDeviceId = new AtomicReference<>();

    private final AtomicBoolean started = new AtomicBoolean();

    private final AtomicBoolean connected = new AtomicBoolean();

    private final AtomicBoolean binding = new AtomicBoolean();

    private final AtomicReference<DeviceSession> sessionRef = new AtomicReference<>();

    private final AtomicReference<Disposable.Composite> subscriptions = new AtomicReference<>();
    private final AtomicReference<TcpSessionOwner> connectionOwner = new AtomicReference<>();

    TcpClientDeviceGateway(String id,
                           Mono<ProtocolSupport> protocol,
                           DeviceRegistry registry,
                           DecodedClientMessageHandler clientMessageHandler,
                           DeviceSessionManager sessionManager,
                           TcpClient tcpClient,
                           String gatewayDeviceId) {
        super(id);
        this.protocol = protocol;
        this.registry = registry;
        this.sessionManager = sessionManager;
        this.tcpClient = tcpClient;
        this.gatewayDeviceId.set(gatewayDeviceId);
        this.helper = new DeviceGatewayHelper(registry, sessionManager, clientMessageHandler);
    }

    @Override
    public long totalConnection() {
        return connected.get() ? 1 : 0;
    }

    @Override
    protected Mono<Void> doStartup() {
        return Mono.fromRunnable(this::doStart);
    }

    @Override
    protected Mono<Void> doShutdown() {
        return Mono.defer(() -> {
            TcpSessionOwner owner;
            synchronized (connected) {
                started.set(false);
                owner = connectionOwner.getAndSet(null);
                if (owner != null) owner.invalidate();
            }
            Disposable.Composite lifecycle = subscriptions.getAndSet(null);
            if (lifecycle != null) {
                lifecycle.dispose();
            }
            if (connected.getAndSet(false)) {
                monitor.disconnected();
                monitor.totalConnection(0);
            }
            sessionRef.set(null);
            return (owner == null ? Mono.<Void>empty() : owner.close())
                .onErrorResume(error -> Mono.defer(tcpClient::shutdownAsync)
                    .onErrorResume(closeError -> { error.addSuppressed(closeError); return Mono.empty(); })
                    .then(Mono.error(error)))
                .then(Mono.defer(tcpClient::shutdownAsync))
                .doOnCancel(tcpClient::shutdown);
        });
    }

    private void doStart() {
        if (started.getAndSet(true)) {
            return;
        }
        Disposable.Composite lifecycle = Disposables.composite();
        Disposable.Composite previous = subscriptions.getAndSet(lifecycle);
        if (previous != null) {
            previous.dispose();
        }
        sessionRef.set(new UnknownTcpDeviceSession(tcpClient.getId(), tcpClient, DefaultTransport.TCP, monitor));

        Disposable state = tcpClient
                .connectionState()
                .distinctUntilChanged()
                .doOnNext(online -> {
                    if (!online) {
                        TcpSessionOwner owner = connectionOwner.get();
                        if (owner != null) owner.invalidate();
                    }
                })
                .concatMap(stateValue -> handleConnectionState(stateValue)
                        .onErrorResume(error -> {
                            log.warn("handle TCP client gateway[{}] connection state [{}] failed",
                                     getId(), stateValue, error);
                            return Mono.empty();
                        }))
                .subscribe();
        lifecycle.add(state);

        // The socket can connect before the configured device is enabled. A later
        // registration must not require a new physical connection or a restart.
        lifecycle.add(Flux.interval(Duration.ofSeconds(5))
                .concatMap(ignore -> reconcileConfiguredGatewayDevice()
                        .onErrorResume(error -> {
                            log.warn("recover TCP client gateway[{}] device session failed", getId(), error);
                            return Mono.empty();
                        }))
                .subscribe());

        Disposable recv = tcpClient
                .subscribe()
                .filter(msg -> started.get())
                .concatMap(this::handleTcpMessage)
                .doOnDiscard(TcpMessage.class, message -> ReferenceCountUtil.safeRelease(message.getPayload()))
                .onErrorResume(err -> {
                    log.error("TCP client gateway[{}] receive error", getId(), err);
                    return Mono.empty();
                })
                .contextWrite(ReactiveLogger.start("network", tcpClient.getId()))
                .subscribeOn(Schedulers.parallel())
                .subscribe();
        lifecycle.add(recv);

    }

    private Mono<Void> handleConnectionState(boolean online) {
        if (!started.get()) {
            return Mono.empty();
        }
        return online ? handleConnected() : handleDisconnected();
    }

    private Mono<Void> handleConnected() {
        synchronized (connected) {
            if (!started.get() || !connected.compareAndSet(false, true)) {
                return Mono.empty();
            }
            connectionOwner.set(new TcpSessionOwner(getId(), sessionManager));
            sessionRef.set(new UnknownTcpDeviceSession(tcpClient.getId(), tcpClient, DefaultTransport.TCP, monitor));
        }
        monitor.connected();
        monitor.totalConnection(1);

        return bindConfiguredGatewayDevice()
                .onErrorResume(ignore -> Mono.empty())
                .then(getProtocol()
                        .flatMap(pt -> pt.onClientConnect(DefaultTransport.TCP, tcpClient, this))
                         .timeout(Duration.ofSeconds(10))
                        .onErrorResume(err -> {
                            log.warn("protocol onClientConnect failed", err);
                            return Mono.empty();
                        }));
    }

    private Mono<Void> handleDisconnected() {
        if (!connected.compareAndSet(true, false)) {
            return Mono.empty();
        }
        monitor.disconnected();
        monitor.totalConnection(0);

        TcpSessionOwner owner = connectionOwner.getAndSet(null);
        sessionRef.set(new UnknownTcpDeviceSession(tcpClient.getId(), tcpClient, DefaultTransport.TCP, monitor));
        return owner == null ? Mono.empty() : owner.close();
    }

    private Mono<Void> bindConfiguredGatewayDevice() {
        return Mono.defer(() -> {
            String deviceId = gatewayDeviceId.get();
            if (!started.get() || !connected.get() || deviceId == null || deviceId.isEmpty()
                    || !binding.compareAndSet(false, true)) {
                return Mono.empty();
            }
            return bindDevice(deviceId)
                    .doFinally(ignore -> binding.set(false));
        });
    }

    private Mono<Void> bindDevice(String deviceId) {
        if (!started.get() || !connected.get() || deviceId == null || deviceId.isEmpty()) return Mono.empty();
        DeviceOnlineMessage online = new DeviceOnlineMessage();
        online.setDeviceId(deviceId);
        online.timestamp(System.currentTimeMillis());
        return registry.getDevice(deviceId).flatMap(device -> handleDeviceMessage(online))
            .doOnNext(ignore -> log.info("TCP client gateway[{}] bound configured device [{}]", getId(), deviceId))
            .doOnError(error -> log.warn("bind configured gateway device [{}] failed", deviceId, error)).then();
    }

    private Mono<Void> reconcileConfiguredGatewayDevice() {
        return Mono.defer(() -> {
            String deviceId = gatewayDeviceId.get();
            if (!started.get() || !connected.get() || !tcpClient.isAlive()
                    || deviceId == null || deviceId.isEmpty()) {
                return Mono.empty();
            }
            return sessionManager.getSession(deviceId, false)
                    .filter(session -> session.isAlive()
                            && session.isWrapFrom(TcpDeviceSession.class)
                            && session.unwrap(TcpDeviceSession.class) == sessionRef.get())
                    .hasElement()
                    // Do not ping or re-register healthy sessions: only inbound
                    // traffic should refresh the TCP client's idle timeout.
                    .flatMap(healthy -> healthy ? Mono.empty() : bindConfiguredGatewayDevice());
        });
    }

    Mono<Void> updateGatewayDeviceId(String deviceId) {
        return Flux.interval(Duration.ZERO, Duration.ofMillis(10))
            .filter(ignored -> binding.compareAndSet(false, true)).next().timeout(Duration.ofSeconds(10))
            .flatMap(ignored -> Mono.defer(() -> {
            String previous = gatewayDeviceId.get();
            TcpSessionOwner owner = connectionOwner.get();
            Mono<Void> validate = owner == null || deviceId == null || deviceId.isEmpty() ? Mono.empty()
                : registry.getDevice(deviceId).flatMap(device -> owner.validate(registry, deviceId, true));
            return validate.then(Mono.defer(() -> {
                gatewayDeviceId.set(deviceId);
                return bindDevice(deviceId)
                    .then(owner != null && previous != null && !previous.isEmpty() && !Objects.equals(previous, deviceId)
                        ? owner.remove(previous).then() : Mono.empty())
                    .onErrorResume(error -> {
                        gatewayDeviceId.set(previous);
                        Mono<Void> rollback = owner != null && deviceId != null && !deviceId.isEmpty() && !Objects.equals(previous, deviceId)
                            ? owner.remove(deviceId).then() : Mono.empty();
                        return rollback.then(bindDevice(previous))
                            .onErrorResume(rollbackError -> {
                                error.addSuppressed(rollbackError);
                                return Mono.empty();
                            }).then(Mono.error(error));
                    });
            }));
        }).doFinally(ignoredSignal -> binding.set(false)));
    }

    private Mono<Void> handleTcpMessage(TcpMessage message) {
        return getProtocol()
                .flatMap(pt -> pt.getMessageCodec(DefaultTransport.TCP))
                .flatMapMany(codec -> codec.decode(FromDeviceMessageContext.of(
                        sessionRef.get(), message, registry, msg -> handleDeviceMessage(msg).then())))
                .cast(DeviceMessage.class)
                .concatMap(msg -> handleDeviceMessage(msg)
                        .as(MonoTracer.create(
                                DeviceTracer.SpanName.decode(msg.getDeviceId()),
                                (span, _msg) -> span.setAttributeLazy(DeviceTracer.SpanKey.message, _msg::toString))))
                .onErrorResume(err -> {
                    log.error("handle TCP client[{}] message failed", tcpClient.getId(), err);
                    return Mono.empty();
                })
                .subscribeOn(Schedulers.parallel())
                .then()
                .doFinally(ignored -> ReferenceCountUtil.safeRelease(message.getPayload()));
    }

    private Mono<DeviceMessage> handleDeviceMessage(DeviceMessage message) {
        if (message.getDeviceId() == null || message.getDeviceId().trim().isEmpty()) return Mono.empty();
        monitor.receivedMessage();
        TcpSessionOwner owner = connectionOwner.get();
        if (owner == null) return Mono.empty();
        return owner.track(() -> owner.validate(registry, message.getDeviceId(), true)
            .then(helper.handleDeviceMessage(
                        message,
                        device -> owner.validate(registry, device.getDeviceId(), true)
                            .then(Mono.defer(() -> owner.isActive()
                                ? Mono.just(new TcpDeviceSession(device, tcpClient, DefaultTransport.TCP, monitor, false, owner)) : Mono.empty())),
                        session -> {
                            TcpDeviceSession deviceSession = session.unwrap(TcpDeviceSession.class);
                            if (owner.isActive() && connectionOwner.get() == owner) sessionRef.set(deviceSession);
                            return Mono.empty();
                        },
                        () -> {
                            log.warn("TCP client gateway[{}]: device[{}] not found: {}", getId(), message.getDeviceId(), message);
                            return Mono.empty();
                        }, owner)).map(ignored -> message));
    }

    @Override
    public Mono<DeviceOperator> getDevice(String deviceId) {
        return registry.getDevice(deviceId);
    }

    @Override
    public Mono<DeviceProductOperator> getProduct(String productId) {
        return registry.getProduct(productId);
    }

    @Override
    public Mono<Void> onMessage(DeviceMessage message) {
        return handleDeviceMessage(message).then();
    }
}
