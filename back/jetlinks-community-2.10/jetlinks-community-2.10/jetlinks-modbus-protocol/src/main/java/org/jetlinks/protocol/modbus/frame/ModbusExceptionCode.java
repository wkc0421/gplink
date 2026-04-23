package org.jetlinks.protocol.modbus.frame;

public enum ModbusExceptionCode {

    ILLEGAL_FUNCTION(0x01, "Illegal function"),
    ILLEGAL_DATA_ADDRESS(0x02, "Illegal data address"),
    ILLEGAL_DATA_VALUE(0x03, "Illegal data value"),
    SLAVE_DEVICE_FAILURE(0x04, "Slave device failure"),
    ACKNOWLEDGE(0x05, "Acknowledge"),
    SLAVE_DEVICE_BUSY(0x06, "Slave device busy"),
    NEGATIVE_ACKNOWLEDGE(0x07, "Negative acknowledge"),
    MEMORY_PARITY_ERROR(0x08, "Memory parity error"),
    GATEWAY_PATH_UNAVAILABLE(0x0A, "Gateway path unavailable"),
    GATEWAY_TARGET_NO_RESPONSE(0x0B, "Gateway target device failed to respond"),
    UNKNOWN(0xFF, "Unknown exception");

    private final int code;
    private final String message;

    ModbusExceptionCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public static ModbusExceptionCode of(int code) {
        for (ModbusExceptionCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return UNKNOWN;
    }
}
