package org.jetlinks.protocol.modbus.mapping;

public enum RegisterDataType {
    BIT(1, false),
    INT16(1, true),
    UINT16(1, true),
    INT32(2, true),
    UINT32(2, true),
    FLOAT32(2, true),
    INT64(4, true),
    FLOAT64(4, true);

    private final int registerCount;
    private final boolean word;

    RegisterDataType(int registerCount, boolean word) {
        this.registerCount = registerCount;
        this.word = word;
    }

    public int getRegisterCount() {
        return registerCount;
    }

    public boolean isWord() {
        return word;
    }

    public int byteLength() {
        return word ? registerCount * 2 : 1;
    }

    public static RegisterDataType parse(String raw) {
        if (raw == null) {
            return UINT16;
        }
        switch (raw.trim().toLowerCase()) {
            case "bit":
            case "bool":
            case "boolean":
                return BIT;
            case "int16":
            case "short":
                return INT16;
            case "uint16":
            case "ushort":
                return UINT16;
            case "int32":
            case "int":
                return INT32;
            case "uint32":
                return UINT32;
            case "float":
            case "float32":
                return FLOAT32;
            case "int64":
            case "long":
                return INT64;
            case "float64":
            case "double":
                return FLOAT64;
            default:
                throw new IllegalArgumentException("Unsupported register dataType: " + raw);
        }
    }
}
