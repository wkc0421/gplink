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

import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.bean.FastBeanCopier;
import org.hswebframework.web.i18n.LocaleUtils;
import org.jetlinks.community.network.*;
import org.jetlinks.core.metadata.ConfigMetadata;
import org.jetlinks.core.metadata.DefaultConfigMetadata;
import org.jetlinks.core.metadata.types.BooleanType;
import org.jetlinks.core.metadata.types.IntType;
import org.jetlinks.core.metadata.types.ObjectType;
import org.jetlinks.core.metadata.types.StringType;
import org.jetlinks.community.network.security.CertificateManager;
import org.jetlinks.community.network.security.VertxKeyCertTrustOptions;
import org.jetlinks.community.network.tcp.parser.PayloadParser;
import org.jetlinks.community.network.tcp.parser.PayloadParserBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.CancellationException;

/**
 * @author bestfeng
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "jetlinks.network.tcp-server")
public class DefaultTcpServerProvider implements NetworkProvider<TcpServerProperties> {

    private final CertificateManager certificateManager;

    private final Vertx vertx;

    private final PayloadParserBuilder payloadParserBuilder;

    @Getter
    @Setter
    private NetServerOptions template = new NetServerOptions();


    public DefaultTcpServerProvider(CertificateManager certificateManager, Vertx vertx, PayloadParserBuilder payloadParserBuilder) {
        this.certificateManager = certificateManager;
        this.vertx = vertx;
        this.payloadParserBuilder = payloadParserBuilder;
        template.setTcpKeepAlive(true);
    }

    @Nonnull
    @Override
    public NetworkType getType() {
        return DefaultNetworkType.TCP_SERVER;
    }

    @Nonnull
    @Override
    public Mono<Network> createNetwork(@Nonnull TcpServerProperties properties) {

        return Mono.defer(() -> initTcpServer(new VertxTcpServer(properties.getId()), properties));
    }

    private Mono<Network> initTcpServer(VertxTcpServer tcpServer, TcpServerProperties properties) {
        return convert(properties)
            .flatMap(options -> Mono.defer(() -> {
                Supplier<PayloadParser> parserSupplier = payloadParserBuilder.build(properties.getParserType(), properties);
                PayloadParser probe = parserSupplier.get();
                probe.close();
                InetSocketAddress bind = new InetSocketAddress(properties.getHost(), properties.getPort());
                long keepAliveTimeout = properties.getLong("keepAliveTimeout", Duration.ofMinutes(10).toMillis());
                int instance = Math.max(2, properties.getInstance());
                List<NetServer> instances = new ArrayList<>(instance);
                try {
                    for (int i = 0; i < instance; i++) {
                        instances.add(vertx.createNetServer(options));
                    }
                } catch (Throwable error) {
                    return closeServers(instances)
                        .onErrorResume(closeError -> {
                            error.addSuppressed(closeError);
                            return Mono.empty();
                        })
                        .then(Mono.error(error));
                }
                tcpServer.setParserSupplier(parserSupplier);
                tcpServer.setKeepAliveTimeout(keepAliveTimeout);
                tcpServer.setBind(bind);
                VertxTcpServer.ServerState state = tcpServer.installServers(instances);
                return Mono.fromRunnable(() -> tcpServer.initializeHandlers(state))
                    .thenMany(Flux.fromIterable(instances))
                    .flatMap(server -> listen(tcpServer, state, server, properties))
                    .then(Mono.<Network>fromCallable(() -> {
                        if (!tcpServer.markListening(state)) {
                            throw new CancellationException("TCP server stopped during startup");
                        }
                        return tcpServer;
                    }))
                    .onErrorResume(error -> {
                        tcpServer.setLastError(error.getMessage());
                        return tcpServer.shutdownAsync(state)
                            .onErrorResume(closeError -> {
                                error.addSuppressed(closeError);
                                return Mono.empty();
                            })
                            .then(Mono.error(error));
                    })
                    .doOnCancel(() -> tcpServer.shutdownAsync(state)
                        .subscribe(null, error -> log.warn("close cancelled TCP server failed", error)));
            }));
    }

    private Mono<Void> listen(VertxTcpServer tcpServer, VertxTcpServer.ServerState state,
                              NetServer server, TcpServerProperties properties) {
        return Mono.create(sink -> {
            synchronized (tcpServer) {
                if (!tcpServer.isCurrent(state)) {
                    sink.error(new CancellationException("TCP server startup was cancelled"));
                    return;
                }
                server.listen(properties.createSocketAddress()).onComplete(result -> {
                    if (!tcpServer.isCurrent(state)) {
                        server.close();
                        sink.error(new CancellationException("TCP server stopped during bind"));
                    } else if (result.succeeded()) {
                        log.info("tcp server startup on {}", result.result().actualPort());
                        sink.success();
                    } else {
                        sink.error(result.cause());
                    }
                });
            }
        });
    }

    private Mono<Void> closeServers(List<NetServer> servers) {
        return Mono.whenDelayError(servers.stream()
            .map(server -> Mono.defer(() -> Mono.fromCompletionStage(server.close().toCompletionStage())))
            .collect(java.util.stream.Collectors.toList()));
    }

    @Override
    public Mono<Network> reload(@Nonnull Network network, @Nonnull TcpServerProperties properties) {
        VertxTcpServer tcpServer = ((VertxTcpServer) network);
        return tcpServer.shutdownAsync().then(Mono.defer(() -> initTcpServer(tcpServer, properties)));
    }

    @Nullable
    @Override
    public ConfigMetadata getConfigMetadata() {
        return new DefaultConfigMetadata()
            .add("host", "本地地址", "", new StringType())
            .add("port", "本地端口", "", new IntType())
            .add("publicHost", "公网地址", "", new StringType())
            .add("publicPort", "公网端口", "", new IntType())
            .add("certId", "CA证书", "", new StringType().expand("selector", "cert"))
            .add("secure", "开启TSL", "", new BooleanType())
            .add("parserType", "解析器类型", "", new ObjectType())
            .add("parserConfiguration", "配置解析器", "", new ObjectType());
    }

    @Nonnull
    @Override
    public Mono<TcpServerProperties> createConfig(@Nonnull NetworkProperties properties) {
        return Mono.fromSupplier(() -> {
            TcpServerProperties config = FastBeanCopier.copy(properties.getConfigurations(), new TcpServerProperties());
            config.setId(properties.getId());
            config.validate();
            return config;
        })
            .as(LocaleUtils::transform);
    }

    private Mono<NetServerOptions> convert(TcpServerProperties properties) {
        NetServerOptions options = new NetServerOptions(template);
        options.setPort(properties.getPort());
        options.setHost(properties.getHost());
        if (properties.isSecure()) {
            options.setSsl(true);
            return certificateManager
                .getCertificate(properties.getCertId())
                .map(VertxKeyCertTrustOptions::new)
                .doOnNext(options::setKeyCertOptions)
                .doOnNext(options::setTrustOptions)
                .thenReturn(options);
        }
        return Mono.just(options);
    }
}
