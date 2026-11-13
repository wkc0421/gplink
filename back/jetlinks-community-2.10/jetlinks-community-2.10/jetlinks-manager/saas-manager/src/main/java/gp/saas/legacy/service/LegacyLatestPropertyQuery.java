package gp.saas.legacy.service;

import gp.saas.legacy.service.LegacyDeviceLatestValueService.LatestValue;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.community.device.entity.DeviceProperty;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.core.metadata.DeviceMetadata;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LegacyLatestPropertyQuery {

    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(30);

    private final DeviceDataService deviceData;
    private final LegacyDeviceLatestValueService latestValues;

    public LegacyLatestPropertyQuery(DeviceDataService deviceData, LegacyDeviceLatestValueService latestValues) {
        this.deviceData = deviceData;
        this.latestValues = latestValues;
    }

    public Flux<DeviceProperty> query(String deviceId,
                                      DeviceMetadata metadata,
                                      List<String> propertyIds,
                                      boolean readAll) {
        if (metadata == null || propertyIds.isEmpty()) {
            return Flux.empty();
        }
        Mono<Map<String, LatestValue>> cached = readAll
            ? latestValues.readAll(deviceId)
            : latestValues.readProperties(deviceId, propertyIds);
        return cached.flatMapMany(values -> {
            String[] missing = propertyIds.stream()
                .filter(id -> !valid(values.get(id)))
                .distinct()
                .toArray(String[]::new);
            Mono<Map<String, DeviceProperty>> fallback = missing.length == 0
                ? Mono.just(Map.of())
                : deviceData.queryEachOneProperties(deviceId, QueryParamEntity.of(), missing)
                    .timeout(QUERY_TIMEOUT)
                    .collect(LinkedHashMap::new, (found, property) -> found.putIfAbsent(property.getProperty(), property));

            return fallback.flatMapMany(found -> Flux.fromIterable(propertyIds)
                .mapNotNull(id -> {
                    LatestValue latest = values.get(id);
                    DeviceProperty property = valid(latest)
                        ? cachedProperty(deviceId, id, latest)
                        : found.get(id);
                    return formatProperty(property, metadata);
                }));
        });
    }

    public static DeviceProperty formatProperty(DeviceProperty property, DeviceMetadata metadata) {
        if (metadata == null || property == null) {
            return property;
        }
        return property.withProperty(metadata.getPropertyOrNull(property.getProperty()));
    }

    private static boolean valid(LatestValue value) {
        return value != null && value.getTimestamp() > 0;
    }

    private static DeviceProperty cachedProperty(String deviceId, String id, LatestValue latest) {
        DeviceProperty property = new DeviceProperty();
        property.setDeviceId(deviceId);
        property.setProperty(id);
        property.setValue(latest.getValue());
        property.setTimestamp(latest.getTimestamp());
        property.setCreateTime(latest.getTimestamp());
        return property;
    }
}
