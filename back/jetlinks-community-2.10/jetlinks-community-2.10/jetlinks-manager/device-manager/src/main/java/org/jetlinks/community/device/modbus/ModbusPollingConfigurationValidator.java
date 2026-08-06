package org.jetlinks.community.device.modbus;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.hswebframework.web.crud.events.EntityPrepareSaveEvent;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ModbusPollingConfigurationValidator {

    static final long MIN_POLL_INTERVAL_MS = 1000L;
    static final long MAX_POLL_INTERVAL_MS = 86400000L;
    static final long MAX_DEVICE_OR_FRAME_INTERVAL_MS = 60000L;
    static final int MAX_RETRY_COUNT = 10;
    static final long MAX_READ_ADDRESS_GAP = 2L;

    private final LocalDeviceProductService productService;

    public ModbusPollingConfigurationValidator(LocalDeviceProductService productService) {
        this.productService = productService;
    }

    @EventListener
    public void validateProduct(EntityPrepareSaveEvent<DeviceProductEntity> event) {
        event.async(Flux
                .fromIterable(event.getEntity())
                .filter(this::isModbusProduct)
                .doOnNext(product -> validateConfiguration(
                        product.getConfiguration(),
                        readable(product.getConfiguration(), product.getMetadata()))));
    }

    @EventListener
    public void validateDevice(EntityPrepareSaveEvent<DeviceInstanceEntity> event) {
        event.async(Flux
                .fromIterable(event.getEntity())
                .filter(device -> bool(config(device.getConfiguration()).get("pollOverrideEnabled")))
                .flatMap(device -> {
                    if (!StringUtils.hasText(device.getProductId())) {
                        return Mono.empty();
                    }
                    return productService
                            .concurrentFindById(device.getProductId())
                            .filter(this::isModbusProduct)
                            .doOnNext(product -> validateConfiguration(
                                    device.getConfiguration(),
                                    readable(product.getConfiguration(), product.getMetadata())))
                            .then();
                }));
    }

    private boolean isModbusProduct(DeviceProductEntity product) {
        return product != null
                && StringUtils.hasText(product.getMessageProtocol())
                && product.getMessageProtocol().toLowerCase().startsWith("modbus");
    }

    void validateConfiguration(Map<String, Object> rawConfig, List<String> readable) {
        Map<String, Object> config = config(rawConfig);
        String scheduleType = String.valueOf(config.getOrDefault(
                "pollScheduleType", "FIXED_DELAY"));
        if (!"FIXED_DELAY".equalsIgnoreCase(scheduleType)
                && !"CRON".equalsIgnoreCase(scheduleType)) {
            throw new IllegalArgumentException("pollScheduleType must be FIXED_DELAY or CRON");
        }

        long interval = number(config.get("pollIntervalMs"),
                number(config.get("probeIntervalMs"), 30000L));
        range(interval, MIN_POLL_INTERVAL_MS, MAX_POLL_INTERVAL_MS,
                "pollIntervalMs must be between 1000 and 86400000");

        long deviceInterval = number(config.get("pollDeviceIntervalMs"), 100L);
        range(deviceInterval, 0L, MAX_DEVICE_OR_FRAME_INTERVAL_MS,
                "pollDeviceIntervalMs must be between 0 and 60000");

        long frameInterval = number(config.get("pollFrameIntervalMs"), 100L);
        range(frameInterval, 0L, MAX_DEVICE_OR_FRAME_INTERVAL_MS,
                "pollFrameIntervalMs must be between 0 and 60000");

        long retryCount = number(config.get("pollRetryCount"), 0L);
        range(retryCount, 0L, MAX_RETRY_COUNT,
                "pollRetryCount must be between 0 and 10");

        if ("CRON".equalsIgnoreCase(scheduleType)) {
            String cron = String.valueOf(config.getOrDefault("pollCron", ""));
            if (!StringUtils.hasText(cron)) {
                throw new IllegalArgumentException("pollCron must not be empty when pollScheduleType=CRON");
            }
            try {
                CronExpression.parse(cron);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("pollCron is not a valid Spring cron expression", error);
            }
        }
        long registerWindow = number(config.get("maxReadRegistersPerRequest"), 60);
        long bitWindow = number(config.get("maxReadBitsPerRequest"), 512);
        long addressGap = number(config.get("maxReadAddressGap"), 2);
        if (registerWindow < 1 || registerWindow > 125) {
            throw new IllegalArgumentException("maxReadRegistersPerRequest must be between 1 and 125");
        }
        if (bitWindow < 1 || bitWindow > 2000) {
            throw new IllegalArgumentException("maxReadBitsPerRequest must be between 1 and 2000");
        }
        if (addressGap < 0 || addressGap > MAX_READ_ADDRESS_GAP) {
            throw new IllegalArgumentException("maxReadAddressGap must be between 0 and 2");
        }

        List<String> selected = strings(config.get("pollPropertyIds"));
        if (!selected.isEmpty() && !readable.containsAll(selected)) {
            Set<String> invalid = new LinkedHashSet<>(selected);
            invalid.removeAll(readable);
            throw new IllegalArgumentException(
                    "pollPropertyIds contains missing, unreadable or write-only properties: " + invalid);
        }
    }

    private void range(long value, long min, long max, String message) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(message);
        }
    }

    private List<String> readable(Map<String, Object> rawConfig, String metadata) {
        Object registerMap = config(rawConfig).get("registerMap");
        if (registerMap != null) {
            JSONArray rows = registerMap instanceof Collection
                    ? JSON.parseArray(JSON.toJSONString(registerMap))
                    : JSON.parseArray(String.valueOf(registerMap));
            List<String> ids = new ArrayList<>();
            for (Object value : rows) {
                JSONObject row = value instanceof JSONObject
                        ? (JSONObject) value
                        : JSON.parseObject(JSON.toJSONString(value));
                int function = row.getIntValue("functionCode");
                if (function == 0) {
                    function = row.getIntValue("fc");
                }
                if (function >= 1 && function <= 4
                        && StringUtils.hasText(row.getString("propertyId"))) {
                    ids.add(row.getString("propertyId"));
                }
            }
            return ids;
        }
        if (!StringUtils.hasText(metadata)) {
            return Collections.emptyList();
        }
        JSONObject object = JSON.parseObject(metadata);
        JSONArray properties = object.getJSONArray("properties");
        if (properties == null) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        for (Object value : properties) {
            JSONObject property = (JSONObject) value;
            JSONObject expands = property.getJSONObject("expands");
            Object types = expands == null ? null : expands.get("type");
            boolean readable = types instanceof Collection
                    ? ((Collection<?>) types).stream().anyMatch(item -> "read".equalsIgnoreCase(String.valueOf(item)))
                    : "read".equalsIgnoreCase(String.valueOf(types));
            if (readable && StringUtils.hasText(property.getString("id"))) {
                ids.add(property.getString("id"));
            }
        }
        return ids;
    }

    private Map<String, Object> config(Map<String, Object> value) {
        return value == null ? Collections.emptyMap() : value;
    }

    private boolean bool(Object value) {
        return Boolean.TRUE.equals(value)
                || "true".equalsIgnoreCase(String.valueOf(value))
                || "1".equals(String.valueOf(value));
    }

    private long number(Object value, long defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid numeric Modbus polling configuration: " + value, error);
        }
    }

    private List<String> strings(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof Collection) {
            List<String> result = new ArrayList<>();
            for (Object item : (Collection<?>) value) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return Collections.emptyList();
        }
        return text.startsWith("[")
                ? JSON.parseArray(text, String.class)
                : java.util.Arrays.asList(text.split(","));
    }
}
