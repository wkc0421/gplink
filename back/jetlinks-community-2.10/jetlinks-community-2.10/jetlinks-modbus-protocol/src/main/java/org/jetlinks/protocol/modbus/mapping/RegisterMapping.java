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
}
