package org.jetlinks.protocol.modbus;

import lombok.extern.slf4j.Slf4j;
import org.jetlinks.core.server.ClientConnection;
import org.jetlinks.core.server.DeviceGatewayContext;
import reactor.core.publisher.Mono;

import java.util.function.BiFunction;

/**
 * Hook invoked by {@code tcp-server-gateway} / {@code tcp-client-gateway}
 * immediately after a Modbus RTU transport connects. Modbus RTU has no
 * in-band device identity — the platform must know in advance which
 * gateway device is hosted on which TCP channel. A pure identity
 * handshake therefore isn't possible on the first packet.
 *
 * <p>V1 strategy: leave the connection untouched. A matching
 * {@link ModbusStateChecker} is expected to trigger the gateway online
 * state on the first successful probe. Operators that need a stricter
 * onboarding flow can swap in a custom handler that reads a product
 * config (e.g. {@code secureKey} echoed over the first frame) and emits
 * {@code DeviceOnlineMessage} via {@link DeviceGatewayContext#onMessage}.
 */
@Slf4j
public final class ModbusOnConnectHandler
        implements BiFunction<ClientConnection, DeviceGatewayContext, Mono<Void>> {

    @Override
    public Mono<Void> apply(ClientConnection connection, DeviceGatewayContext ctx) {
        if (connection != null) {
            log.debug("Modbus transport connected from {}", connection.address());
        }
        return Mono.empty();
    }
}
