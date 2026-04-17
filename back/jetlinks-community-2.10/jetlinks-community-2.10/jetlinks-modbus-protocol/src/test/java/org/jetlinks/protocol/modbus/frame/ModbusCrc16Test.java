package org.jetlinks.protocol.modbus.frame;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModbusCrc16Test {

    @Test
    public void readHoldingRegistersSample() {
        // 01 03 00 00 00 01 → CRC 84 0A (low byte first on the wire)
        byte[] body = {0x01, 0x03, 0x00, 0x00, 0x00, 0x01};
        int crc = ModbusCrc16.compute(body);
        assertEquals(0x0A84, crc);
        byte[] appended = ModbusCrc16.appended(body);
        assertArrayEquals(new byte[]{0x01, 0x03, 0x00, 0x00, 0x00, 0x01, (byte) 0x84, 0x0A}, appended);
    }

    @Test
    public void writeSingleCoilSample() {
        // 11 05 00 AC FF 00 → CRC 4E 8B
        byte[] body = {0x11, 0x05, 0x00, (byte) 0xAC, (byte) 0xFF, 0x00};
        int crc = ModbusCrc16.compute(body);
        assertEquals(0x8B4E, crc);
    }

    @Test
    public void validatorAcceptsCorrectCrc() {
        byte[] frame = {0x01, 0x03, 0x00, 0x00, 0x00, 0x01, (byte) 0x84, 0x0A};
        assertTrue(ModbusCrc16.validate(frame));
    }

    @Test
    public void validatorRejectsCorruptedCrc() {
        byte[] frame = {0x01, 0x03, 0x00, 0x00, 0x00, 0x01, (byte) 0x84, 0x0B};
        assertFalse(ModbusCrc16.validate(frame));
    }
}
