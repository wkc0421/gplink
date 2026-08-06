package org.jetlinks.community.device.modbus;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModbusPollingReloadGateTest {

    @Test
    void coalescesARefreshBurst() {
        AtomicInteger reloads = new AtomicInteger();
        ModbusPollingReloadGate gate = new ModbusPollingReloadGate(
                () -> Mono.fromRunnable(reloads::incrementAndGet), Duration.ZERO);

        Mono<Void> first = gate.request();
        gate.request();
        gate.request();
        first.block();

        assertEquals(3, gate.getRequestedRevision());
        assertEquals(3, gate.getAppliedRevision());
        assertEquals(1, reloads.get());
        assertFalse(gate.isRunning());
    }

    @Test
    void schedulesOneFollowUpWhenAWriteArrivesDuringReload() {
        AtomicInteger reloads = new AtomicInteger();
        ModbusPollingReloadGate[] holder = new ModbusPollingReloadGate[1];
        ModbusPollingReloadGate gate = new ModbusPollingReloadGate(() -> {
            if (reloads.incrementAndGet() == 1) {
                holder[0].request();
            }
            return Mono.empty();
        }, Duration.ZERO);
        holder[0] = gate;

        gate.request().block();

        assertEquals(2, gate.getRequestedRevision());
        assertEquals(2, gate.getAppliedRevision());
        assertEquals(2, reloads.get());
        assertTrue(!gate.isRunning());
    }
}
