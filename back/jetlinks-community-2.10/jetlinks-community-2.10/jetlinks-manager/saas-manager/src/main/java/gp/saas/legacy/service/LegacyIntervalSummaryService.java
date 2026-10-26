package gp.saas.legacy.service;

import gp.saas.legacy.dto.DeviceIntervalDataEntity;
import gp.saas.legacy.dto.DeviceIntervalSummaryDataEntity;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.community.device.entity.DeviceProperty;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.community.timescaledb.thing.counter.CounterSnapshotService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Boundary policy of G:/gplink's two-point endpoint; history uses its own policy. */
public class LegacyIntervalSummaryService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final DeviceDataService raw;
    private final CounterSnapshotService counters;
    private final LegacyDeviceLatestValueService latest;

    public LegacyIntervalSummaryService(DeviceDataService raw, CounterSnapshotService counters,
                                        LegacyDeviceLatestValueService latest) {
        this.raw = raw;
        this.counters = counters;
        this.latest = latest;
    }

    public Mono<DeviceIntervalDataEntity> raw(String device, String property, long start, long end) {
        // Match the reference ES fallback: start <= boundary, first strictly inside
        // the range, end strictly before boundary. No end-after-range substitution.
        return Mono.zip(point(device, property, "lte", start, null, false),
                        point(device, property, "gt", start, end, true),
                        point(device, property, "lt", end, null, false))
            .map(points -> {
                Object first = points.getT2().map(DeviceProperty::getValue).orElse(null);
                return row(device, property, start, end,
                    points.getT1().isPresent() ? points.getT1().get().getValue() : first,
                    points.getT3().isPresent() ? points.getT3().get().getValue() : first);
            });
    }

    private Mono<Optional<DeviceProperty>> point(String device, String property, String operator,
                                         long boundary, Long upper, boolean ascending) {
        QueryParamEntity query = QueryParamEntity.of();
        query.and("timestamp", operator, boundary);
        if (upper != null) query.and("timestamp", "lt", upper);
        query.setOrderBy("timestamp " + (ascending ? "asc" : "desc"));
        query.doPaging(0, 1);
        return raw.queryEachOneProperties(device, query, property)
            .next().map(Optional::of)
            .defaultIfEmpty(Optional.empty());
    }

    public Flux<DeviceIntervalDataEntity> configured(String product, String property, List<String> devices,
                                                     long requestedStart, long effectiveEnd, boolean futureEnd,
                                                     boolean snap, long toleranceSeconds) {
        long tolerance = Math.min(1800L, Math.max(0L, toleranceSeconds)) * 1000L;
        long start = snap ? align(requestedStart, tolerance) : requestedStart;
        long end = snap && !futureEnd ? align(effectiveEnd, tolerance) : effectiveEnd;
        if (end <= start && !futureEnd) {
            return Flux.fromIterable(devices).map(device -> row(device, property, start, end, null, null));
        }
        boolean daily = midnight(start) && (futureEnd || midnight(end));
        Mono<Map<String, String>> starts = counters.summaryBoundaryValues(
            product, property, devices, start, false, daily);
        if (!futureEnd) {
            return Mono.zip(starts, counters.summaryBoundaryValues(
                    product, property, devices, end, true, daily))
                .flatMapMany(values -> Flux.fromIterable(devices).flatMapSequential(device -> {
                    String first = values.getT1().get(device), last = values.getT2().get(device);
                    return first == null || last == null
                        ? raw(device, property, start, end)
                        : Mono.just(row(device, property, start, end, number(first), number(last)));
                }, 4));
        }
        // A live value must be within the effective query range. Redis failure is
        // recoverable; storage errors propagate rather than silently mixing sources.
        Mono<Map<String, Object>> live = Flux.fromIterable(devices)
            .flatMapSequential(device -> live(device, property, start, end)
                .map(value -> Map.entry(device, value)), 4)
            .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new);
        return Mono.zip(starts, live).flatMapMany(values -> {
            List<String> missing = devices.stream().filter(id -> !values.getT2().containsKey(id)).toList();
            long hour = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZONE)
                .truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli();
            Mono<Map<String, String>> ends = missing.isEmpty() ? Mono.just(Collections.emptyMap())
                : counters.summaryBoundaryValues(product, property, missing, hour, true, false);
            return ends.flatMapMany(fallback -> Flux.fromIterable(devices).flatMapSequential(device -> {
                String first = values.getT1().get(device);
                Object last = values.getT2().get(device);
                if (last == null) last = number(fallback.get(device));
                return first == null || last == null
                    ? raw(device, property, start, end)
                    : Mono.just(row(device, property, start, end, number(first), last));
            }, 4));
        });
    }

    private Mono<Object> live(String device, String property, long start, long end) {
        if (latest == null) return Mono.empty();
        return latest.readProperty(device, property).flatMap(value -> {
            if (value.getTimestamp() <= start || value.getTimestamp() > end
                || value.getTimestamp() > System.currentTimeMillis()) return Mono.empty();
            try {
                double reading = Double.parseDouble(String.valueOf(value.getValue()));
                return Double.isFinite(reading) ? Mono.just((Object) number(String.valueOf(value.getValue())))
                    : Mono.empty();
            } catch (NumberFormatException error) {
                return Mono.empty();
            }
        }).onErrorResume(error -> Mono.empty());
    }

    private static long align(long timestamp, long tolerance) {
        long floor = Instant.ofEpochMilli(timestamp).atZone(ZONE).truncatedTo(ChronoUnit.HOURS)
            .toInstant().toEpochMilli();
        long ceil = floor + 3_600_000L;
        long nearest = timestamp - floor <= ceil - timestamp ? floor : ceil;
        return Math.abs(nearest - timestamp) <= tolerance ? nearest : timestamp;
    }

    private static boolean midnight(long timestamp) {
        return Instant.ofEpochMilli(timestamp).atZone(ZONE).toLocalTime().equals(java.time.LocalTime.MIDNIGHT);
    }

    private static BigDecimal number(String value) {
        return value == null ? null : new BigDecimal(value.trim());
    }

    public static DeviceIntervalDataEntity row(String device, String property, long start, long end,
                                               Object first, Object last) {
        return new DeviceIntervalSummaryDataEntity(device, property, start, end, first, last);
    }
}
