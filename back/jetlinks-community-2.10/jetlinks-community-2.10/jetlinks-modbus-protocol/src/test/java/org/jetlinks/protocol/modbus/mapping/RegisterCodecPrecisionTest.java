package org.jetlinks.protocol.modbus.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.*;

public class RegisterCodecPrecisionTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    public void float32ValuesAreRoundedForReportingWithoutPadding() throws Exception {
        RegisterMapping mapping = mapping(RegisterDataType.FLOAT32, 1, 0);
        assertEquals("227.398", json.writeValueAsString(decodeFloat(mapping, 227.3983f)));
        assertEquals("926.4", json.writeValueAsString(decodeFloat(mapping, 926.4f)));
        assertEquals("0.838", json.writeValueAsString(decodeFloat(mapping, 0.838f)));
        assertEquals("0.581", json.writeValueAsString(decodeFloat(mapping, 0.581f)));
    }

    @Test
    public void roundsAfterScaleAndOffsetIncludingNegativeHalf() {
        RegisterMapping scaled = mapping(RegisterDataType.INT16, 0.001, 0.0005);
        assertEquals(1.235, (Double) RegisterCodec.decode(scaled, new byte[]{0x04, (byte) 0xd2}), 0);

        RegisterMapping doubleMapping = mapping(RegisterDataType.FLOAT64, 1, 0);
        assertEquals(-1.235, (Double) RegisterCodec.decode(doubleMapping,
                ByteBuffer.allocate(8).putDouble(-1.2345).array()), 0);
        assertEquals(1.234, (Double) RegisterCodec.decode(doubleMapping,
                ByteBuffer.allocate(8).putDouble(1.2344).array()), 0);
    }

    @Test
    public void preservesBooleanAndExistingNumericType() {
        assertEquals(Boolean.TRUE, RegisterCodec.decode(mapping(RegisterDataType.BIT, 1, 0), new byte[]{1}));
        Object zero = RegisterCodec.decode(mapping(RegisterDataType.UINT16, 1, 0), new byte[]{0, 0});
        assertEquals(Double.class, zero.getClass());
        assertEquals(0.0, (Double) zero, 0);
    }

    @Test
    public void preservesNonFiniteValues() {
        RegisterMapping mapping = mapping(RegisterDataType.FLOAT32, 1, 0);
        assertTrue(Double.isNaN((Double) decodeFloat(mapping, Float.NaN)));
        assertEquals(Double.POSITIVE_INFINITY, (Double) decodeFloat(mapping, Float.POSITIVE_INFINITY), 0);
        assertEquals(Double.NEGATIVE_INFINITY, (Double) decodeFloat(mapping, Float.NEGATIVE_INFINITY), 0);
    }

    private static Object decodeFloat(RegisterMapping mapping, float value) {
        return RegisterCodec.decode(mapping, ByteBuffer.allocate(4).putFloat(value).array());
    }

    private static RegisterMapping mapping(RegisterDataType type, double scale, double offset) {
        return RegisterMapping.builder().dataType(type).scale(scale).offset(offset).build();
    }
}
