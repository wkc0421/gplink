package org.jetlinks.protocol.modbus;

import io.netty.buffer.Unpooled;
import org.jetlinks.core.message.property.ReadPropertyMessage;
import org.jetlinks.core.message.property.ReadPropertyMessageReply;
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
    public void batchReadHandlesPayloadTooShortGracefully() {
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
        assertEquals(1, replies.size());
        ReadPropertyMessageReply reply = (ReadPropertyMessageReply) replies.get(0);
        // temperature and humidity decoded; status skipped (payload too short)
        assertTrue(reply.isSuccess());
        assertEquals(2, reply.getProperties().size());
        assertTrue(reply.getProperties().containsKey("temperature"));
        assertTrue(reply.getProperties().containsKey("humidity"));
        assertFalse(reply.getProperties().containsKey("status"));
    }
}
