package org.jetlinks.protocol.modbus;

import lombok.extern.slf4j.Slf4j;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceState;
import org.jetlinks.core.device.DeviceStateChecker;
import reactor.core.publisher.Mono;

/**
 * Device state checker for Modbus gateways and slaves.
 *
 * <p>Both the gateway (TCP-level) and its slaves share the state of the
 * underlying {@code DeviceSession}: if the session is alive and a probe
 * response has been observed recently, the device is treated as online.
 * Real-time probe scheduling is delegated to the surrounding gateway
 * (via periodic {@code ReadPropertyMessage}s the platform already emits);
 * this checker simply translates the current session presence into the
 * online/offline/unknown triple JetLinks expects.
 */
@Slf4j
public final class ModbusStateChecker implements DeviceStateChecker {

    @Override
    public Mono<Byte> checkState(DeviceOperator device) {
        if (device == null) {
            return Mono.just(DeviceState.unknown);
        }
        return device
                .getConnectionServerId()
                .map(id -> id == null || id.isEmpty() ? DeviceState.offline : DeviceState.online)
                .defaultIfEmpty(DeviceState.unknown)
                .onErrorReturn(DeviceState.unknown);
    }
}
