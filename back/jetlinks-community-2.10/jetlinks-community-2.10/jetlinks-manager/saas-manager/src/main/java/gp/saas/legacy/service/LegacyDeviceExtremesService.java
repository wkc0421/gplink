package gp.saas.legacy.service;

import gp.saas.legacy.dto.DeviceExtremesData;
import gp.saas.legacy.dto.DeviceExtremesRequest;
import lombok.AllArgsConstructor;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.community.things.data.PropertyExtremes;
import org.jetlinks.community.things.data.operations.PropertyExtremesQuery;
import org.jetlinks.core.metadata.PropertyMetadata;
import org.jetlinks.core.metadata.types.NumberType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Called only after the controller has authorized the complete device roster. */
@AllArgsConstructor
public class LegacyDeviceExtremesService {
    private final DeviceDataService dataService;
    private final LocalDeviceInstanceService deviceService;

    public Flux<DeviceExtremesData> query(DeviceExtremesRequest request) {
        return Flux.defer(() -> Flux.fromIterable(request.getDevices())
            .flatMapSequential(id -> deviceService.getMetadata(id)
                .switchIfEmpty(Mono.error(new IllegalStateException("Device metadata unavailable: " + id)))
                .map(metadata -> Map.entry(id, metadata)), PropertyExtremesQuery.MAX_CONCURRENCY)
            .collectList()
            .flatMapMany(metadata -> {
                Map<PropertyExtremes.Target, DeviceExtremesData> results = new LinkedHashMap<>();
                List<PropertyExtremes.Target> valid = new ArrayList<>();
                metadata.forEach(entry -> request.getProperties().forEach(property -> {
                    PropertyExtremes.Target target = new PropertyExtremes.Target(entry.getKey(), property);
                    PropertyMetadata declared = entry.getValue().getPropertyOrNull(property);
                    if (declared == null) {
                        results.put(target, DeviceExtremesData.empty(target, DeviceExtremesData.Status.UNKNOWN_PROPERTY));
                    } else if (!(declared.getValueType() instanceof NumberType)) {
                        results.put(target, DeviceExtremesData.empty(target, DeviceExtremesData.Status.NON_NUMERIC));
                    } else {
                        valid.add(target);
                        results.put(target, null);
                    }
                }));
                Flux<PropertyExtremes> query = valid.isEmpty() ? Flux.empty()
                    : dataService.queryPropertyExtremes(valid, request.getStartTime(), request.getEndTime());
                // Buffer only bounded result rows, never raw samples. Do not emit a partial successful response.
                return query.doOnNext(value -> {
                    if (!results.containsKey(value.target())) {
                        throw new IllegalStateException("Unexpected extremes target: " + value.target());
                    }
                    results.put(value.target(), DeviceExtremesData.of(value));
                }).thenMany(Flux.defer(() -> {
                    if (results.containsValue(null)) {
                        return Flux.error(new IllegalStateException("Incomplete extremes storage response"));
                    }
                    return Flux.fromIterable(results.values());
                }));
            }));
    }
}
