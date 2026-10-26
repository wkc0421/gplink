package org.jetlinks.community.configure.redis;

import org.jetlinks.community.codec.Serializers;
import org.jetlinks.core.utils.SerializeUtils;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.UTFDataFormatException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ObjectRedisSerializerTest {

    private final ObjectRedisSerializer serializer = new ObjectRedisSerializer();

    @Test
    void preservesLegacyEncodingAndReadsExistingValues() throws Exception {
        for (Object value : new Object[]{"short string", "温度", 123, true, Map.of("source", "device")}) {
            byte[] legacy = legacySerialize(value);
            assertArrayEquals(legacy, serializer.serialize(value));
            assertEquals(value, serializer.deserialize(legacy));
        }
        assertNull(serializer.serialize(null));
        assertNull(serializer.deserialize(null));
    }

    @Test
    void supportsLongStringsWithUnmodifiedLegacyReader() throws Exception {
        // writeUTF uses modified UTF-8: NUL and surrogate pairs also matter.
        for (String value : new String[]{
            "a".repeat(65535), "a".repeat(65536), "温".repeat(22000),
            "\0".repeat(32768), "\uD83D\uDD25".repeat(11000)
        }) {
            byte[] bytes = serializer.serialize(value);
            assertEquals(value, serializer.deserialize(bytes));
            assertEquals(value, legacyDeserialize(bytes));
        }
    }

    @Test
    void supportsNestedMetadataAndResetsStreamAfterFailedCompactWrite() throws Exception {
        String metadata = "{\"name\":\"灭火主机\",\"properties\":\"" + "温度".repeat(22000) + "\"}";
        assertThrows(UTFDataFormatException.class, () -> legacySerialize(metadata));
        Map<String, Object> configs = new LinkedHashMap<>();
        configs.put("protocol", "KHMQTT");
        configs.put("metadata", metadata);
        byte[] bytes = serializer.serialize(configs);
        assertEquals(configs, serializer.deserialize(bytes));
        assertEquals(configs, legacyDeserialize(bytes));
        assertArrayEquals(legacySerialize("next value"), serializer.serialize("next value"));
    }

    private static byte[] legacySerialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutput output = Serializers.getDefault().createOutput(bytes)) {
            SerializeUtils.writeObject(value, output);
            output.flush();
            return bytes.toByteArray();
        }
    }

    private static Object legacyDeserialize(byte[] bytes) throws Exception {
        try (ObjectInput input = Serializers.getDefault().createInput(new ByteArrayInputStream(bytes))) {
            return SerializeUtils.readObject(input);
        }
    }
}
