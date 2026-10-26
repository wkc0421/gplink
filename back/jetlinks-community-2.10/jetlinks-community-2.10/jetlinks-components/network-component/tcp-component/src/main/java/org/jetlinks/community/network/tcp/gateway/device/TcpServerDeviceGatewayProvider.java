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

import org.jetlinks.core.ProtocolSupports;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.device.session.DeviceSessionManager;
import org.jetlinks.core.message.codec.DefaultTransport;
import org.jetlinks.core.message.codec.Transport;
import org.jetlinks.community.gateway.DeviceGateway;
import org.jetlinks.community.gateway.supports.DeviceGatewayProperties;
import org.jetlinks.community.gateway.supports.DeviceGatewayProvider;
import org.jetlinks.community.network.DefaultNetworkType;
import org.jetlinks.community.network.NetworkManager;
import org.jetlinks.community.network.NetworkType;
import org.jetlinks.community.network.tcp.server.TcpServer;
import org.jetlinks.supports.server.DecodedClientMessageHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class TcpServerDeviceGatewayProvider implements DeviceGatewayProvider {

    private final NetworkManager networkManager;

    private final DeviceRegistry registry;

    private final DeviceSessionManager sessionManager;

    private final DecodedClientMessageHandler messageHandler;

    private final ProtocolSupports protocolSupports;


    public TcpServerDeviceGatewayProvider(NetworkManager networkManager,
                                          DeviceRegistry registry,
                                          DeviceSessionManager sessionManager,
                                          DecodedClientMessageHandler messageHandler,
                                          ProtocolSupports protocolSupports) {
        this.networkManager = networkManager;
        this.registry = registry;
        this.sessionManager = sessionManager;
        this.messageHandler = messageHandler;
        this.protocolSupports = protocolSupports;
    }

    @Override
    public String getId() {
        return "tcp-server-gateway";
    }

    @Override
    public String getName() {
        return "TCP 透传接入";
    }

    public NetworkType getNetworkType() {
        return DefaultNetworkType.TCP_SERVER;
    }

    public Transport getTransport() {
        return DefaultTransport.TCP;
    }

    private List<String> children(DeviceGatewayProperties properties) {
        Object value = properties.getConfiguration().get("childDeviceIds");
        java.util.stream.Stream<?> stream = value instanceof Iterable
            ? java.util.stream.StreamSupport.stream(((Iterable<?>) value).spliterator(), false)
            : value == null ? java.util.stream.Stream.empty() : java.util.Arrays.stream(String.valueOf(value).split(","));
        return stream.map(String::valueOf).map(String::trim).filter(id -> !id.isEmpty()).distinct().collect(Collectors.toList());
    }

    private Mono<Void> validate(DeviceGatewayProperties properties) {
        return Mono.defer(() -> {
            Assert.hasText(properties.getId(), "gateway id can not be empty");
            Assert.hasText(properties.getProtocol(), "protocol can not be empty");
            Assert.hasText(properties.getChannelId(), "network channel can not be empty");
            Object device = properties.getConfiguration().get("deviceId");
            Assert.isTrue(device == null || device instanceof String, "deviceId must be a string");
            return protocolSupports.getProtocol(properties.getProtocol())
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Protocol does not exist: " + properties.getProtocol())))
                .then(device == null || ((String) device).isEmpty() ? Mono.empty()
                    : registry.getDevice((String) device).flatMap(ignored -> TcpSessionOwner.validateAccess(
                        registry, (String) device, properties.getId(), true)))
                .then(TcpSessionOwner.validateChildren(registry, (String) device, children(properties)));
        });
    }

    @Override
    public Mono<DeviceGateway> createDeviceGateway(DeviceGatewayProperties properties) {
        return validate(properties).then(Mono.defer(() -> networkManager
            .<TcpServer>getNetwork(getNetworkType(), properties.getChannelId())
            .map(server -> {
                String protocol = properties.getProtocol();
                return new TcpServerDeviceGateway(properties.getId(),
                    Mono.defer(() -> protocolSupports.getProtocol(protocol)),
                    registry, messageHandler, sessionManager, server,
                    (String) properties.getConfiguration().get("deviceId"), children(properties));
            })));
    }

    @Override
    public Mono<? extends DeviceGateway> reloadDeviceGateway(DeviceGateway gateway, DeviceGatewayProperties properties) {
        TcpServerDeviceGateway current = (TcpServerDeviceGateway) gateway;
        return validate(properties).then(Mono.defer(() -> {
            if (!Objects.equals(current.tcpServer.getId(), properties.getChannelId())
                || !Objects.equals(current.getGatewayDeviceId(), properties.getConfiguration().get("deviceId"))) {
                return TcpGatewayReload.replace(current, createDeviceGateway(properties));
            }
            String protocol = properties.getProtocol();
            return current.reloadChildDevices(children(properties))
                .doOnSuccess(ignored -> current.protocol = Mono.defer(() -> protocolSupports.getProtocol(protocol)))
                .thenReturn(gateway);
        }));
    }
}
