package org.jetlinks.protocol.modbus.frame;

/**
 * Modbus RTU CRC16 (IBM / reflected, poly 0xA001, init 0xFFFF).
 * Returned value is little-endian appended to the ADU:
 * low byte first, then high byte.
 */
public final class ModbusCrc16 {

    private ModbusCrc16() {
    }

    public static int compute(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = 0; i < length; i++) {
            crc ^= (data[offset + i] & 0xFF);
            for (int b = 0; b < 8; b++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >> 1) ^ 0xA001;
                } else {
                    crc = crc >> 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    public static int compute(byte[] data) {
        return compute(data, 0, data.length);
    }

    /** Appends CRC16 (low byte, high byte) into a new array. */
    public static byte[] appended(byte[] payload) {
        int crc = compute(payload);
        byte[] out = new byte[payload.length + 2];
        System.arraycopy(payload, 0, out, 0, payload.length);
        out[payload.length] = (byte) (crc & 0xFF);
        out[payload.length + 1] = (byte) ((crc >> 8) & 0xFF);
        return out;
    }

    /** Validates a frame that already includes two-byte CRC at the end. */
    public static boolean validate(byte[] frame) {
        return validate(frame, 0, frame.length);
    }

    public static boolean validate(byte[] frame, int offset, int length) {
        if (length < 3) {
            return false;
        }
        int bodyLen = length - 2;
        int expected = compute(frame, offset, bodyLen);
        int actual = (frame[offset + bodyLen] & 0xFF)
                | ((frame[offset + bodyLen + 1] & 0xFF) << 8);
        return expected == actual;
    }
}
