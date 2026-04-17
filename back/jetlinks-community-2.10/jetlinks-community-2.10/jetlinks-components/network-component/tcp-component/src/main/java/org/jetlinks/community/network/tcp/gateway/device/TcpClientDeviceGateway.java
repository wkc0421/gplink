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
import org.jetlinks.core.message.codec.DefaultTransport;
import org.jetlinks.core.message.codec.FromDeviceMessageContext;
import org.jetlinks.core.server.DeviceGatewayContext;
import org.jetlinks.core.server.session.DeviceSession;
import org.jetlinks.core.trace.DeviceTracer;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.supports.server.DecodedClientMessageHandler;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

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

    private final LongAdder counter = new LongAdder();

    private final AtomicBoolean started = new AtomicBoolean();

    private final AtomicReference<DeviceSession> sessionRef = new AtomicReference<>();

    private final Disposable.Composite subscriptions = Disposables.composite();

    TcpClientDeviceGateway(String id,
                           Mono<ProtocolSupport> protocol,
                           DeviceRegistry registry,
                           DecodedClientMessageHandler clientMessageHandler,
                           DeviceSessionManager sessionManager,
                           TcpClient tcpClient) {
        super(id);
        this.protocol = protocol;
        this.registry = registry;
        this.sessionManager = sessionManager;
        this.tcpClient = tcpClient;
        this.helper = new DeviceGatewayHelper(registry, sessionManager, clientMessageHandler);
    }

    @Override
    public long totalConnection() {
        return counter.sum();
    }

    @Override
    protected Mono<Void> doStartup() {
        return Mono.fromRunnable(this::doStart);
    }

    @Override
    protected Mono<Void> doShutdown() {
        return Mono.fromRunnable(() -> {
            started.set(false);
            subscriptions.dispose();
            DeviceSession session = sessionRef.getAndSet(null);
            if (session != null) {
                session.close();
            }
        });
    }

    private void doStart() {
        if (started.getAndSet(true)) {
            return;
        }
        sessionRef.set(new UnknownTcpDeviceSession(tcpClient.getId(), tcpClient, DefaultTransport.TCP, monitor));
        counter.increment();
        monitor.connected();
        monitor.totalConnection(counter.sum());

        tcpClient.onDisconnect(() -> {
            counter.decrement();
            monitor.disconnected();
            monitor.totalConnection(counter.sum());
            DeviceSession current = sessionRef.get();
            if (current != null && current.getDeviceId() != null) {
                sessionManager
                    .getSession(current.getDeviceId())
                    .subscribe(null, err -> log.warn("get session on disconnect error", err));
            }
        });

        Disposable recv = tcpClient
                .subscribe()
                .filter(msg -> started.get())
                .concatMap(this::handleTcpMessage)
                .onErrorResume(err -> {
                    log.error("TCP client gateway[{}] receive error", getId(), err);
                    return Mono.empty();
                })
                .contextWrite(ReactiveLogger.start("network", tcpClient.getId()))
                .subscribeOn(Schedulers.parallel())
                .subscribe();
        subscriptions.add(recv);

        Disposable connectHook = getProtocol()
                .flatMap(pt -> pt.onClientConnect(DefaultTransport.TCP, tcpClient, this))
                .onErrorResume(err -> {
                    log.warn("protocol onClientConnect failed", err);
                    return Mono.empty();
                })
                .subscribe();
        subscriptions.add(connectHook);
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
                .then();
    }

    private Mono<DeviceMessage> handleDeviceMessage(DeviceMessage message) {
        monitor.receivedMessage();
        return helper
                .handleDeviceMessage(
                        message,
                        device -> new TcpDeviceSession(device, tcpClient, DefaultTransport.TCP, monitor),
                        session -> {
                            TcpDeviceSession deviceSession = session.unwrap(TcpDeviceSession.class);
                            deviceSession.setClient(tcpClient);
                            sessionRef.set(deviceSession);
                        },
                        () -> log.warn("TCP client gateway[{}]: device[{}] not found: {}",
                                getId(), message.getDeviceId(), message))
                .thenReturn(message);
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
