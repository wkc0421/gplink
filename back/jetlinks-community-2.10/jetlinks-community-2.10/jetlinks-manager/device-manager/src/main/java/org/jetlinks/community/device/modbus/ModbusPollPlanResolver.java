package org.jetlinks.community.device.modbus;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.core.metadata.PropertyMetadata;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
final class ModbusPollPlanResolver {

    private final LocalDeviceInstanceService deviceService;
    private final LocalDeviceProductService productService;

    ModbusPollPlanResolver(LocalDeviceInstanceService deviceService,
                           LocalDeviceProductService productService) {
        this.deviceService = deviceService;
        this.productService = productService;
    }

    Mono<Boolean> isModbusGateway(String deviceId) {
        return deviceService
                .findById(deviceId)
                .filter(device -> device.getParentId() == null || device.getParentId().isEmpty())
                .flatMap(device -> productService.concurrentFindById(device.getProductId()))
                .map(DeviceProductEntity::getMessageProtocol)
                .map(protocol -> protocol != null && protocol.startsWith("modbus-rtu"))
                .defaultIfEmpty(false);
    }

    Flux<ModbusPollPlan> resolve(String gatewayId) {
        return deviceService
                .createQuery()
                .where(DeviceInstanceEntity::getParentId, gatewayId)
                .fetch()
                .flatMap(device -> productService
                        .concurrentFindById(device.getProductId())
                        .map(product -> resolveDevice(device, product))
                        .onErrorResume(error -> {
                            log.warn("Failed to resolve Modbus poll plan for device [{}]",
                                    device.getId(), error);
                            return reactor.core.publisher.Mono.empty();
                        }))
                .filter(DevicePlan::isEnabled)
                .collectList()
                .flatMapMany(this::groupPlans);
    }

    private Flux<ModbusPollPlan> groupPlans(List<DevicePlan> devicePlans) {
        Map<String, PlanBuilder> grouped = new LinkedHashMap<>();
        for (DevicePlan devicePlan : devicePlans) {
            String id = devicePlan.override
                    ? "device:" + devicePlan.deviceId
                    : "product:" + devicePlan.productId;
            PlanBuilder builder = grouped.computeIfAbsent(
                    id,
                    ignore -> new PlanBuilder(id, devicePlan));
            builder.deviceIds.add(devicePlan.deviceId);
        }
        return Flux.fromIterable(grouped.values()).map(PlanBuilder::build);
    }

    private DevicePlan resolveDevice(DeviceInstanceEntity device, DeviceProductEntity product) {
        Map<String, Object> deviceConfig = map(device.getConfiguration());
        Map<String, Object> productConfig = map(product.getConfiguration());
        boolean override = bool(deviceConfig.get("pollOverrideEnabled"), false);
        Map<String, Object> config = override ? deviceConfig : productConfig;
        boolean enabled = bool(config.get("pollEnabled"), false);

        List<String> readable = readablePropertyIds(product);
        List<String> selected = strings(config.get("pollPropertyIds"));
        if (selected.isEmpty()) {
            selected = readable;
        } else if (!readable.containsAll(selected)) {
            Set<String> invalid = new LinkedHashSet<>(selected);
            invalid.removeAll(readable);
            throw new IllegalArgumentException("unreadable Modbus properties: " + invalid);
        }

        String typeValue = string(config.get("pollScheduleType"), "FIXED_DELAY");
        ModbusPollPlan.ScheduleType type = "CRON".equalsIgnoreCase(typeValue)
                ? ModbusPollPlan.ScheduleType.CRON
                : ModbusPollPlan.ScheduleType.FIXED_DELAY;
        long interval = number(
                config.get("pollIntervalMs"),
                number(config.get("probeIntervalMs"), 30000L));

        return new DevicePlan(
                device.getId(),
                product.getId(),
                override,
                enabled && !selected.isEmpty(),
                type,
                interval,
                string(config.get("pollCron"), null),
                number(config.get("pollDeviceIntervalMs"), 100L),
                number(config.get("pollFrameIntervalMs"), 100L),
                (int) number(config.get("pollRetryCount"), 0L),
                selected);
    }

    private List<String> readablePropertyIds(DeviceProductEntity product) {
        Object registerMap = map(product.getConfiguration()).get("registerMap");
        JSONArray rows = null;
        if (registerMap instanceof Collection) {
            rows = JSON.parseArray(JSON.toJSONString(registerMap));
        } else if (registerMap != null) {
            try {
                rows = JSON.parseArray(String.valueOf(registerMap));
            } catch (Exception ignore) {
                log.warn("Invalid registerMap on product [{}]", product.getId());
            }
        }
        if (rows != null) {
            List<String> ids = new ArrayList<>();
            for (Object rowValue : rows) {
                JSONObject row = rowValue instanceof JSONObject
                        ? (JSONObject) rowValue
                        : JSON.parseObject(JSON.toJSONString(rowValue));
                int function = row.getIntValue("functionCode");
                if (function == 0) {
                    function = row.getIntValue("fc");
                }
                String propertyId = row.getString("propertyId");
                if (propertyId != null && function >= 1 && function <= 4) {
                    ids.add(propertyId);
                }
            }
            return ids;
        }
        List<String> ids = new ArrayList<>();
        for (PropertyMetadata property : product.parseMetadata().getProperties()) {
            ids.add(property.getId());
        }
        return ids;
    }

    private Map<String, Object> map(Map<String, Object> value) {
        return value == null ? Collections.emptyMap() : value;
    }

    private List<String> strings(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof Collection) {
            List<String> result = new ArrayList<>();
            for (Object item : (Collection<?>) value) {
                if (item != null && !String.valueOf(item).trim().isEmpty()) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return Collections.emptyList();
        }
        if (text.startsWith("[")) {
            return JSON.parseArray(text, String.class);
        }
        List<String> result = new ArrayList<>();
        for (String item : text.split(",")) {
            if (!item.trim().isEmpty()) {
                result.add(item.trim());
            }
        }
        return result;
    }

    private boolean bool(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return "true".equalsIgnoreCase(String.valueOf(value))
                || "1".equals(String.valueOf(value));
    }

    private long number(Object value, long defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignore) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String string(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static final class DevicePlan {
        final String deviceId;
        final String productId;
        final boolean override;
        final boolean enabled;
        final ModbusPollPlan.ScheduleType scheduleType;
        final long intervalMs;
        final String cron;
        final long deviceIntervalMs;
        final long frameIntervalMs;
        final int retryCount;
        final List<String> propertyIds;

        private DevicePlan(String deviceId,
                           String productId,
                           boolean override,
                           boolean enabled,
                           ModbusPollPlan.ScheduleType scheduleType,
                           long intervalMs,
                           String cron,
                           long deviceIntervalMs,
                           long frameIntervalMs,
                           int retryCount,
                           List<String> propertyIds) {
            this.deviceId = deviceId;
            this.productId = productId;
            this.override = override;
            this.enabled = enabled;
            this.scheduleType = scheduleType;
            this.intervalMs = intervalMs;
            this.cron = cron;
            this.deviceIntervalMs = deviceIntervalMs;
            this.frameIntervalMs = frameIntervalMs;
            this.retryCount = retryCount;
            this.propertyIds = propertyIds;
        }

        boolean isEnabled() {
            return enabled;
        }
    }

    private static final class PlanBuilder {
        final String id;
        final DevicePlan sample;
        final List<String> deviceIds = new ArrayList<>();

        private PlanBuilder(String id, DevicePlan sample) {
            this.id = id;
            this.sample = sample;
        }

        ModbusPollPlan build() {
            return new ModbusPollPlan(
                    id,
                    sample.scheduleType,
                    sample.intervalMs,
                    sample.cron,
                    sample.deviceIntervalMs,
                    sample.frameIntervalMs,
                    sample.retryCount,
                    sample.propertyIds,
                    deviceIds);
        }
    }
}
