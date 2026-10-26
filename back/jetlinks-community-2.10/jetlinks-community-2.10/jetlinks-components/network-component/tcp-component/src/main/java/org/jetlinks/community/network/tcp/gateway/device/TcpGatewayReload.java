package org.jetlinks.community.network.tcp.gateway.device;

import org.jetlinks.community.gateway.DeviceGateway;
import reactor.core.publisher.Mono;

final class TcpGatewayReload {
    private TcpGatewayReload() { }

    static Mono<DeviceGateway> replace(DeviceGateway current, Mono<DeviceGateway> candidate) {
        return Mono.usingWhen(candidate,
            next -> current.shutdown().then(next.startup()).thenReturn(next),
            next -> Mono.empty(),
            (next, error) -> next.shutdown()
                .onErrorResume(cleanup -> { error.addSuppressed(cleanup); return Mono.empty(); })
                .then(current.startup())
                .onErrorResume(rollback -> { error.addSuppressed(rollback); return Mono.empty(); }),
            next -> next.shutdown().then(current.startup()));
    }
}
