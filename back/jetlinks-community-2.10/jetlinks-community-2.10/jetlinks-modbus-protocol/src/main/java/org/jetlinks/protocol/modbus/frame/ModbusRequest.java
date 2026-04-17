package org.jetlinks.protocol.modbus.frame;

import lombok.Getter;
import lombok.ToString;

/**
 * A Modbus RTU request ADU built for transmission.
 * Wire format: [slaveId][functionCode][pdu...][crcLo][crcHi].
 */
@Getter
@ToString
public final class ModbusRequest {

    private final int slaveId;
    private final ModbusFunctionCode function;
    private final int address;
    private final int quantity;
    private final byte[] writeValue; // null for read requests

    private ModbusRequest(int slaveId,
                          ModbusFunctionCode function,
                          int address,
                          int quantity,
                          byte[] writeValue) {
        validateSlaveId(slaveId);
        this.slaveId = slaveId;
        this.function = function;
        this.address = address & 0xFFFF;
        this.quantity = quantity & 0xFFFF;
        this.writeValue = writeValue;
    }

    public static ModbusRequest read(int slaveId, ModbusFunctionCode fc, int address, int quantity) {
        if (!fc.isRead()) {
            throw new IllegalArgumentException(fc + " is not a read function");
        }
        return new ModbusRequest(slaveId, fc, address, quantity, null);
    }

    public static ModbusRequest writeSingleCoil(int slaveId, int address, boolean on) {
        byte[] value = new byte[]{(byte) (on ? 0xFF : 0x00), 0x00};
        return new ModbusRequest(slaveId, ModbusFunctionCode.WRITE_SINGLE_COIL, address, 1, value);
    }

    public static ModbusRequest writeSingleRegister(int slaveId, int address, int value) {
        byte[] v = new byte[]{(byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)};
        return new ModbusRequest(slaveId, ModbusFunctionCode.WRITE_SINGLE_REGISTER, address, 1, v);
    }

    public static ModbusRequest writeMultipleCoils(int slaveId, int address, boolean[] coils) {
        int quantity = coils.length;
        int byteCount = (quantity + 7) / 8;
        byte[] packed = new byte[byteCount];
        for (int i = 0; i < quantity; i++) {
            if (coils[i]) {
                packed[i / 8] |= (byte) (1 << (i % 8));
            }
        }
        return new ModbusRequest(slaveId, ModbusFunctionCode.WRITE_MULTIPLE_COILS, address, quantity, packed);
    }

    public static ModbusRequest writeMultipleRegisters(int slaveId, int address, int[] registers) {
        byte[] buf = new byte[registers.length * 2];
        for (int i = 0; i < registers.length; i++) {
            buf[i * 2] = (byte) ((registers[i] >> 8) & 0xFF);
            buf[i * 2 + 1] = (byte) (registers[i] & 0xFF);
        }
        return new ModbusRequest(slaveId, ModbusFunctionCode.WRITE_MULTIPLE_REGISTERS, address, registers.length, buf);
    }

    public byte[] toAdu() {
        byte[] body = buildBody();
        return ModbusCrc16.appended(body);
    }

    private byte[] buildBody() {
        switch (function) {
            case READ_COILS:
            case READ_DISCRETE_INPUTS:
            case READ_HOLDING_REGISTERS:
            case READ_INPUT_REGISTERS: {
                byte[] body = new byte[6];
                body[0] = (byte) slaveId;
                body[1] = (byte) function.getCode();
                body[2] = (byte) ((address >> 8) & 0xFF);
                body[3] = (byte) (address & 0xFF);
                body[4] = (byte) ((quantity >> 8) & 0xFF);
                body[5] = (byte) (quantity & 0xFF);
                return body;
            }
            case WRITE_SINGLE_COIL:
            case WRITE_SINGLE_REGISTER: {
                byte[] body = new byte[6];
                body[0] = (byte) slaveId;
                body[1] = (byte) function.getCode();
                body[2] = (byte) ((address >> 8) & 0xFF);
                body[3] = (byte) (address & 0xFF);
                body[4] = writeValue[0];
                body[5] = writeValue[1];
                return body;
            }
            case WRITE_MULTIPLE_COILS:
            case WRITE_MULTIPLE_REGISTERS: {
                byte[] body = new byte[7 + writeValue.length];
                body[0] = (byte) slaveId;
                body[1] = (byte) function.getCode();
                body[2] = (byte) ((address >> 8) & 0xFF);
                body[3] = (byte) (address & 0xFF);
                body[4] = (byte) ((quantity >> 8) & 0xFF);
                body[5] = (byte) (quantity & 0xFF);
                body[6] = (byte) writeValue.length;
                System.arraycopy(writeValue, 0, body, 7, writeValue.length);
                return body;
            }
            default:
                throw new IllegalStateException("Unsupported function: " + function);
        }
    }

    public int expectedResponseLength() {
        switch (function) {
            case READ_COILS:
            case READ_DISCRETE_INPUTS: {
                int bytes = (quantity + 7) / 8;
                return 3 + bytes + 2; // slave + fc + byteCount + data + CRC
            }
            case READ_HOLDING_REGISTERS:
            case READ_INPUT_REGISTERS: {
                int bytes = quantity * 2;
                return 3 + bytes + 2;
            }
            case WRITE_SINGLE_COIL:
            case WRITE_SINGLE_REGISTER:
                return 8; // echo: slave + fc + addr + value + CRC
            case WRITE_MULTIPLE_COILS:
            case WRITE_MULTIPLE_REGISTERS:
                return 8; // slave + fc + addr + qty + CRC
            default:
                throw new IllegalStateException("Unsupported function: " + function);
        }
    }

    private static void validateSlaveId(int slaveId) {
        if (slaveId < 0 || slaveId > 247) {
            throw new IllegalArgumentException("slaveId must be in [0, 247]: " + slaveId);
        }
    }
}
