package org.jetlinks.protocol.modbus.frame;

import lombok.Getter;
import lombok.ToString;

/**
 * Parsed Modbus RTU response ADU.
 * For read responses, payload holds the raw data bytes (after byteCount, before CRC).
 * For write responses, payload holds the echoed body (addr + value / qty).
 * For exception responses, exception is non-null and payload is empty.
 */
@Getter
@ToString
public final class ModbusResponse {

    private final int slaveId;
    private final ModbusFunctionCode function;
    private final boolean exception;
    private final ModbusExceptionCode exceptionCode;
    private final byte[] payload;

    private ModbusResponse(int slaveId,
                           ModbusFunctionCode function,
                           boolean exception,
                           ModbusExceptionCode exceptionCode,
                           byte[] payload) {
        this.slaveId = slaveId;
        this.function = function;
        this.exception = exception;
        this.exceptionCode = exceptionCode;
        this.payload = payload;
    }

    public static ModbusResponse parse(byte[] adu) {
        return parse(adu, 0, adu.length);
    }

    public static ModbusResponse parse(byte[] adu, int offset, int length) {
        if (length < 5) {
            throw new IllegalArgumentException("ADU too short: " + length);
        }
        if (!ModbusCrc16.validate(adu, offset, length)) {
            throw new IllegalArgumentException("CRC mismatch");
        }
        int slaveId = adu[offset] & 0xFF;
        int rawFc = adu[offset + 1] & 0xFF;
        int bodyEnd = offset + length - 2; // exclusive (CRC stripped)

        if (ModbusFunctionCode.isException(rawFc)) {
            ModbusFunctionCode fc = ModbusFunctionCode.of(rawFc);
            int code = adu[offset + 2] & 0xFF;
            return new ModbusResponse(slaveId, fc, true,
                    ModbusExceptionCode.of(code), new byte[0]);
        }

        ModbusFunctionCode fc = ModbusFunctionCode.of(rawFc);
        byte[] payload;
        switch (fc) {
            case READ_COILS:
            case READ_DISCRETE_INPUTS:
            case READ_HOLDING_REGISTERS:
            case READ_INPUT_REGISTERS: {
                int byteCount = adu[offset + 2] & 0xFF;
                if (offset + 3 + byteCount > bodyEnd) {
                    throw new IllegalArgumentException("byteCount exceeds payload");
                }
                payload = new byte[byteCount];
                System.arraycopy(adu, offset + 3, payload, 0, byteCount);
                break;
            }
            case WRITE_SINGLE_COIL:
            case WRITE_SINGLE_REGISTER:
            case WRITE_MULTIPLE_COILS:
            case WRITE_MULTIPLE_REGISTERS: {
                // body is the 4 bytes following fc: addrHi addrLo + (value or qty)
                int len = bodyEnd - (offset + 2);
                payload = new byte[len];
                System.arraycopy(adu, offset + 2, payload, 0, len);
                break;
            }
            default:
                throw new IllegalStateException("Unsupported function: " + fc);
        }
        return new ModbusResponse(slaveId, fc, false, null, payload);
    }

    public boolean isRead() {
        return !exception && function.isRead();
    }

    public boolean isWrite() {
        return !exception && function.isWrite();
    }
}
