package gp.saas.legacy.web;

import com.alibaba.fastjson.JSON;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gp.saas.legacy.dto.DeiceDataRequestEntity;
import gp.saas.legacy.dto.DeviceAggregationDataEntity;
import gp.saas.legacy.dto.DeviceDataEntity;
import gp.saas.legacy.dto.DeviceIntervalDataEntity;
import gp.saas.legacy.service.LegacyDeviceLatestValueService;
import gp.saas.legacy.service.LegacyDeviceLatestValueService.LatestValue;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.core.param.TermType;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.community.Interval;
import org.jetlinks.community.device.entity.DeviceEvent;
import org.jetlinks.community.device.entity.DeviceProperty;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.community.timeseries.query.Aggregation;
import org.jetlinks.community.timeseries.query.AggregationData;
import org.jetlinks.core.metadata.DeviceMetadata;
import org.jetlinks.core.metadata.PropertyMetadata;
import org.jetlinks.reactor.ql.utils.CastUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1/device-data")
@Authorize(ignore = true)
public class LegacyDeviceDataController {

    private static final long THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1000;
    private static final int MAX_DEVICE_QUERY_SIZE = 500;
    private static final int HISTORY_AGG_POINT_CAP = 1000;
    private static final int INTERVAL_BUCKET_CAP = 1000;
    private static final long HOT_WINDOW_MS = 10L * 60 * 1000;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final String HISTORY_AGG_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private static final Duration SHORT_CACHE_TTL = Duration.ofMinutes(1);
    private static final Duration LONG_CACHE_TTL = Duration.ofMinutes(5);
    private static final String AGG_TIME_KEY = "time";
    private static final String AGG_TS_KEY = "_ts";

    private final DeviceDataService deviceDataService;
    private final LocalDeviceInstanceService deviceService;
    private final LegacyDeviceLatestValueService latestValueService;
    private final Cache<String, List<DeviceAggregationDataEntity>> shortAggCache = Caffeine
        .newBuilder()
        .expireAfterWrite(SHORT_CACHE_TTL)
        .maximumSize(4096)
        .build();
    private final Cache<String, List<DeviceAggregationDataEntity>> longAggCache = Caffeine
        .newBuilder()
        .expireAfterWrite(LONG_CACHE_TTL)
        .maximumSize(4096)
        .build();
    private final Cache<String, DeviceIntervalDataEntity> shortIntervalCache = Caffeine
        .newBuilder()
        .expireAfterWrite(SHORT_CACHE_TTL)
        .maximumSize(4096)
        .build();
    private final Cache<String, DeviceIntervalDataEntity> longIntervalCache = Caffeine
        .newBuilder()
        .expireAfterWrite(LONG_CACHE_TTL)
        .maximumSize(4096)
        .build();
    private final Cache<String, List<DeviceIntervalDataEntity>> shortIntervalHistoryCache = Caffeine
        .newBuilder()
        .expireAfterWrite(SHORT_CACHE_TTL)
        .maximumWeight(100_000)
        .weigher((String key, List<DeviceIntervalDataEntity> value) -> Math.max(1, value.size()))
        .build();
    private final Cache<String, List<DeviceIntervalDataEntity>> longIntervalHistoryCache = Caffeine
        .newBuilder()
        .expireAfterWrite(LONG_CACHE_TTL)
        .maximumWeight(100_000)
        .weigher((String key, List<DeviceIntervalDataEntity> value) -> Math.max(1, value.size()))
        .build();
    private final ConcurrentMap<String, Mono<List<DeviceAggregationDataEntity>>> aggregationInflight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Mono<DeviceIntervalDataEntity>> intervalInflight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Mono<List<DeviceIntervalDataEntity>>> intervalHistoryInflight = new ConcurrentHashMap<>();

    public LegacyDeviceDataController(DeviceDataService deviceDataService,
                                      LocalDeviceInstanceService deviceService,
                                      LegacyDeviceLatestValueService latestValueService) {
        this.deviceDataService = deviceDataService;
        this.deviceService = deviceService;
        this.latestValueService = latestValueService;
    }

    @GetMapping({"/{deviceId}/properties/es", "/{deviceId}/properties/pg"})
    public Flux<DeviceProperty> getLatestPropertiesFromPg(@PathVariable String deviceId) {
        return deviceDataService.queryEachOneProperties(deviceId, QueryParamEntity.of());
    }

    @GetMapping({"/{deviceId}/{property}/es", "/{deviceId}/{property}/pg"})
    public Flux<DeviceProperty> getLatestPropertyFromPg(@PathVariable String deviceId,
                                                        @PathVariable String property) {
        return deviceDataService.queryEachOneProperties(deviceId, QueryParamEntity.of(), property);
    }

    @PostMapping({"/devices/properties/es", "/devices/properties/pg"})
    public Flux<DeviceDataEntity> getLatestPropertiesFromPg(@RequestBody DeiceDataRequestEntity request) {
        return Flux
            .fromIterable(nullToEmpty(request.getDevices()))
            .flatMap(deviceId -> deviceDataService
                .queryEachOneProperties(deviceId, QueryParamEntity.of(), properties(request))
                .collectList()
                .map(data -> new DeviceDataEntity(deviceId, data)), 10);
    }

    @PostMapping("/devices/properties/redis")
    public Flux<DeviceDataEntity> getLatestPropertiesFromRedis(@RequestBody DeiceDataRequestEntity request) {
        return Flux
            .fromIterable(nullToEmpty(request.getDevices()))
            .flatMap(deviceId -> getRedisProperties(deviceId, nullToEmpty(request.getProperties()))
                .collectList()
                .map(data -> new DeviceDataEntity(deviceId, data)), 10);
    }

    @GetMapping("/{deviceId}/properties/redis")
    public Flux<DeviceProperty> getRedisProperties(@PathVariable String deviceId) {
        return getRedisProperties(deviceId, Collections.emptyList());
    }

    @GetMapping("/{deviceId}/{property}/redis")
    public Mono<DeviceProperty> getRedisProperty(@PathVariable String deviceId,
                                                 @PathVariable String property) {
        return deviceService
            .getMetadata(deviceId)
            .flatMap(metadata -> latestValueService
                .readProperty(deviceId, property)
                .map(value -> toDeviceProperty(deviceId, property, value, metadata)));
    }

    @PostMapping({"/{deviceId}/{property}/history/test", "/{deviceId}/{property}/history/pg/test"})
    public Mono<PagerResult<DeviceProperty>> queryPropertyPageTest(@PathVariable String deviceId,
                                                                   @PathVariable String property,
                                                                   @RequestBody QueryParamEntity query) {
        return deviceDataService.queryPropertyPage(deviceId, property, safeQuery(query));
    }

    @PostMapping({"/{deviceId}/{property}/history/no-paging/raw", "/{deviceId}/{property}/history/no-paging/pg/raw"})
    public Flux<DeviceProperty> queryPropertyNoPagingTest(@PathVariable String deviceId,
                                                          @PathVariable String property,
                                                          @RequestBody QueryParamEntity query) {
        QueryParamEntity param = safeQuery(query);
        param.noPaging();
        return deviceDataService.queryProperty(deviceId, param, property);
    }

    @PostMapping({"/{deviceId}/{property}/history", "/{deviceId}/{property}/history/pg"})
    public Mono<PagerResult<DeviceProperty>> queryPropertyPage(@PathVariable String deviceId,
                                                               @PathVariable String property,
                                                               @RequestBody QueryParamEntity query) {
        return deviceService
            .getMetadata(deviceId)
            .flatMap(metadata -> deviceDataService
                .queryPropertyPage(deviceId, property, safeQuery(query))
                .map(result -> {
                    result.getData().forEach(item -> formatProperty(item, metadata));
                    return result;
                }));
    }

    @PostMapping({"/{deviceId}/{property}/history/no-paging", "/{deviceId}/{property}/history/no-paging/pg"})
    public Flux<DeviceProperty> queryPropertyNoPaging(@PathVariable String deviceId,
                                                      @PathVariable String property,
                                                      @RequestBody Map<String, Object> body) {
        QueryParamEntity query = parseQuery(body);
        query.noPaging();
        return deviceService
            .getMetadata(deviceId)
            .flatMapMany(metadata -> queryHistoryNoPaging(deviceId, property, query, metadata));
    }

    @PostMapping({"/{deviceId}/event/{event}/history", "/{deviceId}/event/{event}/history/pg"})
    public Flux<DeviceEvent> queryEventHistory(@PathVariable String deviceId,
                                               @PathVariable String event,
                                               @RequestBody QueryParamEntity query) {
        return deviceDataService
            .queryEvent(deviceId, event, safeQuery(query), false)
            .onErrorResume(UnsupportedOperationException.class, ignore -> Flux.empty());
    }

    @PostMapping("/devices/agg/_query")
    public Flux<Map<String, List<DeviceAggregationDataEntity>>> queryAggregation(@RequestBody DeiceDataRequestEntity request) {
        validateAggregationRequest(request);
        validateDeviceQuerySize(request);
        DeviceDataService.AggregationRequest query = request.getAggregationRequest().getQuery();
        DeviceDataService.DevicePropertyAggregation[] columns = request
            .getAggregationRequest()
            .getColumns()
            .toArray(new DeviceDataService.DevicePropertyAggregation[0]);
        String valueKey = columns[0].getAlias();
        Cache<String, List<DeviceAggregationDataEntity>> cache = aggregationCache(query);

        return Flux
            .fromIterable(nullToEmpty(request.getDevices()))
            .flatMap(deviceId -> {
                String cacheKey = aggregationCacheKey(request, deviceId);
                Mono<List<DeviceAggregationDataEntity>> data = cachedAggregation(cache, cacheKey, () -> deviceDataService
                    .aggregationPropertiesByDevice(deviceId, query, columns)
                    .map(AggregationData::values)
                    .map(map -> toAggregationData(valueKey, map))
                    .collectList());

                return data.map(value -> {
                    Map<String, List<DeviceAggregationDataEntity>> result = new HashMap<>();
                    result.put(deviceId, value);
                    return result;
                });
            }, 5);
    }

    @PostMapping("/devices/interval")
    public Flux<DeviceIntervalDataEntity> queryDeviceInterval(@RequestParam Long startTime,
                                                              @RequestParam Long endTime,
                                                              @RequestBody DeiceDataRequestEntity request) {
        validateIntervalRequest(request, startTime, endTime);
        validateDeviceQuerySize(request);
        String property = request.getProperties().get(0);
        return Flux
            .fromIterable(nullToEmpty(request.getDevices()))
            .flatMap(deviceId -> queryIntervalForDeviceCached(deviceId, property, startTime, endTime), 5);
    }

    @PostMapping("/devices/interval/{type}/history")
    public Flux<List<DeviceIntervalDataEntity>> queryDeviceIntervalHistory(@PathVariable String type,
                                                                          @RequestParam Long startTime,
                                                                          @RequestParam Long endTime,
                                                                          @RequestBody DeiceDataRequestEntity request) {
        validateIntervalRequest(request, startTime, endTime);
        validateDeviceQuerySize(request);
        List<Long> boundaries = intervalBoundaries(type, startTime, endTime);
        if (boundaries.size() - 1 > INTERVAL_BUCKET_CAP) {
            throw new IllegalArgumentException("Interval bucket count exceeds " + INTERVAL_BUCKET_CAP);
        }

        String property = request.getProperties().get(0);
        return Flux
            .fromIterable(nullToEmpty(request.getDevices()))
            .flatMap(deviceId -> queryIntervalHistoryForDeviceCached(deviceId, property, type, startTime, endTime, boundaries), 5);
    }

    private Flux<DeviceProperty> getRedisProperties(String deviceId, List<String> properties) {
        return deviceService
            .getMetadata(deviceId)
            .flatMapMany(metadata -> latestValueService
                .readAll(deviceId)
                .flatMapMany(values -> {
                    List<String> ids = properties.isEmpty() ? orderedPropertyIds(metadata) : properties;
                    return Flux
                        .fromIterable(ids)
                        .flatMap(property -> {
                            LatestValue value = values.get(property);
                            return value == null
                                ? Mono.empty()
                                : Mono.just(toDeviceProperty(deviceId, property, value, metadata));
                        });
                }));
    }

    private DeviceProperty toDeviceProperty(String deviceId,
                                            String property,
                                            LatestValue latestValue,
                                            DeviceMetadata metadata) {
        DeviceProperty deviceProperty = new DeviceProperty();
        long timestamp = latestValue.getTimestamp() > 0 ? latestValue.getTimestamp() : System.currentTimeMillis();
        deviceProperty.setDeviceId(deviceId);
        deviceProperty.setProperty(property);
        deviceProperty.setValue(latestValue.getValue());
        deviceProperty.setTimestamp(timestamp);
        deviceProperty.setCreateTime(timestamp);
        return formatProperty(deviceProperty, metadata);
    }

    private DeviceProperty formatProperty(DeviceProperty property, DeviceMetadata metadata) {
        if (metadata == null || property == null) {
            return property;
        }
        PropertyMetadata propertyMetadata = metadata.getPropertyOrNull(property.getProperty());
        return property.withProperty(propertyMetadata);
    }

    private Flux<DeviceProperty> queryHistoryNoPaging(String deviceId,
                                                      String property,
                                                      QueryParamEntity query,
                                                      DeviceMetadata metadata) {
        Optional<long[]> range = extractTimeRangeMillis(query);
        if (range.isEmpty() || range.get()[1] - range.get()[0] <= THREE_DAYS_MS) {
            return deviceDataService
                .queryProperty(deviceId, query, property)
                .map(item -> formatProperty(item, metadata));
        }
        return queryDownsampledHistory(deviceId, property, range.get()[0], range.get()[1], metadata)
            .switchIfEmpty(deviceDataService
                .queryProperty(deviceId, query, property)
                .map(item -> formatProperty(item, metadata)));
    }

    private Flux<DeviceProperty> queryDownsampledHistory(String deviceId,
                                                         String property,
                                                         long start,
                                                         long end,
                                                         DeviceMetadata metadata) {
        Interval interval = chooseHistoryInterval(end - start);
        DeviceDataService.AggregationRequest request = new DeviceDataService.AggregationRequest();
        request.setFrom(new Date(start));
        request.setTo(new Date(end));
        request.setInterval(interval);
        request.setFormat(HISTORY_AGG_TIME_FORMAT);
        request.setLimit(computeBucketLimit(start, end, interval));
        request.setFilter(QueryParamEntity.of());

        DeviceDataService.DevicePropertyAggregation aggregation =
            new DeviceDataService.DevicePropertyAggregation(property, property, Aggregation.FIRST);

        return deviceDataService
            .aggregationPropertiesByDevice(deviceId, request, aggregation)
            .map(data -> toDownsampledDeviceProperty(deviceId, property, data, metadata))
            .filter(Objects::nonNull)
            .sort(Comparator.comparingLong(DeviceProperty::getTimestamp).reversed());
    }

    private DeviceProperty toDownsampledDeviceProperty(String deviceId,
                                                       String property,
                                                       AggregationData data,
                                                       DeviceMetadata metadata) {
        Object value = data.asMap().containsKey("value") ? data.asMap().get("value") : data.asMap().get(property);
        if (value == null) {
            return null;
        }
        DeviceProperty deviceProperty = new DeviceProperty();
        long timestamp = parseAggregationTime(data.asMap().get("time"));
        deviceProperty.setDeviceId(deviceId);
        deviceProperty.setProperty(property);
        deviceProperty.setValue(value);
        deviceProperty.setTimestamp(timestamp);
        deviceProperty.setCreateTime(timestamp);
        return formatProperty(deviceProperty, metadata);
    }

    private DeviceAggregationDataEntity toAggregationData(String valueKey, Map<String, Object> map) {
        DeviceAggregationDataEntity entity = new DeviceAggregationDataEntity();
        Object time = map.containsKey(AGG_TIME_KEY) ? map.get(AGG_TIME_KEY) : map.get(AGG_TS_KEY);
        Object value = map.containsKey(valueKey) ? map.get(valueKey) : map.get("value");
        entity.setTime(time == null ? null : String.valueOf(time));
        entity.setValue(value);
        return entity;
    }

    private void validateAggregationRequest(DeiceDataRequestEntity request) {
        if (request == null || request.getProperties() == null || request.getProperties().size() != 1) {
            throw new IllegalArgumentException("Only one property is supported");
        }
        if (request.getAggregationRequest() == null || request.getAggregationRequest().getQuery() == null) {
            throw new IllegalArgumentException("aggregationRequest.query is required");
        }
        if (request.getAggregationRequest().getColumns() == null || request.getAggregationRequest().getColumns().isEmpty()) {
            throw new IllegalArgumentException("aggregationRequest.columns is required");
        }
        if (request.getAggregationRequest().getColumns().size() != 1) {
            throw new IllegalArgumentException("Only one aggregation column is supported");
        }
    }

    private Cache<String, List<DeviceAggregationDataEntity>> aggregationCache(DeviceDataService.AggregationRequest query) {
        Interval interval = query.getInterval();
        if (isHotWindow(query.getTo() == null ? null : query.getTo().getTime())) {
            return shortAggCache;
        }
        return interval != null && interval.toMillis() >= DAY_MS
            ? longAggCache
            : shortAggCache;
    }

    private Mono<List<DeviceAggregationDataEntity>> cachedAggregation(Cache<String, List<DeviceAggregationDataEntity>> cache,
                                                                      String cacheKey,
                                                                      Supplier<Mono<List<DeviceAggregationDataEntity>>> loader) {
        List<DeviceAggregationDataEntity> cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            return Mono.just(cached);
        }
        return aggregationInflight.computeIfAbsent(cacheKey, key -> loader
            .get()
            .doOnNext(value -> cache.put(key, value))
            .doFinally(signal -> aggregationInflight.remove(key))
            .cache());
    }

    private String aggregationCacheKey(DeiceDataRequestEntity request, String deviceId) {
        DeviceDataService.AggregationRequest query = request.getAggregationRequest().getQuery();
        Map<String, Object> key = new LinkedHashMap<>();
        List<String> properties = new ArrayList<>(nullToEmpty(request.getProperties()));
        Collections.sort(properties);
        key.put("device", deviceId);
        key.put("properties", properties);
        key.put("columns", request.getAggregationRequest().getColumns());
        key.put("from", query.getFrom() == null ? null : query.getFrom().getTime());
        key.put("to", query.getTo() == null ? null : query.getTo().getTime());
        key.put("interval", query.getInterval() == null ? null : query.getInterval().toString());
        key.put("limit", query.getLimit());
        key.put("filter", query.getFilter());
        return JSON.toJSONString(key);
    }

    private void validateIntervalRequest(DeiceDataRequestEntity request, Long startTime, Long endTime) {
        if (request == null || request.getProperties() == null || request.getProperties().size() != 1) {
            throw new IllegalArgumentException("Only one property is supported");
        }
        if (startTime == null || endTime == null || endTime <= startTime) {
            throw new IllegalArgumentException("startTime and endTime are required, and endTime must be greater than startTime");
        }
    }

    private void validateDeviceQuerySize(DeiceDataRequestEntity request) {
        int size = nullToEmpty(request == null ? null : request.getDevices()).size();
        if (size > MAX_DEVICE_QUERY_SIZE) {
            throw new IllegalArgumentException("Too many devices, max supported size is " + MAX_DEVICE_QUERY_SIZE);
        }
    }

    private Mono<Object> queryLastValueBefore(String deviceId, String property, Long boundary) {
        QueryParamEntity query = QueryParamEntity.of();
        query.and("timestamp", TermType.lte, boundary);
        query.setOrderBy("timestamp desc");
        query.doPaging(0, 1);
        return deviceDataService
            .queryProperty(deviceId, query, property)
            .next()
            .map(DeviceProperty::getValue);
    }

    private Mono<DeviceIntervalDataEntity> queryIntervalForDevice(String deviceId,
                                                                  String property,
                                                                  Long startTime,
                                                                  Long endTime) {
        String type = intervalTypeForSummary(startTime, endTime);
        List<Long> boundaries = intervalBoundaries(type, startTime, endTime);
        if (boundaries.size() - 1 > INTERVAL_BUCKET_CAP) {
            boundaries = Arrays.asList(startTime, endTime);
        }
        return queryIntervalHistoryForDevice(deviceId, property, type, startTime, endTime, boundaries)
            .map(data -> toIntervalSummary(deviceId, property, startTime, endTime, data));
    }

    private Mono<DeviceIntervalDataEntity> queryIntervalForDeviceCached(String deviceId,
                                                                        String property,
                                                                        Long startTime,
                                                                        Long endTime) {
        Cache<String, DeviceIntervalDataEntity> cache = intervalSummaryCache(startTime, endTime);
        String cacheKey = intervalCacheKey(deviceId, property, startTime, endTime, "summary");
        DeviceIntervalDataEntity cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            return Mono.just(cached);
        }
        return intervalInflight.computeIfAbsent(cacheKey, key -> queryIntervalForDevice(deviceId, property, startTime, endTime)
            .doOnNext(value -> cache.put(key, value))
            .doFinally(signal -> intervalInflight.remove(key))
            .cache());
    }

    private Mono<Object> queryLastValueInRange(String deviceId, String property, long startTime, long endTime) {
        DeviceDataService.AggregationRequest request = new DeviceDataService.AggregationRequest();
        request.setFrom(new Date(startTime));
        request.setTo(new Date(endTime));
        request.setInterval(Interval.of("100y"));
        request.setFormat(HISTORY_AGG_TIME_FORMAT);
        request.setLimit(1);
        request.setFilter(QueryParamEntity.of());

        DeviceDataService.DevicePropertyAggregation aggregation =
            new DeviceDataService.DevicePropertyAggregation(property, property, Aggregation.LAST);

        return deviceDataService
            .aggregationPropertiesByDevice(deviceId, request, aggregation)
            .next()
            .map(data -> aggregationValue(data, property))
            .onErrorResume(error -> queryLastValueBefore(deviceId, property, endTime));
    }

    private Mono<List<DeviceIntervalDataEntity>> queryIntervalHistoryForDevice(String deviceId,
                                                                              String property,
                                                                              String type,
                                                                              long startTime,
                                                                              long endTime,
                                                                              List<Long> boundaries) {
        return queryLastValueBefore(deviceId, property, startTime)
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .flatMap(startValue -> queryLastValuesByBucket(deviceId, property, type, startTime, endTime, boundaries)
                .map(bucketValues -> toIntervalHistory(deviceId, property, boundaries, startValue.orElse(null), bucketValues)));
    }

    private Mono<List<DeviceIntervalDataEntity>> queryIntervalHistoryForDeviceCached(String deviceId,
                                                                                    String property,
                                                                                    String type,
                                                                                    long startTime,
                                                                                    long endTime,
                                                                                    List<Long> boundaries) {
        Cache<String, List<DeviceIntervalDataEntity>> cache = intervalHistoryCache(type, endTime);
        String cacheKey = intervalCacheKey(deviceId, property, startTime, endTime, type);
        List<DeviceIntervalDataEntity> cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            return Mono.just(cached);
        }
        return intervalHistoryInflight.computeIfAbsent(cacheKey, key -> queryIntervalHistoryForDevice(deviceId, property, type, startTime, endTime, boundaries)
            .doOnNext(value -> cache.put(key, value))
            .doFinally(signal -> intervalHistoryInflight.remove(key))
            .cache());
    }

    private Cache<String, DeviceIntervalDataEntity> intervalSummaryCache(long startTime, long endTime) {
        if (isHotWindow(endTime)) {
            return shortIntervalCache;
        }
        return endTime - startTime >= DAY_MS
            ? longIntervalCache
            : shortIntervalCache;
    }

    private Cache<String, List<DeviceIntervalDataEntity>> intervalHistoryCache(String type, long endTime) {
        if (isHotWindow(endTime)) {
            return shortIntervalHistoryCache;
        }
        return "month".equals(type) || "year".equals(type)
            ? longIntervalHistoryCache
            : shortIntervalHistoryCache;
    }

    private boolean isHotWindow(Long endTime) {
        return endTime == null || endTime >= System.currentTimeMillis() - HOT_WINDOW_MS;
    }

    private String intervalCacheKey(String deviceId, String property, long startTime, long endTime, String type) {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("device", deviceId);
        key.put("property", property);
        key.put("startTime", startTime);
        key.put("endTime", endTime);
        key.put("type", type);
        return JSON.toJSONString(key);
    }

    private Mono<Map<Long, Object>> queryLastValuesByBucket(String deviceId,
                                                            String property,
                                                            String type,
                                                            long startTime,
                                                            long endTime,
                                                            List<Long> boundaries) {
        DeviceDataService.AggregationRequest request = new DeviceDataService.AggregationRequest();
        request.setFrom(new Date(startTime));
        request.setTo(new Date(endTime));
        request.setInterval(intervalForType(type));
        request.setFormat(HISTORY_AGG_TIME_FORMAT);
        request.setLimit(boundaries.size());
        request.setFilter(QueryParamEntity.of());

        DeviceDataService.DevicePropertyAggregation aggregation =
            new DeviceDataService.DevicePropertyAggregation(property, property, Aggregation.LAST);

        return deviceDataService
            .aggregationPropertiesByDevice(deviceId, request, aggregation)
            .collectMap(data -> bucketEndFor(type, parseAggregationTime(aggregationTime(data)), boundaries),
                        data -> aggregationValue(data, property))
            .map(values -> {
                values.remove(null);
                return values;
            })
            .onErrorResume(error -> queryBoundaryValuesFallback(deviceId, property, boundaries));
    }

    private Mono<Map<Long, Object>> queryBoundaryValuesFallback(String deviceId, String property, List<Long> boundaries) {
        return Flux
            .fromIterable(boundaries)
            .distinct()
            .skip(1)
            .flatMap(boundary -> queryLastValueBefore(deviceId, property, boundary)
                .map(value -> {
                    Map<Long, Object> result = new HashMap<>();
                    result.put(boundary, value);
                    return result;
                })
                .defaultIfEmpty(Collections.singletonMap(boundary, null)), 4)
            .collect(HashMap<Long, Object>::new, Map::putAll);
    }

    private List<DeviceIntervalDataEntity> toIntervalHistory(String deviceId,
                                                             String property,
                                                             List<Long> boundaries,
                                                             Object startValue,
                                                             Map<Long, Object> bucketValues) {
        Object currentValue = startValue;
        List<DeviceIntervalDataEntity> data = new ArrayList<>();
        for (int i = 0; i < boundaries.size() - 1; i++) {
            Long bucketStart = boundaries.get(i);
            Long bucketEnd = boundaries.get(i + 1);
            Object intervalStartValue = currentValue;
            if (bucketValues.containsKey(bucketEnd)) {
                currentValue = bucketValues.get(bucketEnd);
            }
            data.add(new DeviceIntervalDataEntity(
                deviceId,
                property,
                bucketStart,
                bucketEnd,
                intervalStartValue,
                currentValue
            ));
        }
        return data;
    }

    private DeviceIntervalDataEntity toIntervalSummary(String deviceId,
                                                       String property,
                                                       Long startTime,
                                                       Long endTime,
                                                       List<DeviceIntervalDataEntity> history) {
        if (history.isEmpty()) {
            return new DeviceIntervalDataEntity(deviceId, property, startTime, endTime, null, null);
        }
        DeviceIntervalDataEntity first = history.get(0);
        DeviceIntervalDataEntity last = history.get(history.size() - 1);
        return new DeviceIntervalDataEntity(
            deviceId,
            property,
            startTime,
            endTime,
            first.getStartValue(),
            last.getEndValue()
        );
    }

    private String intervalTypeForSummary(long startTime, long endTime) {
        long span = endTime - startTime;
        if (span <= DAY_MS) {
            return "day";
        }
        if (span <= 31L * DAY_MS) {
            return "month";
        }
        return "year";
    }

    private Interval intervalForType(String type) {
        if ("day".equals(type)) {
            return Interval.ofHours(1);
        }
        if ("month".equals(type)) {
            return Interval.ofDays(1);
        }
        if ("year".equals(type)) {
            return Interval.ofMonth(1);
        }
        throw new IllegalArgumentException("Unsupported interval type: " + type);
    }

    private Long bucketEndFor(String type, long bucketStart, List<Long> boundaries) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(bucketStart);
        if ("day".equals(type)) {
            calendar.add(Calendar.HOUR_OF_DAY, 1);
        } else if ("month".equals(type)) {
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        } else if ("year".equals(type)) {
            calendar.add(Calendar.MONTH, 1);
        } else {
            return null;
        }
        long naturalEnd = calendar.getTimeInMillis();
        for (Long boundary : boundaries) {
            if (boundary > bucketStart && boundary <= naturalEnd) {
                return boundary;
            }
        }
        return null;
    }

    private Object aggregationTime(AggregationData data) {
        Map<String, Object> map = data.asMap();
        return map.containsKey(AGG_TIME_KEY) ? map.get(AGG_TIME_KEY) : map.get(AGG_TS_KEY);
    }

    private Object aggregationValue(AggregationData data, String property) {
        Map<String, Object> map = data.asMap();
        return map.containsKey(property) ? map.get(property) : map.get("value");
    }

    private List<Long> intervalBoundaries(String type, long startTime, long endTime) {
        List<Long> boundaries = new ArrayList<>();
        boundaries.add(startTime);

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(startTime);
        if ("day".equals(type)) {
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            calendar.add(Calendar.HOUR_OF_DAY, 1);
            addBoundaries(boundaries, calendar, Calendar.HOUR_OF_DAY, startTime, endTime);
        } else if ("month".equals(type)) {
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            calendar.add(Calendar.DAY_OF_MONTH, 1);
            addBoundaries(boundaries, calendar, Calendar.DAY_OF_MONTH, startTime, endTime);
        } else if ("year".equals(type)) {
            calendar.set(Calendar.DAY_OF_MONTH, 1);
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            calendar.add(Calendar.MONTH, 1);
            addBoundaries(boundaries, calendar, Calendar.MONTH, startTime, endTime);
        } else {
            throw new IllegalArgumentException("Unsupported interval type: " + type);
        }

        if (!Objects.equals(boundaries.get(boundaries.size() - 1), endTime)) {
            boundaries.add(endTime);
        }
        return boundaries;
    }

    private void addBoundaries(List<Long> boundaries,
                               Calendar calendar,
                               int calendarField,
                               long startTime,
                               long endTime) {
        while (calendar.getTimeInMillis() <= startTime) {
            calendar.add(calendarField, 1);
        }
        while (calendar.getTimeInMillis() < endTime) {
            boundaries.add(calendar.getTimeInMillis());
            calendar.add(calendarField, 1);
        }
    }

    private Optional<long[]> extractTimeRangeMillis(QueryParamEntity query) {
        Long start = null;
        Long end = null;
        for (Term term : query.getTerms()) {
            if (!"timestamp".equals(term.getColumn()) && !"createTime".equals(term.getColumn())) {
                continue;
            }
            if (TermType.btw.equals(term.getTermType())) {
                List<Object> values = toList(term.getValue());
                if (values.size() > 0) {
                    start = toMillis(values.get(0));
                }
                if (values.size() > 1) {
                    end = toMillis(values.get(1));
                }
            } else if (TermType.gt.equals(term.getTermType()) || TermType.gte.equals(term.getTermType())) {
                start = toMillis(term.getValue());
            } else if (TermType.lt.equals(term.getTermType()) || TermType.lte.equals(term.getTermType())) {
                end = toMillis(term.getValue());
            }
        }
        if (start == null || end == null || end <= start) {
            return Optional.empty();
        }
        return Optional.of(new long[]{start, end});
    }

    private List<Object> toList(Object value) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        if (value instanceof Object[]) {
            return Arrays.asList((Object[]) value);
        }
        return Collections.singletonList(value);
    }

    private Long toMillis(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return CastUtils.castDate(value).getTime();
    }

    private Interval chooseHistoryInterval(long span) {
        long target = Math.max(1, span / HISTORY_AGG_POINT_CAP);
        if (target <= 5L * 60 * 1000) {
            return Interval.ofMinutes(5);
        }
        if (target <= 10L * 60 * 1000) {
            return Interval.ofMinutes(10);
        }
        if (target <= 30L * 60 * 1000) {
            return Interval.ofMinutes(30);
        }
        if (target <= 60L * 60 * 1000) {
            return Interval.ofHours(1);
        }
        if (target <= 2L * 60 * 60 * 1000) {
            return Interval.ofHours(2);
        }
        return Interval.ofDays(1);
    }

    private int computeBucketLimit(long start, long end, Interval interval) {
        long intervalMs = Math.max(1, interval.toMillis());
        long limit = ((end - start) / intervalMs) + 2;
        return (int) Math.min(HISTORY_AGG_POINT_CAP, Math.max(1, limit));
    }

    private long parseAggregationTime(Object time) {
        if (time instanceof Number) {
            return ((Number) time).longValue();
        }
        if (time instanceof Date) {
            return ((Date) time).getTime();
        }
        if (time != null) {
            try {
                return new SimpleDateFormat(HISTORY_AGG_TIME_FORMAT).parse(String.valueOf(time)).getTime();
            } catch (Exception ignore) {
            }
        }
        return System.currentTimeMillis();
    }

    private List<String> orderedPropertyIds(DeviceMetadata metadata) {
        if (metadata == null) {
            return Collections.emptyList();
        }
        List<String> properties = new ArrayList<>();
        metadata.getProperties().forEach(property -> properties.add(property.getId()));
        return properties;
    }

    private QueryParamEntity parseQuery(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return QueryParamEntity.of();
        }
        Object query = body.get("query");
        if (query instanceof QueryParamEntity) {
            return safeQuery((QueryParamEntity) query);
        }
        if (query instanceof Map) {
            return JSON.parseObject(JSON.toJSONString(query), QueryParamEntity.class);
        }
        return JSON.parseObject(JSON.toJSONString(body), QueryParamEntity.class);
    }

    private QueryParamEntity safeQuery(QueryParamEntity query) {
        return query == null ? QueryParamEntity.of() : query;
    }

    private String[] properties(DeiceDataRequestEntity request) {
        List<String> properties = request == null ? Collections.emptyList() : nullToEmpty(request.getProperties());
        return properties.toArray(new String[0]);
    }

    private List<String> nullToEmpty(List<String> value) {
        return value == null ? Collections.emptyList() : value;
    }
}
