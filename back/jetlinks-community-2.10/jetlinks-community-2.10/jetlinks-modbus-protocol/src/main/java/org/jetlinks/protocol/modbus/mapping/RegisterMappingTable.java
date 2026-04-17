package org.jetlinks.protocol.modbus.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetlinks.protocol.modbus.frame.ModbusFunctionCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-product register map, indexed by property id. Parsed from the product
 * config value {@code registerMap} which is a JSON array such as:
 * <pre>
 * [
 *   {"propertyId":"temp","fc":3,"address":0,"quantity":1,"dataType":"int16","scale":0.1},
 *   {"propertyId":"run","fc":1,"address":8,"dataType":"bit","writable":true}
 * ]
 * </pre>
 */
public final class RegisterMappingTable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, RegisterMapping> byProperty;

    private RegisterMappingTable(Map<String, RegisterMapping> byProperty) {
        this.byProperty = byProperty;
    }

    public static RegisterMappingTable empty() {
        return new RegisterMappingTable(Collections.emptyMap());
    }

    /** Convenience factory used by tests. */
    public static RegisterMappingTable of(List<RegisterMapping> mappings) {
        Map<String, RegisterMapping> map = new LinkedHashMap<>();
        for (RegisterMapping m : mappings) {
            map.put(m.getPropertyId(), m);
        }
        return new RegisterMappingTable(Collections.unmodifiableMap(map));
    }

    public static RegisterMappingTable parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return empty();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return parse(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid registerMap JSON: " + e.getMessage(), e);
        }
    }

    public static RegisterMappingTable parse(JsonNode root) {
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("registerMap must be a JSON array");
        }
        Map<String, RegisterMapping> map = new LinkedHashMap<>();
        for (JsonNode node : root) {
            RegisterMapping mapping = parseOne(node);
            map.put(mapping.getPropertyId(), mapping);
        }
        return new RegisterMappingTable(Collections.unmodifiableMap(map));
    }

    private static RegisterMapping parseOne(JsonNode node) {
        String propertyId = text(node, "propertyId", "property", "id");
        if (propertyId == null) {
            throw new IllegalArgumentException("registerMap entry missing propertyId: " + node);
        }
        int fc = intValue(node, "functionCode", "fc");
        if (fc == Integer.MIN_VALUE) {
            throw new IllegalArgumentException("registerMap entry " + propertyId + " missing functionCode");
        }
        int address = intValue(node, "address", "addr");
        if (address == Integer.MIN_VALUE) {
            throw new IllegalArgumentException("registerMap entry " + propertyId + " missing address");
        }
        RegisterDataType type = RegisterDataType.parse(text(node, "dataType", "type"));
        int quantity = intValue(node, "quantity", "qty");
        if (quantity == Integer.MIN_VALUE) {
            quantity = type.getRegisterCount();
        }
        return RegisterMapping.builder()
                .propertyId(propertyId)
                .functionCode(ModbusFunctionCode.of(fc))
                .address(address)
                .quantity(quantity)
                .dataType(type)
                .byteOrder(ByteOrder.parse(text(node, "byteOrder", "order")))
                .scale(doubleValue(node, "scale", 1.0))
                .offset(doubleValue(node, "offset", 0.0))
                .writable(boolValue(node, "writable", false))
                .build();
    }

    public RegisterMapping require(String propertyId) {
        RegisterMapping m = byProperty.get(propertyId);
        if (m == null) {
            throw new IllegalArgumentException("No registerMap entry for property: " + propertyId);
        }
        return m;
    }

    public RegisterMapping find(String propertyId) {
        return byProperty.get(propertyId);
    }

    public List<RegisterMapping> all() {
        return new ArrayList<>(byProperty.values());
    }

    public boolean isEmpty() {
        return byProperty.isEmpty();
    }

    private static String text(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) {
                return v.asText();
            }
        }
        return null;
    }

    private static int intValue(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && v.isNumber()) {
                return v.asInt();
            }
            if (v != null && v.isTextual()) {
                try {
                    return Integer.decode(v.asText());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    private static double doubleValue(JsonNode node, String key, double def) {
        JsonNode v = node.get(key);
        if (v != null && v.isNumber()) {
            return v.asDouble();
        }
        return def;
    }

    private static boolean boolValue(JsonNode node, String key, boolean def) {
        JsonNode v = node.get(key);
        if (v != null && v.isBoolean()) {
            return v.asBoolean();
        }
        return def;
    }
}
