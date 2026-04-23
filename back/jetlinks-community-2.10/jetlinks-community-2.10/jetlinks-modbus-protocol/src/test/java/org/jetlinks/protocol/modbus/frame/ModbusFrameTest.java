package org.jetlinks.protocol.modbus.frame;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModbusFrameTest {

    @Test
    public void readHoldingRegistersRequest() {
        ModbusRequest req = ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1);
        byte[] adu = req.toAdu();
        assertArrayEquals(new byte[]{0x01, 0x03, 0x00, 0x00, 0x00, 0x01, (byte) 0x84, 0x0A}, adu);
        assertEquals(7, req.expectedResponseLength()); // 3 + 2 + 2
    }

    @Test
    public void readHoldingRegistersResponse() {
        // slave 1, fc 3, byteCount 2, data 0x01F4 (500), CRC
        byte[] body = {0x01, 0x03, 0x02, 0x01, (byte) 0xF4};
        byte[] adu = ModbusCrc16.appended(body);
        ModbusResponse resp = ModbusResponse.parse(adu);
        assertFalse(resp.isException());
        assertTrue(resp.isRead());
        assertEquals(1, resp.getSlaveId());
        assertEquals(ModbusFunctionCode.READ_HOLDING_REGISTERS, resp.getFunction());
        assertArrayEquals(new byte[]{0x01, (byte) 0xF4}, resp.getPayload());
    }

    @Test
    public void writeSingleRegisterRoundTrip() {
        ModbusRequest req = ModbusRequest.writeSingleRegister(17, 0x0001, 0x0003);
        byte[] adu = req.toAdu();
        // Echo response equals request body, with recomputed CRC
        ModbusResponse resp = ModbusResponse.parse(adu);
        assertTrue(resp.isWrite());
        assertEquals(17, resp.getSlaveId());
        assertArrayEquals(new byte[]{0x00, 0x01, 0x00, 0x03}, resp.getPayload());
    }

    @Test
    public void writeMultipleCoilsPacking() {
        boolean[] coils = {true, false, true, true, false, false, true, true, true, false};
        ModbusRequest req = ModbusRequest.writeMultipleCoils(17, 0x0013, coils);
        byte[] adu = req.toAdu();
        // slave + fc + addrHi addrLo + qtyHi qtyLo + byteCount + 2 data bytes + CRC
        assertEquals(11, adu.length);
        assertEquals(0x11, adu[0] & 0xFF);
        assertEquals(0x0F, adu[1] & 0xFF);
        assertEquals(0x02, adu[6] & 0xFF); // byteCount
        // coils 0..7 → 0b11001101 = 0xCD; coils 8..9 → 0b01 = 0x01
        assertEquals(0xCD, adu[7] & 0xFF);
        assertEquals(0x01, adu[8] & 0xFF);
    }

    @Test
    public void exceptionResponseIsDecoded() {
        // slave 1, fc 0x83 (exception flag + 0x03), code 0x02 (illegal data address), CRC
        byte[] body = {0x01, (byte) 0x83, 0x02};
        byte[] adu = ModbusCrc16.appended(body);
        ModbusResponse resp = ModbusResponse.parse(adu);
        assertTrue(resp.isException());
        assertEquals(ModbusExceptionCode.ILLEGAL_DATA_ADDRESS, resp.getExceptionCode());
        assertEquals(ModbusFunctionCode.READ_HOLDING_REGISTERS, resp.getFunction());
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseRejectsBadCrc() {
        byte[] adu = {0x01, 0x03, 0x02, 0x01, (byte) 0xF4, 0x00, 0x00};
        ModbusResponse.parse(adu);
    }
}
