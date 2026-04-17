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

import org.jetlinks.community.gateway.DeviceGateway;
import org.jetlinks.community.gateway.supports.DeviceGatewayProperties;
import org.jetlinks.community.gateway.supports.DeviceGatewayProvider;
import org.jetlinks.community.network.DefaultNetworkType;
import org.jetlinks.community.network.NetworkManager;
import org.jetlinks.community.network.NetworkType;
import org.jetlinks.community.network.tcp.client.TcpClient;
import org.jetlinks.core.ProtocolSupports;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.device.session.DeviceSessionManager;
import org.jetlinks.core.message.codec.DefaultTransport;
import org.jetlinks.core.message.codec.Transport;
import org.jetlinks.supports.server.DecodedClientMessageHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import reactor.core.publisher.Mono;

import java.util.Objects;

@Component
public class TcpClientDeviceGatewayProvider implements DeviceGatewayProvider {

    private final NetworkManager networkManager;

    private final DeviceRegistry registry;

    private final DeviceSessionManager sessionManager;

    private final DecodedClientMessageHandler messageHandler;

    private final ProtocolSupports protocolSupports;

    public TcpClientDeviceGatewayProvider(NetworkManager networkManager,
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
        return "tcp-client-gateway";
    }

    @Override
    public String getName() {
        return "TCP 客户端接入";
    }

    public NetworkType getNetworkType() {
        return DefaultNetworkType.TCP_CLIENT;
    }

    @Override
    public Transport getTransport() {
        return DefaultTransport.TCP;
    }

    @Override
    public Mono<DeviceGateway> createDeviceGateway(DeviceGatewayProperties properties) {
        return networkManager
                .<TcpClient>getNetwork(getNetworkType(), properties.getChannelId())
                .map(client -> {
                    String protocol = properties.getProtocol();
                    Assert.hasText(protocol, "protocol can not be empty");
                    return new TcpClientDeviceGateway(
                            properties.getId(),
                            Mono.defer(() -> protocolSupports.getProtocol(protocol)),
                            registry,
                            messageHandler,
                            sessionManager,
                            client
                    );
                });
    }

    @Override
    public Mono<? extends DeviceGateway> reloadDeviceGateway(DeviceGateway gateway,
                                                             DeviceGatewayProperties properties) {
        TcpClientDeviceGateway current = (TcpClientDeviceGateway) gateway;
        if (!Objects.equals(current.tcpClient.getId(), properties.getChannelId())) {
            return gateway
                    .shutdown()
                    .then(createDeviceGateway(properties))
                    .flatMap(newer -> newer.startup().thenReturn(newer));
        }
        String protocol = properties.getProtocol();
        current.protocol = Mono.defer(() -> protocolSupports.getProtocol(protocol));
        return Mono.just(gateway);
    }
}
