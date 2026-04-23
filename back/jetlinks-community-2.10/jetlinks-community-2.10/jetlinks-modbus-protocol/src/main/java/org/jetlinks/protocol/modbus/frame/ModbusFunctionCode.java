package org.jetlinks.protocol.modbus.frame;

public enum ModbusFunctionCode {

    READ_COILS(0x01, true, false),
    READ_DISCRETE_INPUTS(0x02, true, false),
    READ_HOLDING_REGISTERS(0x03, true, false),
    READ_INPUT_REGISTERS(0x04, true, false),
    WRITE_SINGLE_COIL(0x05, false, true),
    WRITE_SINGLE_REGISTER(0x06, false, true),
    WRITE_MULTIPLE_COILS(0x0F, false, true),
    WRITE_MULTIPLE_REGISTERS(0x10, false, true);

    public static final int EXCEPTION_FLAG = 0x80;

    private final int code;
    private final boolean read;
    private final boolean write;

    ModbusFunctionCode(int code, boolean read, boolean write) {
        this.code = code;
        this.read = read;
        this.write = write;
    }

    public int getCode() {
        return code;
    }

    public boolean isRead() {
        return read;
    }

    public boolean isWrite() {
        return write;
    }

    public boolean isBitOriented() {
        return this == READ_COILS
                || this == READ_DISCRETE_INPUTS
                || this == WRITE_SINGLE_COIL
                || this == WRITE_MULTIPLE_COILS;
    }

    public static ModbusFunctionCode of(int code) {
        int normalized = code & 0x7F;
        for (ModbusFunctionCode value : values()) {
            if (value.code == normalized) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported Modbus function code: 0x"
                + Integer.toHexString(normalized));
    }

    public static boolean isException(int rawCode) {
        return (rawCode & EXCEPTION_FLAG) != 0;
    }
}
