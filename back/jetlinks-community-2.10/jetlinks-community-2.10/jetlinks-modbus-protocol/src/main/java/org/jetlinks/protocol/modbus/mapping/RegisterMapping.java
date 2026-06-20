package org.jetlinks.protocol.modbus.mapping;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.jetlinks.protocol.modbus.frame.ModbusFunctionCode;

@Getter
@Builder
@ToString
public class RegisterMapping {

    /** Thing-model property id. */
    private final String propertyId;

    /** Function code to use for reads (and for writes when supported). */
    private final ModbusFunctionCode functionCode;

    /** Optional function code to use for writes. */
    private final ModbusFunctionCode writeFunctionCode;

    /** Starting register / coil address. */
    private final int address;

    /** Number of registers / coils (ignored for BIT types where the type defines it). */
    private final int quantity;

    private final RegisterDataType dataType;

    @Builder.Default
    private final ByteOrder byteOrder = ByteOrder.ABCD;

    /** Linear scale applied to raw value on read (raw * scale + offset). */
    @Builder.Default
    private final double scale = 1.0;

    @Builder.Default
    private final double offset = 0.0;

    @Builder.Default
    private final boolean writable = false;

    public int effectiveQuantity() {
        if (dataType == RegisterDataType.BIT) {
            return Math.max(1, quantity);
        }
        return Math.max(quantity, dataType.getRegisterCount());
    }

    public ModbusFunctionCode getReadFunctionCode() {
        if (functionCode == null || !functionCode.isRead()) {
            return null;
        }
        return functionCode;
    }

    public ModbusFunctionCode getWriteFunctionCode() {
        if (writeFunctionCode != null) {
            return writeFunctionCode;
        }
        if (functionCode != null && functionCode.isWrite()) {
            return functionCode;
        }
        if (!writable) {
            return null;
        }
        ModbusFunctionCode readFunctionCode = getReadFunctionCode();
        if (readFunctionCode == ModbusFunctionCode.READ_COILS) {
            return effectiveQuantity() == 1
                    ? ModbusFunctionCode.WRITE_SINGLE_COIL
                    : ModbusFunctionCode.WRITE_MULTIPLE_COILS;
        }
        if (readFunctionCode == ModbusFunctionCode.READ_HOLDING_REGISTERS) {
            return effectiveQuantity() == 1
                    ? ModbusFunctionCode.WRITE_SINGLE_REGISTER
                    : ModbusFunctionCode.WRITE_MULTIPLE_REGISTERS;
        }
        return null;
    }

    public ModbusFunctionCode requireReadFunctionCode() {
        ModbusFunctionCode fc = getReadFunctionCode();
        if (fc == null) {
            throw new IllegalStateException("Mapping for " + propertyId + " has no read function code");
        }
        return fc;
    }

    public ModbusFunctionCode requireWriteFunctionCode() {
        ModbusFunctionCode fc = getWriteFunctionCode();
        if (fc == null) {
            throw new IllegalStateException("Mapping for " + propertyId + " has no write function code");
        }
        return fc;
    }

    public boolean isReadable() {
        return getReadFunctionCode() != null;
    }

    public boolean isWritable() {
        return writable || getWriteFunctionCode() != null;
    }
}
