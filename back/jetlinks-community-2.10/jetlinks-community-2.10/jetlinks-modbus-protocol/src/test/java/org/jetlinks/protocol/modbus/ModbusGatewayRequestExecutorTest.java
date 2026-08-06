package org.jetlinks.protocol.modbus;

import org.jetlinks.protocol.modbus.frame.ModbusFunctionCode;
import org.jetlinks.protocol.modbus.frame.ModbusRequest;
import org.junit.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class ModbusGatewayRequestExecutorTest {

    @Test
    public void manualAndPollingShareOneFifoAndSingleFlight() {
        ModbusGatewayRequestExecutor executor = new ModbusGatewayRequestExecutor();
        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        executor.setSender(request -> {
            sent.add(request.getMessageId());
            return Mono.just(true);
        });

        ModbusGatewayRequestExecutor.PendingRequest first =
                request("gw", "manual-1", ModbusGatewayRequestExecutor.Source.MANUAL, 3000);
        ModbusGatewayRequestExecutor.PendingRequest second =
                request("gw", "poll-1", ModbusGatewayRequestExecutor.Source.POLLING, 3000);
        ModbusGatewayRequestExecutor.PendingRequest third =
                request("gw", "manual-2", ModbusGatewayRequestExecutor.Source.MANUAL, 3000);

        assertTrue(executor.submit(first));
        assertFalse(executor.submit(second));
        assertFalse(executor.submit(third));
        assertSame(first, executor.peekInFlight("gw"));
        assertEquals(2, executor.waitingCount("gw"));

        executor.acknowledge("gw", first.getRequestToken());
        assertEquals(Collections.singletonList("poll-1"), sent);
        assertSame(second, executor.peekInFlight("gw"));

        executor.acknowledge("gw", second.getRequestToken());
        assertEquals(2, sent.size());
        assertEquals("manual-2", sent.get(1));
        assertSame(third, executor.peekInFlight("gw"));
    }

    @Test
    public void activeTimeoutReleasesBusAndOldTokenCannotClearNextRequest() throws Exception {
        ModbusGatewayRequestExecutor executor = new ModbusGatewayRequestExecutor();
        CountDownLatch nextSent = new CountDownLatch(1);
        CountDownLatch timedOut = new CountDownLatch(1);
        List<ModbusGatewayRequestExecutor.CompletionType> completions =
                Collections.synchronizedList(new ArrayList<>());
        executor.setSender(request -> {
            nextSent.countDown();
            return Mono.just(true);
        });

        ModbusGatewayRequestExecutor.PendingRequest first =
                request("gw-timeout", "first", ModbusGatewayRequestExecutor.Source.MANUAL, 30,
                        completion -> {
                            completions.add(completion.getType());
                            timedOut.countDown();
                        });
        ModbusGatewayRequestExecutor.PendingRequest second =
                request("gw-timeout", "second", ModbusGatewayRequestExecutor.Source.MANUAL, 3000);
        executor.submit(first);
        executor.submit(second);

        assertTrue(nextSent.await(2, TimeUnit.SECONDS));
        assertTrue(timedOut.await(2, TimeUnit.SECONDS));
        assertEquals(Collections.singletonList(
                ModbusGatewayRequestExecutor.CompletionType.TIMEOUT), completions);
        assertSame(second, executor.peekInFlight("gw-timeout"));

        assertNull(executor.acknowledge("gw-timeout", first.getRequestToken()));
        assertSame(second, executor.peekInFlight("gw-timeout"));
    }

    @Test
    public void disconnectDrainCancelsTimeoutAndReclaimsState() throws Exception {
        ModbusGatewayRequestExecutor executor = new ModbusGatewayRequestExecutor();
        CountDownLatch drained = new CountDownLatch(2);
        List<ModbusGatewayRequestExecutor.CompletionType> completions =
                Collections.synchronizedList(new ArrayList<>());

        executor.submit(request("gw-drain", "one", ModbusGatewayRequestExecutor.Source.MANUAL, 50,
                completion -> {
                    completions.add(completion.getType());
                    drained.countDown();
                }));
        executor.submit(request("gw-drain", "two", ModbusGatewayRequestExecutor.Source.POLLING, 50,
                completion -> {
                    completions.add(completion.getType());
                    drained.countDown();
                }));

        assertEquals(2, executor.drain("gw-drain", "connection lost").size());
        assertTrue(drained.await(1, TimeUnit.SECONDS));
        Thread.sleep(80);
        assertEquals(0, executor.gatewayStateCount());
        assertEquals(2, completions.size());
        assertTrue(completions.stream().allMatch(
                type -> type == ModbusGatewayRequestExecutor.CompletionType.CONNECTION_INTERRUPTED));
    }

    @Test
    public void cancelWaitingAndMarkInflightWithoutAdvancingBus() {
        ModbusGatewayRequestExecutor executor = new ModbusGatewayRequestExecutor();
        executor.setSender(request -> Mono.just(true));
        ModbusGatewayRequestExecutor.PendingRequest first =
                request("gw-cancel", "one", "cycle-a", ModbusGatewayRequestExecutor.Source.POLLING, 3000);
        ModbusGatewayRequestExecutor.PendingRequest second =
                request("gw-cancel", "two", "cycle-a", ModbusGatewayRequestExecutor.Source.POLLING, 3000);
        ModbusGatewayRequestExecutor.PendingRequest third =
                request("gw-cancel", "three", "cycle-b", ModbusGatewayRequestExecutor.Source.POLLING, 3000);
        executor.submit(first);
        executor.submit(second);
        executor.submit(third);

        List<ModbusGatewayRequestExecutor.PendingRequest> cancelled =
                executor.cancelByCycleId("gw-cancel", "cycle-a");
        assertEquals(2, cancelled.size());
        assertTrue(first.isCancelled());
        assertTrue(second.isCancelled());
        assertSame(first, executor.peekInFlight("gw-cancel"));
        assertEquals(1, executor.waitingCount("gw-cancel"));

        executor.acknowledge("gw-cancel", first.getRequestToken());
        assertSame(third, executor.peekInFlight("gw-cancel"));
    }

    @Test
    public void invalidLeaseNeverSendsNextPollingFrame() {
        ModbusGatewayRequestExecutor executor = new ModbusGatewayRequestExecutor();
        executor.setLeaseValidator(request -> !"expired".equals(request.getLeaseOwnerToken()));
        ModbusGatewayRequestExecutor.PendingRequest invalid =
                ModbusGatewayRequestExecutor.PendingRequest.builder()
                        .gatewayId("gw-lease")
                        .deviceId("slave")
                        .messageId("poll")
                        .replyMessageId("poll")
                        .source(ModbusGatewayRequestExecutor.Source.POLLING)
                        .leaseOwnerToken("expired")
                        .request(ModbusRequest.read(
                                1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1))
                        .timeoutMillis(3000)
                        .build();

        assertFalse(executor.submit(invalid));
        assertNull(executor.peekInFlight("gw-lease"));
        assertEquals(0, executor.gatewayStateCount());
        assertTrue(invalid.isCancelled());
    }

    @Test
    public void invalidatedOwnerIsFencedBeforeDelayedSend() {
        ModbusGatewayRequestExecutor executor = new ModbusGatewayRequestExecutor();
        ModbusGatewayRequestExecutor.PendingRequest first =
                request("gw-fence", "manual", ModbusGatewayRequestExecutor.Source.MANUAL, 3000);
        ModbusGatewayRequestExecutor.PendingRequest polling =
                request("gw-fence", "polling", ModbusGatewayRequestExecutor.Source.POLLING, 3000);

        assertTrue(executor.submit(first));
        assertFalse(executor.submit(polling));
        executor.invalidateLeaseOwnerToken("gw-fence", "valid");
        assertTrue(polling.isCancelled());

        executor.acknowledge("gw-fence", first.getRequestToken());
        assertNull(executor.peekInFlight("gw-fence"));
        assertFalse(executor.isSendAllowed(polling));
        assertEquals(0, executor.gatewayStateCount());
    }

    private ModbusGatewayRequestExecutor.PendingRequest request(
            String gatewayId,
            String messageId,
            ModbusGatewayRequestExecutor.Source source,
            long timeoutMillis) {
        return request(gatewayId, messageId, source, timeoutMillis, null);
    }

    private ModbusGatewayRequestExecutor.PendingRequest request(
            String gatewayId,
            String messageId,
            ModbusGatewayRequestExecutor.Source source,
            long timeoutMillis,
            java.util.function.Consumer<ModbusGatewayRequestExecutor.Completion> listener) {
        return ModbusGatewayRequestExecutor.PendingRequest.builder()
                .gatewayId(gatewayId)
                .deviceId("slave")
                .messageId(messageId)
                .replyMessageId(messageId)
                .logicalRequestId(messageId)
                .source(source)
                .leaseOwnerToken("valid")
                .request(ModbusRequest.read(
                        1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1))
                .timeoutMillis(timeoutMillis)
                .onCompletion(listener)
                .build();
    }

    private ModbusGatewayRequestExecutor.PendingRequest request(
            String gatewayId,
            String messageId,
            String cycleId,
            ModbusGatewayRequestExecutor.Source source,
            long timeoutMillis) {
        return ModbusGatewayRequestExecutor.PendingRequest.builder()
                .gatewayId(gatewayId)
                .deviceId("slave")
                .messageId(messageId)
                .replyMessageId(messageId)
                .logicalRequestId(messageId)
                .cycleId(cycleId)
                .source(source)
                .leaseOwnerToken("valid")
                .request(ModbusRequest.read(
                        1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1))
                .timeoutMillis(timeoutMillis)
                .build();
    }
}
