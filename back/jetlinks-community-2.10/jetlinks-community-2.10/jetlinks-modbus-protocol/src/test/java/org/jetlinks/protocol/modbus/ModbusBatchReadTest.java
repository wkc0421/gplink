package org.jetlinks.protocol.modbus;

import io.netty.buffer.Unpooled;
import org.jetlinks.core.message.Headers;
import org.jetlinks.core.message.property.ReadPropertyMessage;
import org.jetlinks.core.message.property.ReadPropertyMessageReply;
import org.jetlinks.core.message.property.WritePropertyMessageReply;
import org.jetlinks.protocol.modbus.frame.ModbusCrc16;
import org.jetlinks.protocol.modbus.frame.ModbusFunctionCode;
import org.jetlinks.protocol.modbus.frame.ModbusRequest;
import org.jetlinks.protocol.modbus.mapping.ByteOrder;
import org.jetlinks.protocol.modbus.mapping.RegisterDataType;
import org.jetlinks.protocol.modbus.mapping.RegisterMapping;
import org.jetlinks.protocol.modbus.mapping.RegisterMappingTable;
import org.jetlinks.protocol.modbus.pending.PendingRequestQueue;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Verifies that a ReadPropertyMessage with multiple same-FC properties is
 * coalesced into one batch Modbus frame and the response payload is correctly
 * distributed to each property.
 */
public class ModbusBatchReadTest {

    /**
     * Build a minimal RegisterMappingTable directly without going through the
     * JSON parser, so the test has no external dependencies.
     */
    private RegisterMappingTable buildTable() {
        // addr=0  INT16  scale=0.1  → temperature
        // addr=1  INT16  scale=0.1  → humidity
        // addr=2  UINT16 scale=1    → status
        RegisterMapping temp = RegisterMapping.builder()
                .propertyId("temperature")
                .functionCode(ModbusFunctionCode.READ_HOLDING_REGISTERS)
                .address(0).quantity(1)
                .dataType(RegisterDataType.INT16)
                .byteOrder(ByteOrder.ABCD)
                .scale(0.1).offset(0).writable(false)
                .build();
        RegisterMapping hum = RegisterMapping.builder()
                .propertyId("humidity")
                .functionCode(ModbusFunctionCode.READ_HOLDING_REGISTERS)
                .address(1).quantity(1)
                .dataType(RegisterDataType.INT16)
                .byteOrder(ByteOrder.ABCD)
                .scale(0.1).offset(0).writable(false)
                .build();
        RegisterMapping status = RegisterMapping.builder()
                .propertyId("status")
                .functionCode(ModbusFunctionCode.READ_HOLDING_REGISTERS)
                .address(2).quantity(1)
                .dataType(RegisterDataType.UINT16)
                .byteOrder(ByteOrder.ABCD)
                .scale(1).offset(0).writable(false)
                .build();
        return RegisterMappingTable.of(Arrays.asList(temp, hum, status));
    }

    @Test
    public void batchReadCoalescesIntoSingleFrame() {
        PendingRequestQueue queue = new PendingRequestQueue();
        ModbusRtuCodec codec = new ModbusRtuCodec(queue);
        RegisterMappingTable table = buildTable();

        // Build a batch request the same way encode() would
        List<String> props = Arrays.asList("temperature", "humidity", "status");
        // All same FC → should coalesce: addr=0, qty=3
        ModbusRequest batchReq = ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 3);

        String gatewayId = "gw1";
        String deviceId  = "slave1";
        String messageId = "msg-001";
        PendingRequestQueue.PendingRequest pending = new PendingRequestQueue.PendingRequest(
                gatewayId, deviceId, messageId, batchReq, 3000);

        // Simulate what trackAndEncode stores in PENDING_META
        codec.putBatchMeta(messageId, props, 0);

        queue.offer(gatewayId, pending);
        queue.promote(gatewayId);

        // Build a fake FC03 response: slaveId=1, fc=3, byteCount=6
        // temp = 0x00FA (250 → 25.0°C), humidity = 0x01F4 (500 → 50.0%), status = 0x0001
        byte[] body = {
            0x01, 0x03, 0x06,
            0x00, (byte) 0xFA,  // temp raw 250
            0x01, (byte) 0xF4,  // humidity raw 500
            0x00, 0x01           // status raw 1
        };
        byte[] adu = ModbusCrc16.appended(body);

        List<?> replies = codec.decodeForTest(gatewayId, adu, table);
        assertEquals(1, replies.size());

        ReadPropertyMessageReply reply = (ReadPropertyMessageReply) replies.get(0);
        assertTrue(reply.isSuccess());
        assertEquals(deviceId, reply.getDeviceId());
        assertEquals(messageId, reply.getMessageId());

        Map<String, Object> values = reply.getProperties();
        assertEquals(3, values.size());

        // 250 * 0.1 = 25.0
        assertEquals(25.0, ((Number) values.get("temperature")).doubleValue(), 0.001);
        // 500 * 0.1 = 50.0
        assertEquals(50.0, ((Number) values.get("humidity")).doubleValue(), 0.001);
        // 1 * 1 = 1
        assertEquals(1.0, ((Number) values.get("status")).doubleValue(), 0.001);
    }

    @Test
    public void readResponseWithUnexpectedByteCountDoesNotAckCurrentRequest() {
        PendingRequestQueue queue = new PendingRequestQueue();
        ModbusRtuCodec codec = new ModbusRtuCodec(queue);
        RegisterMappingTable table = buildTable();

        // Request 3 regs but response only returns 2 (edge case / truncation)
        ModbusRequest batchReq = ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 3);
        String gatewayId = "gw2";
        String messageId = "msg-002";
        PendingRequestQueue.PendingRequest pending = new PendingRequestQueue.PendingRequest(
                gatewayId, "slave1", messageId, batchReq, 3000);

        codec.putBatchMeta(messageId, Arrays.asList("temperature", "humidity", "status"), 0);
        queue.offer(gatewayId, pending);
        queue.promote(gatewayId);

        // Only 2 registers in payload (4 bytes data)
        byte[] body = {0x01, 0x03, 0x04, 0x00, (byte) 0xFA, 0x01, (byte) 0xF4};
        byte[] adu = ModbusCrc16.appended(body);

        List<?> replies = codec.decodeForTest(gatewayId, adu, table);
        assertTrue(replies.isEmpty());
        assertSame(pending, queue.peekInFlight(gatewayId));
    }

    @Test
    public void lateResponseWithDifferentShapeDoesNotAckNextRequest() {
        PendingRequestQueue queue = new PendingRequestQueue();
        ModbusRtuCodec codec = new ModbusRtuCodec(queue);
        RegisterMappingTable table = buildTable();

        String gatewayId = "gw-late-shape";
        PendingRequestQueue.PendingRequest next = new PendingRequestQueue.PendingRequest(
                gatewayId, "slave-next", "msg-next",
                ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 2), 3000);
        queue.offer(gatewayId, next);
        queue.promote(gatewayId);

        byte[] oldBody = {0x01, 0x03, 0x02, 0x00, (byte) 0xFA};
        byte[] oldAdu = ModbusCrc16.appended(oldBody);

        List<?> replies = codec.decodeForTest(gatewayId, oldAdu, table);
        assertTrue(replies.isEmpty());
        assertSame(next, queue.peekInFlight(gatewayId));
    }

    @Test
    public void batchReadReplyKeepsAutoCollectHeaders() {
        PendingRequestQueue queue = new PendingRequestQueue();
        ModbusRtuCodec codec = new ModbusRtuCodec(queue);
        RegisterMappingTable table = buildTable();

        ModbusRequest batchReq = ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 3);
        String gatewayId = "gw3";
        String deviceId = "slave1";
        String messageId = "msg-003";
        PendingRequestQueue.PendingRequest pending = new PendingRequestQueue.PendingRequest(
                gatewayId, deviceId, messageId, batchReq, 3000);

        Map<String, Object> headers = new HashMap<>();
        headers.put(Headers.ignoreStorage.getKey(), true);
        headers.put(Headers.ignoreLog.getKey(), true);
        headers.put("ignoreCache", true);
        codec.putBatchMeta(messageId, Arrays.asList("temperature", "humidity", "status"), 0, headers);
        queue.offer(gatewayId, pending);
        queue.promote(gatewayId);

        byte[] body = {
                0x01, 0x03, 0x06,
                0x00, (byte) 0xFA,
                0x01, (byte) 0xF4,
                0x00, 0x01
        };
        byte[] adu = ModbusCrc16.appended(body);

        List<?> replies = codec.decodeForTest(gatewayId, adu, table);
        assertEquals(1, replies.size());
        ReadPropertyMessageReply reply = (ReadPropertyMessageReply) replies.get(0);
        assertTrue(reply.getHeaderOrDefault(Headers.ignoreStorage));
        assertTrue(reply.getHeaderOrDefault(Headers.ignoreLog));
        assertEquals(Boolean.TRUE, reply.getHeader("ignoreCache").orElse(Boolean.FALSE));
    }

    @Test
    public void splitReadReplyUsesOriginalMessageId() {
        PendingRequestQueue queue = new PendingRequestQueue();
        ModbusRtuCodec codec = new ModbusRtuCodec(queue);
        RegisterMappingTable table = buildTable();

        String gatewayId = "gw-split-id";
        String deviceId = "slave-split-id";
        String baseMessageId = "msg-split";
        String firstFrameMessageId = baseMessageId + "_s0";
        String secondFrameMessageId = baseMessageId + "_s1";
        ModbusRequest firstRequest = ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1);
        ModbusRequest secondRequest = ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 1, 1);
        PendingRequestQueue.PendingRequest first = new PendingRequestQueue.PendingRequest(
                gatewayId, deviceId, firstFrameMessageId, baseMessageId, firstRequest, 3000, 0);
        PendingRequestQueue.PendingRequest second = new PendingRequestQueue.PendingRequest(
                gatewayId, deviceId, secondFrameMessageId, baseMessageId, secondRequest, 3000, 0);

        codec.putSingleMeta(firstFrameMessageId, "temperature", Collections.emptyMap(),
                baseMessageId, table, 0, 2);
        codec.putSingleMeta(secondFrameMessageId, "humidity", Collections.emptyMap(),
                baseMessageId, table, 1, 2);
        queue.offer(gatewayId, first);
        queue.offer(gatewayId, second);
        queue.promote(gatewayId);

        byte[] firstBody = {0x01, 0x03, 0x02, 0x00, (byte) 0xFA};
        byte[] firstAdu = ModbusCrc16.appended(firstBody);
        List<?> firstReplies = codec.decodeForTest(gatewayId, firstAdu, RegisterMappingTable.empty());
        assertTrue(firstReplies.isEmpty());

        queue.promote(gatewayId);
        byte[] secondBody = {0x01, 0x03, 0x02, 0x01, (byte) 0xF4};
        byte[] secondAdu = ModbusCrc16.appended(secondBody);

        List<?> replies = codec.decodeForTest(gatewayId, secondAdu, RegisterMappingTable.empty());
        assertEquals(1, replies.size());
        ReadPropertyMessageReply reply = (ReadPropertyMessageReply) replies.get(0);
        assertEquals(deviceId, reply.getDeviceId());
        assertEquals(baseMessageId, reply.getMessageId());
        assertEquals(2, reply.getProperties().size());
        assertEquals(25.0, ((Number) reply.getProperties().get("temperature")).doubleValue(), 0.001);
        assertEquals(50.0, ((Number) reply.getProperties().get("humidity")).doubleValue(), 0.001);
    }

    @Test
    public void unknownSessionUsesPendingRegisterTableSnapshot() {
        PendingRequestQueue queue = new PendingRequestQueue();
        ModbusRtuCodec codec = new ModbusRtuCodec(queue);
        RegisterMappingTable table = buildTable();

        String gatewayId = "gw-unknown-table";
        String deviceId = "slave-unknown-table";
        String messageId = "msg-unknown-table";
        ModbusRequest request = ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1);
        PendingRequestQueue.PendingRequest pending = new PendingRequestQueue.PendingRequest(
                gatewayId, deviceId, messageId, request, 3000);

        codec.putSingleMeta(messageId, "temperature", Collections.emptyMap(), null, table);
        queue.offer(gatewayId, pending);
        queue.promote(gatewayId);

        byte[] body = {0x01, 0x03, 0x02, 0x00, (byte) 0xFA};
        byte[] adu = ModbusCrc16.appended(body);

        List<?> replies = codec.decodeForTest(null, adu, RegisterMappingTable.empty());
        assertEquals(1, replies.size());
        ReadPropertyMessageReply reply = (ReadPropertyMessageReply) replies.get(0);
        assertEquals(deviceId, reply.getDeviceId());
        assertEquals(messageId, reply.getMessageId());
        assertEquals(25.0, ((Number) reply.getProperties().get("temperature")).doubleValue(), 0.001);
        assertNull(queue.peekInFlight(gatewayId));
    }

    @Test
    public void singleReadDecodeErrorReturnsFailureReplyAndAcksQueue() {
        RegisterMapping wide = RegisterMapping.builder()
                .propertyId("wide")
                .functionCode(ModbusFunctionCode.READ_HOLDING_REGISTERS)
                .address(0).quantity(1)
                .dataType(RegisterDataType.INT32)
                .byteOrder(ByteOrder.ABCD)
                .scale(1).offset(0).writable(false)
                .build();
        RegisterMappingTable table = RegisterMappingTable.of(Collections.singletonList(wide));
        PendingRequestQueue queue = new PendingRequestQueue();
        ModbusRtuCodec codec = new ModbusRtuCodec(queue);

        String gatewayId = "gw-decode-error";
        String messageId = "msg-decode-error";
        ModbusRequest request = ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1);
        PendingRequestQueue.PendingRequest pending = new PendingRequestQueue.PendingRequest(
                gatewayId, "slave-decode-error", messageId, request, 3000);

        Map<String, Object> headers = new HashMap<>();
        headers.put(Headers.ignoreStorage.getKey(), true);
        codec.putSingleMeta(messageId, "wide", headers, null, table);
        queue.offer(gatewayId, pending);
        queue.promote(gatewayId);

        byte[] body = {0x01, 0x03, 0x02, 0x00, 0x01};
        byte[] adu = ModbusCrc16.appended(body);

        List<?> replies = codec.decodeForTest(gatewayId, adu, RegisterMappingTable.empty());
        assertEquals(1, replies.size());
        ReadPropertyMessageReply reply = (ReadPropertyMessageReply) replies.get(0);
        assertFalse(reply.isSuccess());
        assertEquals("modbus-decode-error", reply.getCode());
        assertTrue(reply.getHeaderOrDefault(Headers.ignoreStorage));
        assertNull(queue.peekInFlight(gatewayId));
    }

    @Test
    public void writeReplyKeepsRequestHeaders() {
        PendingRequestQueue queue = new PendingRequestQueue();
        ModbusRtuCodec codec = new ModbusRtuCodec(queue);

        String gatewayId = "gw-write-headers";
        String messageId = "msg-write-headers";
        ModbusRequest request = ModbusRequest.writeSingleRegister(1, 0, 123);
        PendingRequestQueue.PendingRequest pending = new PendingRequestQueue.PendingRequest(
                gatewayId, "slave-write-headers", messageId, request, 3000);

        Map<String, Object> headers = new HashMap<>();
        headers.put(Headers.ignoreStorage.getKey(), true);
        headers.put(Headers.ignoreLog.getKey(), true);
        headers.put("ignoreCache", true);
        codec.putSingleMeta(messageId, "setpoint", headers, null, null);
        queue.offer(gatewayId, pending);
        queue.promote(gatewayId);

        byte[] body = {0x01, 0x06, 0x00, 0x00, 0x00, 0x7B};
        byte[] adu = ModbusCrc16.appended(body);

        List<?> replies = codec.decodeForTest(gatewayId, adu, RegisterMappingTable.empty());
        assertEquals(1, replies.size());
        WritePropertyMessageReply reply = (WritePropertyMessageReply) replies.get(0);
        assertTrue(reply.isSuccess());
        assertTrue(reply.getHeaderOrDefault(Headers.ignoreStorage));
        assertTrue(reply.getHeaderOrDefault(Headers.ignoreLog));
        assertEquals(Boolean.TRUE, reply.getHeader("ignoreCache").orElse(Boolean.FALSE));
    }

    @Test
    public void timeoutSweepClearsPendingMeta() {
        PendingRequestQueue queue = new PendingRequestQueue();
        ModbusRtuCodec codec = new ModbusRtuCodec(queue);

        String gatewayId = "gw-timeout";
        String messageId = "msg-timeout";
        ModbusRequest request = ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 3);
        PendingRequestQueue.PendingRequest pending = new PendingRequestQueue.PendingRequest(
                gatewayId, "slave-timeout", messageId, request, 1);

        codec.putBatchMeta(messageId, Arrays.asList("temperature", "humidity", "status"), 0);
        assertTrue(queue.offer(gatewayId, pending));
        PendingRequestQueue.PendingRequest promoted = queue.promote(gatewayId);
        assertSame(pending, promoted);
        queue.markSent(promoted, System.currentTimeMillis() - 10);
        assertEquals(1, codec.pendingMetaSizeForTests());

        List<PendingRequestQueue.PendingRequest> expired = codec.sweepExpiredForTests(System.currentTimeMillis());
        assertEquals(1, expired.size());
        assertEquals(0, codec.pendingMetaSizeForTests());
        assertNull(queue.peekInFlight(gatewayId));
    }

    @Test
    public void timeoutSweepDropsWaitingFramesFromSameLogicalRequest() {
        PendingRequestQueue queue = new PendingRequestQueue();
        ModbusRtuCodec codec = new ModbusRtuCodec(queue);
        RegisterMappingTable table = buildTable();

        String gatewayId = "gw-timeout-split";
        String baseMessageId = "msg-timeout-split";
        ModbusRequest firstRequest = ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1);
        ModbusRequest secondRequest = ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 1, 1);
        PendingRequestQueue.PendingRequest first = new PendingRequestQueue.PendingRequest(
                gatewayId, "slave-timeout-split", baseMessageId + "_s0",
                baseMessageId, firstRequest, 1, 0);
        PendingRequestQueue.PendingRequest second = new PendingRequestQueue.PendingRequest(
                gatewayId, "slave-timeout-split", baseMessageId + "_s1",
                baseMessageId, secondRequest, 3000, 0);

        codec.putSingleMeta(first.getMessageId(), "temperature", Collections.emptyMap(), baseMessageId, table);
        codec.putSingleMeta(second.getMessageId(), "humidity", Collections.emptyMap(), baseMessageId, table);
        assertTrue(queue.offer(gatewayId, first));
        assertTrue(queue.offer(gatewayId, second));
        PendingRequestQueue.PendingRequest promoted = queue.promote(gatewayId);
        assertSame(first, promoted);
        queue.markSent(promoted, System.currentTimeMillis() - 10);
        assertEquals(2, codec.pendingMetaSizeForTests());

        List<PendingRequestQueue.PendingRequest> expired = codec.sweepExpiredForTests(System.currentTimeMillis());
        assertEquals(1, expired.size());
        assertEquals(0, codec.pendingMetaSizeForTests());
        assertNull(queue.peekInFlight(gatewayId));
        assertNull(queue.promote(gatewayId));
    }

    @Test
    public void promotedRequestExpiresBeforeItIsMarkedSent() {
        PendingRequestQueue queue = new PendingRequestQueue();
        String gatewayId = "gw-promoted-timeout";
        PendingRequestQueue.PendingRequest pending = new PendingRequestQueue.PendingRequest(
                gatewayId, "slave-timeout", "msg-promoted-timeout",
                ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1), 1);

        assertTrue(queue.offer(gatewayId, pending));
        PendingRequestQueue.PendingRequest promoted = queue.promote(gatewayId);
        assertSame(pending, promoted);
        assertTrue(promoted.getPromotedAt() > 0);
        assertEquals(-1L, promoted.getSentAt());

        PendingRequestQueue.PendingRequest expired =
                queue.sweepExpired(gatewayId, promoted.getPromotedAt() + 10);
        assertSame(pending, expired);
        assertNull(queue.peekInFlight(gatewayId));
    }

    @Test
    public void pendingQueueRejectsRequestsOverGatewayLimit() {
        PendingRequestQueue queue = new PendingRequestQueue(1);
        String gatewayId = "gw-limit";
        PendingRequestQueue.PendingRequest first = new PendingRequestQueue.PendingRequest(
                gatewayId, "slave1", "msg-limit-1",
                ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1), 3000);
        PendingRequestQueue.PendingRequest second = new PendingRequestQueue.PendingRequest(
                gatewayId, "slave2", "msg-limit-2",
                ModbusRequest.read(2, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1), 3000);

        assertTrue(queue.offer(gatewayId, first));
        assertFalse(queue.offer(gatewayId, second));
        assertSame(first, queue.promote(gatewayId));
    }

    @Test
    public void dispatchDelayUsesGatewayLastSentAt() {
        PendingRequestQueue queue = new PendingRequestQueue();
        String gatewayId = "gw-dispatch";
        PendingRequestQueue.PendingRequest first = new PendingRequestQueue.PendingRequest(
                gatewayId, "slave1", "msg-dispatch-1",
                ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1), 3000, 50);
        PendingRequestQueue.PendingRequest second = new PendingRequestQueue.PendingRequest(
                gatewayId, "slave2", "msg-dispatch-2",
                ModbusRequest.read(2, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1), 3000, 50);

        assertTrue(queue.offer(gatewayId, first));
        PendingRequestQueue.PendingRequest promotedFirst = queue.promote(gatewayId);
        assertSame(first, promotedFirst);
        assertEquals(0, queue.dispatchDelay(gatewayId, 50, 1000));
        queue.markSent(promotedFirst, 1000);
        queue.ack(gatewayId);

        assertTrue(queue.offer(gatewayId, second));
        assertSame(second, queue.promote(gatewayId));
        assertEquals(30, queue.dispatchDelay(gatewayId, 50, 1020));
        assertEquals(0, queue.dispatchDelay(gatewayId, 50, 1050));
    }

    @Test
    public void gatewayScopedLookupDoesNotMatchOtherGateway() {
        PendingRequestQueue queue = new PendingRequestQueue();
        PendingRequestQueue.PendingRequest gw1 = new PendingRequestQueue.PendingRequest(
                "gw-scope-1", "slave-gw1", "msg-scope-1",
                ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1), 3000);
        PendingRequestQueue.PendingRequest gw2 = new PendingRequestQueue.PendingRequest(
                "gw-scope-2", "slave-gw2", "msg-scope-2",
                ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1), 3000);

        assertTrue(queue.offer("gw-scope-1", gw1));
        assertTrue(queue.offer("gw-scope-2", gw2));
        queue.promote("gw-scope-1");
        queue.promote("gw-scope-2");

        assertSame(gw2, queue.findInFlight("gw-scope-2", 1, ModbusFunctionCode.READ_HOLDING_REGISTERS));
        assertNull(queue.findInFlight("gw-missing", 1, ModbusFunctionCode.READ_HOLDING_REGISTERS));
    }

    @Test
    public void decodeUsesGatewayScopedPendingWhenSlaveAndFunctionOverlap() {
        PendingRequestQueue queue = new PendingRequestQueue();
        ModbusRtuCodec codec = new ModbusRtuCodec(queue);
        RegisterMappingTable table = buildTable();
        ModbusRequest request = ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 3);
        PendingRequestQueue.PendingRequest gw1 = new PendingRequestQueue.PendingRequest(
                "gw-overlap-1", "slave-gw1", "msg-overlap-1", request, 3000);
        PendingRequestQueue.PendingRequest gw2 = new PendingRequestQueue.PendingRequest(
                "gw-overlap-2", "slave-gw2", "msg-overlap-2", request, 3000);

        codec.putBatchMeta("msg-overlap-1", Arrays.asList("temperature", "humidity", "status"), 0);
        codec.putBatchMeta("msg-overlap-2", Arrays.asList("temperature", "humidity", "status"), 0);
        assertTrue(queue.offer("gw-overlap-1", gw1));
        assertTrue(queue.offer("gw-overlap-2", gw2));
        queue.promote("gw-overlap-1");
        queue.promote("gw-overlap-2");

        byte[] body = {
                0x01, 0x03, 0x06,
                0x00, (byte) 0xFA,
                0x01, (byte) 0xF4,
                0x00, 0x01
        };
        byte[] adu = ModbusCrc16.appended(body);

        List<?> replies = codec.decodeForTest("gw-overlap-2", adu, table);
        assertEquals(1, replies.size());
        ReadPropertyMessageReply reply = (ReadPropertyMessageReply) replies.get(0);
        assertEquals("slave-gw2", reply.getDeviceId());
        assertEquals("msg-overlap-2", reply.getMessageId());
        assertSame(gw1, queue.peekInFlight("gw-overlap-1"));
        assertNull(queue.peekInFlight("gw-overlap-2"));
    }

    @Test
    public void unknownSessionFallbackOnlyAcceptsUniqueCandidate() {
        PendingRequestQueue queue = new PendingRequestQueue();
        ModbusRtuCodec codec = new ModbusRtuCodec(queue);
        PendingRequestQueue.PendingRequest gw1 = new PendingRequestQueue.PendingRequest(
                "gw-unknown-1", "slave-gw1", "msg-unknown-1",
                ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1), 3000);
        PendingRequestQueue.PendingRequest gw2 = new PendingRequestQueue.PendingRequest(
                "gw-unknown-2", "slave-gw2", "msg-unknown-2",
                ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1), 3000);

        assertTrue(queue.offer("gw-unknown-1", gw1));
        queue.promote("gw-unknown-1");
        assertSame(gw1, codec.resolveUnknownPendingForTests(1, ModbusFunctionCode.READ_HOLDING_REGISTERS));

        assertTrue(queue.offer("gw-unknown-2", gw2));
        queue.promote("gw-unknown-2");
        assertNull(codec.resolveUnknownPendingForTests(1, ModbusFunctionCode.READ_HOLDING_REGISTERS));
    }
}
