package org.jetlinks.protocol.modbus;

import com.google.common.base.Ticker;
import org.jetlinks.protocol.modbus.frame.ModbusFunctionCode;
import org.jetlinks.protocol.modbus.frame.ModbusRequest;
import org.jetlinks.protocol.modbus.mapping.RegisterMappingTable;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class ModbusLifecycleTest {
    private ModbusGatewayRequestExecutor.PendingRequest request(String id,
            List<ModbusGatewayRequestExecutor.CompletionType> completions) {
        return ModbusGatewayRequestExecutor.PendingRequest.builder()
                .gatewayId("gateway").messageId(id).logicalRequestId(id)
                .request(ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1))
                .timeoutMillis(100).onCompletion(done -> completions.add(done.getType())).build();
    }

    @Test
    public void timeoutDrainCancelAndDisposeCancelDelayedWrites() {
        for (int mode = 0; mode < 4; mode++) {
            VirtualTimeScheduler clock = VirtualTimeScheduler.create();
            ModbusGatewayRequestExecutor executor = new ModbusGatewayRequestExecutor(clock);
            AtomicInteger sent = new AtomicInteger();
            AtomicInteger cancelled = new AtomicInteger();
            List<ModbusGatewayRequestExecutor.CompletionType> completions = new ArrayList<>();
            executor.setSender(pending -> Mono.delay(Duration.ofSeconds(1), clock)
                    .flatMap(ignore -> executor.sendIfCurrent(pending, () -> {
                        sent.incrementAndGet();
                        return Mono.just(true);
                    })).doOnCancel(cancelled::incrementAndGet));
            ModbusGatewayRequestExecutor.PendingRequest first = request("first", new ArrayList<>());
            ModbusGatewayRequestExecutor.PendingRequest delayed = request("delayed", completions);
            executor.submit(first);
            executor.submit(delayed);
            executor.acknowledge("gateway", first.getRequestToken());
            assertTrue(executor.isSendAllowed(delayed));
            if (mode == 0) clock.advanceTimeBy(Duration.ofMillis(101));
            if (mode == 1) executor.drain("gateway", "disconnected");
            if (mode == 2) executor.cancelByLogicalRequestId("gateway", "delayed");
            if (mode == 3) executor.dispose();
            clock.advanceTimeBy(Duration.ofSeconds(2));
            assertEquals("mode " + mode, 0, sent.get());
            assertEquals(1, cancelled.get());
            assertEquals(1, completions.size());
            assertEquals(0, executor.gatewayStateCount());
            assertFalse(executor.isSendAllowed(delayed));
            assertFalse(executor.sendIfCurrent(delayed, () -> {
                fail("terminal request must never allocate a frame or write");
                return Mono.just(true);
            }).block());
            executor.dispose();
            clock.dispose();
        }
    }

    @Test
    public void synchronousResponseDisposesSenderInstalledAfterCompletion() {
        VirtualTimeScheduler clock = VirtualTimeScheduler.create();
        ModbusGatewayRequestExecutor executor = new ModbusGatewayRequestExecutor(clock);
        AtomicInteger cancelled = new AtomicInteger();
        List<ModbusGatewayRequestExecutor.CompletionType> completions = new ArrayList<>();
        ModbusGatewayRequestExecutor.PendingRequest first = request("first", new ArrayList<>());
        ModbusGatewayRequestExecutor.PendingRequest next = request("next", completions);
        executor.setSender(pending -> Mono.<Boolean>create(sink -> {
            sink.onCancel(cancelled::incrementAndGet);
            executor.acknowledge("gateway", pending.getRequestToken());
        }));
        executor.submit(first);
        executor.submit(next);
        executor.acknowledge("gateway", first.getRequestToken());
        clock.advanceTimeBy(Duration.ofSeconds(1));
        assertEquals(1, cancelled.get());
        assertEquals(1, completions.size());
        assertEquals(ModbusGatewayRequestExecutor.CompletionType.RESPONSE, completions.get(0));
        assertEquals(0, executor.gatewayStateCount());
        executor.dispose();
        clock.dispose();
    }

    @Test
    public void disposedExecutorRejectsNewWorkWithoutInstallingTimeout() {
        VirtualTimeScheduler clock = VirtualTimeScheduler.create();
        ModbusGatewayRequestExecutor executor = new ModbusGatewayRequestExecutor(clock);
        List<ModbusGatewayRequestExecutor.CompletionType> completions = new ArrayList<>();
        executor.dispose();
        assertFalse(executor.submit(request("late", completions)));
        clock.advanceTimeBy(Duration.ofSeconds(1));
        assertEquals(1, completions.size());
        assertEquals(0, executor.gatewayStateCount());
        clock.dispose();
    }

    @Test
    public void mappingsAreBoundedAndExpireAfterIdleAndCannotReturnAfterDispose() {
        AtomicLong nanos = new AtomicLong();
        ModbusRtuCodec codec = new ModbusRtuCodec(new ModbusGatewayRequestExecutor(), new Ticker() {
            @Override public long read() { return nanos.get(); }
        });
        String json = "[{\"propertyId\":\"temperature\",\"fc\":3,\"address\":0,\"dataType\":\"int16\"}]";
        RegisterMappingTable initial = codec.parseTable(json);
        assertSame(initial, codec.parseTable(json));
        for (int i = 0; i < 1500; i++) codec.parseTable(json.replace("temperature", "temperature" + i));
        assertTrue(codec.cachedMappingCount() <= 1024);
        RegisterMappingTable refreshed = codec.parseTable(json);
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(29));
        assertSame(refreshed, codec.parseTable(json));
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(29));
        assertSame(refreshed, codec.parseTable(json));
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(31));
        assertEquals(0, codec.cachedMappingCount());
        assertNotSame(refreshed, codec.parseTable(json));
        codec.dispose();
        assertEquals(0, codec.cachedMappingCount());
        assertThrows(IllegalStateException.class, () -> codec.parseTable(json));
        assertEquals(0, codec.cachedMappingCount());
    }
}
