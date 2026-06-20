package org.jetlinks.community.device.modbus;

import com.fasterxml.jackson.core.type.TypeReference;
import org.jetlinks.community.utils.ObjectMappers;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class ModbusAccessProperties {

    public static final String PROTOCOL_ID = "modbus-rtu.v1";
    public static final String TCP_CLIENT_PROVIDER = "tcp-client-gateway";
    public static final String TCP_SERVER_PROVIDER = "tcp-server-gateway";

    public static final String CONFIG_ACCESS_ID = "modbusAccessId";
    public static final String CONFIG_CONNECTION_MODE = "connectionMode";
    public static final String CONFIG_SLAVE_ID = "slaveId";
    public static final String CONFIG_REGISTER_MAP = "registerMap";
    public static final String CONFIG_COLLECT_ENABLED = "collectEnabled";
    public static final String CONFIG_SCAN_INTERVAL_MS = "scanIntervalMs";
    public static final String CONFIG_DISPATCH_INTERVAL_MS = "dispatchIntervalMs";
    public static final String CONFIG_STORAGE_INTERVAL_MS = "storageIntervalMs";
    public static final String CONFIG_RESPONSE_TIMEOUT_MS = "responseTimeoutMs";

    private static final TypeReference<List<Map<String, Object>>> REGISTER_MAP_TYPE = new TypeReference<List<Map<String, Object>>>() {
    };

    private ModbusAccessProperties() {
    }

    public static String sanitizeId(String value, String fallbackPrefix) {
        String source = StringUtils.hasText(value) ? value : fallbackPrefix;
        String cleaned = source.replaceAll("[^0-9a-zA-Z_-]", "_");
        cleaned = cleaned.replaceAll("_+", "_");
        if (!StringUtils.hasText(cleaned)) {
            cleaned = fallbackPrefix;
        }
        return cleaned;
    }

    public static List<Map<String, Object>> normalizeRegisterMap(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof String) {
            String text = String.valueOf(value).trim();
            if (!StringUtils.hasText(text)) {
                return Collections.emptyList();
            }
            return ObjectMappers.JSON_MAPPER.convertValue(ObjectMappers.parseJson(text, List.class), REGISTER_MAP_TYPE);
        }
        return ObjectMappers.JSON_MAPPER.convertValue(value, REGISTER_MAP_TYPE);
    }

    public static String serializeRegisterMap(List<Map<String, Object>> rows) {
        return ObjectMappers.toJsonString(rows == null ? Collections.emptyList() : rows);
    }

    public static List<String> readablePropertyIds(List<Map<String, Object>> registerMap) {
        if (registerMap == null) {
            return Collections.emptyList();
        }
        return registerMap
                .stream()
                .filter(row -> readFunctionCode(row) != null)
                .map(row -> string(first(row, "propertyId", "property")))
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
    }

    public static String buildMetadata(List<Map<String, Object>> registerMap) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        List<Map<String, Object>> properties = new ArrayList<>();
        metadata.put("properties", properties);
        metadata.put("functions", Collections.emptyList());
        metadata.put("events", Collections.emptyList());
        metadata.put("tags", Collections.emptyList());

        for (Map<String, Object> row : registerMap == null ? Collections.<Map<String, Object>>emptyList() : registerMap) {
            String propertyId = string(first(row, "propertyId", "property"));
            if (!StringUtils.hasText(propertyId)) {
                continue;
            }
            Integer readFunctionCode = readFunctionCode(row);
            Integer writeFunctionCode = writeFunctionCode(row);
            String propertyName = string(first(row, "propertyName", "name"));
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("id", propertyId);
            property.put("name", StringUtils.hasText(propertyName) ? propertyName : propertyId);
            property.put("valueType", valueType(row));
            property.put("expands", expands(readFunctionCode, writeFunctionCode));
            property.put("description", "Modbus "
                    + (readFunctionCode == null ? writeFunctionCode : readFunctionCode)
                    + " @ " + asInt(first(row, "address", "addr"), 0));
            properties.add(property);
        }
        return ObjectMappers.toJsonString(metadata);
    }

    public static void validateRegisterMap(List<Map<String, Object>> registerMap) {
        if (registerMap == null || registerMap.isEmpty()) {
            throw new IllegalArgumentException("registerMap must not be empty");
        }
        Set<String> propertyIds = new HashSet<>();
        for (int i = 0; i < registerMap.size(); i++) {
            Map<String, Object> row = registerMap.get(i);
            String propertyId = string(first(row, "propertyId", "property"));
            if (!StringUtils.hasText(propertyId)) {
                throw new IllegalArgumentException("registerMap[" + i + "].propertyId must not be empty");
            }
            if (!propertyIds.add(propertyId)) {
                throw new IllegalArgumentException("registerMap[" + i + "].propertyId is duplicated: " + propertyId);
            }
            String dataType = dataType(row);
            int typeQuantity = dataTypeRegisterCount(dataType);
            int address = asInt(first(row, "address", "addr"), -1);
            if (address < 0 || address > 65535) {
                throw new IllegalArgumentException("registerMap[" + i + "].address must be 0-65535");
            }
            int quantity = asInt(first(row, "quantity", "qty"), 1);
            if (quantity <= 0) {
                throw new IllegalArgumentException("registerMap[" + i + "].quantity must be greater than 0");
            }
            int effectiveQuantity = "BIT".equals(dataType)
                    ? Math.max(quantity, 1)
                    : Math.max(quantity, typeQuantity);
            long lastAddress = (long) address + effectiveQuantity - 1;
            if (lastAddress > 65535) {
                throw new IllegalArgumentException("registerMap[" + i + "].address + quantity exceeds 65535");
            }
            Integer readFc = readFunctionCode(row);
            Integer writeFc = writeFunctionCode(row);
            if (readFc == null && writeFc == null) {
                throw new IllegalArgumentException("registerMap[" + i + "].functionCode must be one of 1/2/3/4/5/6/15/16");
            }
            if (bool(first(row, "writable", "write")) && writeFc == null) {
                throw new IllegalArgumentException("registerMap[" + i + "] writable=true requires writeFunctionCode or readable FC1/FC3");
            }
            if (readFc != null) {
                validateReadFunctionCode(i, readFc, dataType, effectiveQuantity);
            }
            if (writeFc != null) {
                validateWriteFunctionCode(i, writeFc, dataType, effectiveQuantity);
            }
        }
    }

    public static Object first(Map<String, Object> row, String first, String second) {
        Object value = row.get(first);
        return value == null ? row.get(second) : value;
    }

    public static int asInt(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            String text = String.valueOf(value).trim();
            if (text.startsWith("0x") || text.startsWith("0X")) {
                return Integer.parseInt(text.substring(2), 16);
            }
            if (!text.isEmpty()) {
                return Integer.parseInt(text);
            }
        }
        return fallback;
    }

    public static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value != null) {
            String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
            return "true".equals(text) || "1".equals(text) || "yes".equals(text) || "y".equals(text);
        }
        return false;
    }

    private static boolean isReadFunctionCode(int functionCode) {
        return functionCode >= 1 && functionCode <= 4;
    }

    private static boolean isWriteFunctionCode(int functionCode) {
        return functionCode == 5 || functionCode == 6 || functionCode == 15 || functionCode == 16;
    }

    private static Integer readFunctionCode(Map<String, Object> row) {
        Integer readFc = optionalFunctionCode(first(row, "readFunctionCode", "readFc"));
        Integer legacyFc = optionalFunctionCode(first(row, "functionCode", "fc"));
        if (readFc != null) {
            return readFc;
        }
        return legacyFc != null && isReadFunctionCode(legacyFc) ? legacyFc : null;
    }

    private static Integer writeFunctionCode(Map<String, Object> row) {
        Integer writeFc = optionalFunctionCode(first(row, "writeFunctionCode", "writeFc"));
        Integer legacyFc = optionalFunctionCode(first(row, "functionCode", "fc"));
        if (writeFc != null) {
            return writeFc;
        }
        if (legacyFc != null && isWriteFunctionCode(legacyFc)) {
            return legacyFc;
        }
        if (!bool(first(row, "writable", "write"))) {
            return null;
        }
        Integer readFc = readFunctionCode(row);
        String dataType = dataType(row);
        int quantity = Math.max(asInt(first(row, "quantity", "qty"), 1), dataTypeRegisterCount(dataType));
        if (readFc != null && readFc == 1 && "BIT".equals(dataType)) {
            return quantity == 1 ? 5 : 15;
        }
        if (readFc != null && readFc == 3 && !"BIT".equals(dataType)) {
            return quantity == 1 ? 6 : 16;
        }
        return null;
    }

    private static Integer optionalFunctionCode(Object value) {
        int functionCode = asInt(value, Integer.MIN_VALUE);
        if (functionCode == Integer.MIN_VALUE) {
            return null;
        }
        if (!isReadFunctionCode(functionCode) && !isWriteFunctionCode(functionCode)) {
            return functionCode;
        }
        return functionCode;
    }

    private static void validateReadFunctionCode(int index, int functionCode, String dataType, int quantity) {
        if (!isReadFunctionCode(functionCode)) {
            throw new IllegalArgumentException("registerMap[" + index + "].readFunctionCode must be one of 1/2/3/4");
        }
        if ((functionCode == 1 || functionCode == 2) && !"BIT".equals(dataType)) {
            throw new IllegalArgumentException("registerMap[" + index + "] FC1/FC2 only support BIT");
        }
        if ((functionCode == 3 || functionCode == 4) && "BIT".equals(dataType)) {
            throw new IllegalArgumentException("registerMap[" + index + "] FC3/FC4 only support word data types");
        }
        int maxQuantity = (functionCode == 1 || functionCode == 2) ? 2000 : 125;
        if (quantity > maxQuantity) {
            throw new IllegalArgumentException("registerMap[" + index + "].quantity exceeds FC"
                    + functionCode + " limit " + maxQuantity);
        }
    }

    private static void validateWriteFunctionCode(int index, int functionCode, String dataType, int quantity) {
        if (!isWriteFunctionCode(functionCode)) {
            throw new IllegalArgumentException("registerMap[" + index + "].writeFunctionCode must be one of 5/6/15/16");
        }
        if ((functionCode == 5 || functionCode == 15) && !"BIT".equals(dataType)) {
            throw new IllegalArgumentException("registerMap[" + index + "] FC5/FC15 only support BIT");
        }
        if ((functionCode == 6 || functionCode == 16) && "BIT".equals(dataType)) {
            throw new IllegalArgumentException("registerMap[" + index + "] FC6/FC16 only support word data types");
        }
        if ((functionCode == 5 || functionCode == 6) && quantity != 1) {
            throw new IllegalArgumentException("registerMap[" + index + "] FC" + functionCode + " requires quantity=1");
        }
        int maxQuantity = functionCode == 15 ? 1968 : functionCode == 16 ? 123 : 1;
        if (quantity > maxQuantity) {
            throw new IllegalArgumentException("registerMap[" + index + "].quantity exceeds FC"
                    + functionCode + " limit " + maxQuantity);
        }
    }

    private static String dataType(Map<String, Object> row) {
        String dataType = String.valueOf(row.getOrDefault("dataType", "INT16")).toUpperCase(Locale.ROOT);
        switch (dataType) {
            case "BIT":
            case "INT16":
            case "UINT16":
            case "INT32":
            case "UINT32":
            case "FLOAT32":
            case "INT64":
            case "FLOAT64":
                return dataType;
            default:
                throw new IllegalArgumentException("Unsupported register dataType: " + dataType);
        }
    }

    private static int dataTypeRegisterCount(String dataType) {
        switch (dataType) {
            case "BIT":
            case "INT16":
            case "UINT16":
                return 1;
            case "INT32":
            case "UINT32":
            case "FLOAT32":
                return 2;
            case "INT64":
            case "FLOAT64":
                return 4;
            default:
                throw new IllegalArgumentException("Unsupported register dataType: " + dataType);
        }
    }

    private static Map<String, Object> valueType(Map<String, Object> row) {
        String dataType = dataType(row);
        Map<String, Object> valueType = new LinkedHashMap<>();
        switch (dataType) {
            case "BIT":
                valueType.put("type", "boolean");
                break;
            case "FLOAT32":
                valueType.put("type", "float");
                break;
            case "FLOAT64":
                valueType.put("type", "double");
                break;
            case "INT32":
            case "UINT32":
            case "INT64":
                valueType.put("type", "long");
                break;
            default:
                valueType.put("type", "int");
                break;
        }
        Object unit = row.get("unit");
        if (unit != null && StringUtils.hasText(String.valueOf(unit))) {
            valueType.put("unit", String.valueOf(unit));
        }
        return valueType;
    }

    private static Map<String, Object> expands(Integer readFunctionCode, Integer writeFunctionCode) {
        Map<String, Object> expands = new LinkedHashMap<>();
        List<String> type = new ArrayList<>(2);
        if (readFunctionCode != null) {
            type.add("read");
        }
        if (writeFunctionCode != null) {
            type.add("write");
        }
        if (type.isEmpty()) {
            type.add("read");
        }
        expands.put("source", "device");
        expands.put("type", type);
        return expands;
    }
}
