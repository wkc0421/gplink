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

import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.bean.FastBeanCopier;
import org.hswebframework.web.i18n.LocaleUtils;
import org.jetlinks.community.network.DefaultNetworkType;
import org.jetlinks.community.network.Network;
import org.jetlinks.community.network.NetworkProvider;
import org.jetlinks.community.network.NetworkProperties;
import org.jetlinks.community.network.NetworkType;
import org.jetlinks.community.network.security.CertificateManager;
import org.jetlinks.community.network.security.VertxKeyCertTrustOptions;
import org.jetlinks.community.network.tcp.parser.PayloadParser;
import org.jetlinks.community.network.tcp.parser.PayloadParserBuilder;
import org.jetlinks.community.network.tcp.parser.PayloadParserType;
import org.jetlinks.core.metadata.ConfigMetadata;
import org.jetlinks.core.metadata.DefaultConfigMetadata;
import org.jetlinks.core.metadata.types.BooleanType;
import org.jetlinks.core.metadata.types.IntType;
import org.jetlinks.core.metadata.types.ObjectType;
import org.jetlinks.core.metadata.types.StringType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * TCP client network component used by the TCP client device gateway.
 *
 * <p>The device gateway provider for {@code tcp-client-gateway} has existed
 * independently of the network provider. Without this component the gateway
 * can be selected but cannot obtain a {@link TcpClient} from
 * {@code NetworkManager}.</p>
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "jetlinks.network.tcp-client")
public class DefaultTcpClientProvider implements NetworkProvider<TcpClientProperties> {

    private final CertificateManager certificateManager;

    private final Vertx vertx;

    private final PayloadParserBuilder payloadParserBuilder;

    @Getter
    @Setter
    private NetClientOptions template = new NetClientOptions();

    public DefaultTcpClientProvider(CertificateManager certificateManager,
                                    Vertx vertx,
                                    PayloadParserBuilder payloadParserBuilder) {
        this.certificateManager = certificateManager;
        this.vertx = vertx;
        this.payloadParserBuilder = payloadParserBuilder;
        template.setTcpKeepAlive(true);
    }

    @Nonnull
    @Override
    public NetworkType getType() {
        return DefaultNetworkType.TCP_CLIENT;
    }

    @Nonnull
    @Override
    public Mono<Network> createNetwork(@Nonnull TcpClientProperties properties) {
        return initTcpClient(new VertxTcpClient(properties.getId(), false), properties);
    }

    @Override
    public Mono<Network> reload(@Nonnull Network network,
                                @Nonnull TcpClientProperties properties) {
        return initTcpClient((VertxTcpClient) network, properties);
    }

    private Mono<Network> initTcpClient(VertxTcpClient tcpClient,
                                        TcpClientProperties properties) {
        return convert(properties)
            .map(options -> {
                Supplier<PayloadParser> parserSupplier = payloadParserBuilder
                    .build(properties.getParserType() == null
                               ? PayloadParserType.DIRECT
                               : properties.getParserType(),
                           properties);

                PayloadParser parser = parserSupplier.get();
                NetClient client;
                try {
                    client = vertx.createNetClient(options);
                } catch (RuntimeException | Error error) {
                    try {
                        parser.close();
                    } catch (Throwable closeError) {
                        error.addSuppressed(closeError);
                    }
                    throw error;
                }
                long connectionVersion = tcpClient.replaceClient(client);
                try {
                    tcpClient.setRecordParser(parser);
                    client.connect(properties.getPort(), properties.getHost(), result -> {
                        if (result.succeeded()) {
                            if (tcpClient.setSocket(result.result(), connectionVersion)) {
                                log.info("tcp client [{}] connected to {}:{}",
                                         properties.getId(),
                                         properties.getHost(),
                                         properties.getPort());
                            } else {
                                log.debug("ignore stale tcp client [{}] connection to {}:{}",
                                          properties.getId(),
                                          properties.getHost(),
                                          properties.getPort());
                            }
                        } else {
                            tcpClient.handleConnectFailure(client, connectionVersion);
                            log.warn("tcp client [{}] connect to {}:{} failed",
                                     properties.getId(),
                                     properties.getHost(),
                                     properties.getPort(),
                                     result.cause());
                        }
                    });
                } catch (RuntimeException | Error error) {
                    tcpClient.handleConnectFailure(client, connectionVersion);
                    throw error;
                }
                return tcpClient;
            });
    }

    @Nullable
    @Override
    public ConfigMetadata getConfigMetadata() {
        return new DefaultConfigMetadata()
            .add("id", "id", "", new StringType())
            .add("host", "远程地址", "", new StringType())
            .add("port", "远程端口", "", new IntType())
            .add("certId", "证书ID", "", new StringType())
            .add("ssl", "启用TLS", "", new BooleanType())
            .add("parserType", "解析器类型", "", new StringType())
            .add("parserConfiguration", "解析器配置", "", new ObjectType());
    }

    @Nonnull
    @Override
    public Mono<TcpClientProperties> createConfig(@Nonnull NetworkProperties properties) {
        return Mono
            .defer(() -> {
                TcpClientProperties config = FastBeanCopier.copy(
                    properties.getConfigurations(),
                    new TcpClientProperties());
                config.setId(properties.getId());
                if (config.getParserType() == null) {
                    config.setParserType(PayloadParserType.DIRECT);
                }
                return Mono.just(config);
            })
            .as(LocaleUtils::transform);
    }

    private Mono<NetClientOptions> convert(TcpClientProperties config) {
        NetClientOptions options = new NetClientOptions(template);
        if (config.isSsl()) {
            return certificateManager
                .getCertificate(config.getCertId())
                .map(VertxKeyCertTrustOptions::new)
                .doOnNext(options::setKeyCertOptions)
                .doOnNext(options::setTrustOptions)
                .doOnNext(ignore -> options.setSsl(true))
                .thenReturn(options);
        }
        return Mono.just(options);
    }

    @Override
    public boolean isReusable() {
        return true;
    }
}
