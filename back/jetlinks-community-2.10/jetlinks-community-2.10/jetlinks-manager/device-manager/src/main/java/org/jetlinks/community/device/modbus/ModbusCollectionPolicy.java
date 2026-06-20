package org.jetlinks.community.device.modbus;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class ModbusCollectionPolicy {

    private Boolean collectEnabled = true;

    private Long scanIntervalMs = 5000L;

    private Long dispatchIntervalMs = 50L;

    private Long storageIntervalMs = 60000L;

    private Long responseTimeoutMs = 3000L;

    public static ModbusCollectionPolicy defaults() {
        return new ModbusCollectionPolicy();
    }

    public static ModbusCollectionPolicy from(Map<String, Object> config,
                                              ModbusCollectionPolicy fallback) {
        ModbusCollectionPolicy policy = copyOf(fallback == null ? defaults() : fallback);
        if (config == null) {
            return policy;
        }
        if (config.containsKey(ModbusAccessProperties.CONFIG_COLLECT_ENABLED)) {
            policy.setCollectEnabled(asBoolean(config.get(ModbusAccessProperties.CONFIG_COLLECT_ENABLED), true));
        }
        if (config.containsKey(ModbusAccessProperties.CONFIG_SCAN_INTERVAL_MS)) {
            policy.setScanIntervalMs(asLong(config.get(ModbusAccessProperties.CONFIG_SCAN_INTERVAL_MS), policy.getScanIntervalMs()));
        }
        if (config.containsKey(ModbusAccessProperties.CONFIG_DISPATCH_INTERVAL_MS)) {
            policy.setDispatchIntervalMs(asLong(config.get(ModbusAccessProperties.CONFIG_DISPATCH_INTERVAL_MS), policy.getDispatchIntervalMs()));
        }
        if (config.containsKey(ModbusAccessProperties.CONFIG_STORAGE_INTERVAL_MS)) {
            policy.setStorageIntervalMs(asLong(config.get(ModbusAccessProperties.CONFIG_STORAGE_INTERVAL_MS), policy.getStorageIntervalMs()));
        }
        if (config.containsKey(ModbusAccessProperties.CONFIG_RESPONSE_TIMEOUT_MS)) {
            policy.setResponseTimeoutMs(asLong(config.get(ModbusAccessProperties.CONFIG_RESPONSE_TIMEOUT_MS), policy.getResponseTimeoutMs()));
        }
        policy.validate();
        return policy;
    }

    public ModbusCollectionPolicy merge(ModbusCollectionPolicy override) {
        ModbusCollectionPolicy merged = copyOf(this);
        if (override == null) {
            return merged;
        }
        if (override.collectEnabled != null) {
            merged.collectEnabled = override.collectEnabled;
        }
        if (override.scanIntervalMs != null) {
            merged.scanIntervalMs = override.scanIntervalMs;
        }
        if (override.dispatchIntervalMs != null) {
            merged.dispatchIntervalMs = override.dispatchIntervalMs;
        }
        if (override.storageIntervalMs != null) {
            merged.storageIntervalMs = override.storageIntervalMs;
        }
        if (override.responseTimeoutMs != null) {
            merged.responseTimeoutMs = override.responseTimeoutMs;
        }
        merged.validate();
        return merged;
    }

    public void applyTo(Map<String, Object> config) {
        config.put(ModbusAccessProperties.CONFIG_COLLECT_ENABLED, Boolean.TRUE.equals(collectEnabled));
        config.put(ModbusAccessProperties.CONFIG_SCAN_INTERVAL_MS, scanIntervalMs);
        config.put(ModbusAccessProperties.CONFIG_DISPATCH_INTERVAL_MS, dispatchIntervalMs);
        config.put(ModbusAccessProperties.CONFIG_STORAGE_INTERVAL_MS, storageIntervalMs);
        config.put(ModbusAccessProperties.CONFIG_RESPONSE_TIMEOUT_MS, responseTimeoutMs);
    }

    public void validate() {
        if (scanIntervalMs == null || scanIntervalMs <= 0) {
            throw new IllegalArgumentException("scanIntervalMs must be greater than 0");
        }
        if (storageIntervalMs == null || storageIntervalMs <= 0) {
            throw new IllegalArgumentException("storageIntervalMs must be greater than 0");
        }
        if (responseTimeoutMs == null || responseTimeoutMs <= 0) {
            throw new IllegalArgumentException("responseTimeoutMs must be greater than 0");
        }
        if (dispatchIntervalMs == null || dispatchIntervalMs < 0) {
            throw new IllegalArgumentException("dispatchIntervalMs must be greater than or equal to 0");
        }
        if (storageIntervalMs < scanIntervalMs) {
            throw new IllegalArgumentException("storageIntervalMs must be greater than or equal to scanIntervalMs");
        }
        if (collectEnabled == null) {
            collectEnabled = true;
        }
    }

    public static ModbusCollectionPolicy copyOf(ModbusCollectionPolicy source) {
        ModbusCollectionPolicy policy = new ModbusCollectionPolicy();
        policy.setCollectEnabled(source.getCollectEnabled());
        policy.setScanIntervalMs(source.getScanIntervalMs());
        policy.setDispatchIntervalMs(source.getDispatchIntervalMs());
        policy.setStorageIntervalMs(source.getStorageIntervalMs());
        policy.setResponseTimeoutMs(source.getResponseTimeoutMs());
        return policy;
    }

    private static Long asLong(Object value, Long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                return Long.parseLong(text);
            }
        }
        return fallback;
    }

    private static Boolean asBoolean(Object value, Boolean fallback) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value != null) {
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                return Boolean.parseBoolean(text) || "1".equals(text);
            }
        }
        return fallback;
    }
}
