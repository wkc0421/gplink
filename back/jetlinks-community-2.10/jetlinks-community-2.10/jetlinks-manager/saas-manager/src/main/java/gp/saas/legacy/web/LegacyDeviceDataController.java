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
import gp.saas.legacy.service.LegacyIntervalSummaryService;
import gp.saas.apitoken.service.ApiTokenScopeService;
import io.swagger.v3.oas.annotations.Parameter;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.core.param.TermType;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.community.Interval;
import org.jetlinks.community.things.data.PropertyBoundaryValues;
import org.jetlinks.community.device.entity.DeviceEvent;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceProperty;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.community.timeseries.query.Aggregation;
import org.jetlinks.community.timeseries.query.AggregationData;
import org.jetlinks.community.timescaledb.thing.counter.CounterBoundary;
import org.jetlinks.community.timescaledb.thing.counter.CounterReadContext;
import org.jetlinks.community.timescaledb.thing.counter.CounterSnapshotService;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;
import reactor.util.context.ContextView;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@RestController
@RequestMapping({
    "/v1/device-data",
    "/api/v1/device-data"
})
@Authorize
@Resource(id = "device-opt-api", name = "Device data")
public class LegacyDeviceDataController {

    private static final long THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1000;
    private static final int MAX_DEVICE_QUERY_SIZE = 500;
    private static final int MAX_PROPERTY_QUERY_SIZE = 100;
    private static final int MAX_LATEST_QUERY_CELLS = 100_000;
    private static final int LATEST_QUERY_CONCURRENCY = 10;
    private static final int HISTORY_AGG_POINT_CAP = 1000;
    private static final int INTERVAL_BUCKET_CAP = 1000;
    private static final int MAX_QUERY_CELLS = 100_000;
    private static final long AGG_CACHE_MAX_WEIGHT_BYTES = 16L * 1024 * 1024;
    private static final long INTERVAL_CACHE_MAX_WEIGHT_BYTES = 8L * 1024 * 1024;
    private static final long HOT_WINDOW_MS = 10L * 60 * 1000;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final String HISTORY_AGG_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private static final ZoneId HISTORY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HISTORY_TIME_FORMATTER =
        DateTimeFormatter.ofPattern(HISTORY_AGG_TIME_FORMAT);
    private static final Duration SHORT_CACHE_TTL = Duration.ofMinutes(1);
    private static final Duration LONG_CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(30);
    private static final String AGG_TIME_KEY = "time";
    private static final String AGG_TS_KEY = "_ts";

    private final DeviceDataService deviceDataService;
    private final LocalDeviceInstanceService deviceService;
    private final LegacyDeviceLatestValueService latestValueService;
    private final CounterSnapshotService counterSnapshotService;
    private final ApiTokenScopeService apiTokenScopeService;
    @Value("${device-data.interval.near-hour-snap-enabled:true}")
    private boolean nearHourSnapEnabled = true;
    @Value("${device-data.interval.near-hour-snap-seconds:60}")
    private long nearHourSnapSeconds = 60;
    private final Cache<String, List<DeviceAggregationDataEntity>> shortAggCache = Caffeine
        .newBuilder()
        .expireAfterWrite(SHORT_CACHE_TTL)
        .maximumWeight(AGG_CACHE_MAX_WEIGHT_BYTES)
        .weigher((String key, List<DeviceAggregationDataEntity> value) -> weighAggregationEntry(key, value))
        .build();
    private final Cache<String, List<DeviceAggregationDataEntity>> longAggCache = Caffeine
        .newBuilder()
        .expireAfterWrite(LONG_CACHE_TTL)
        .maximumWeight(AGG_CACHE_MAX_WEIGHT_BYTES)
        .weigher((String key, List<DeviceAggregationDataEntity> value) -> weighAggregationEntry(key, value))
        .build();
    private final Cache<String, List<DeviceIntervalDataEntity>> shortIntervalHistoryCache = Caffeine
        .newBuilder()
        .expireAfterWrite(SHORT_CACHE_TTL)
        .maximumWeight(INTERVAL_CACHE_MAX_WEIGHT_BYTES)
        .weigher((String key, List<DeviceIntervalDataEntity> value) -> weighIntervalHistoryEntry(key, value))
        .build();
    private final Cache<String, List<DeviceIntervalDataEntity>> longIntervalHistoryCache = Caffeine
        .newBuilder()
        .expireAfterWrite(LONG_CACHE_TTL)
        .maximumWeight(INTERVAL_CACHE_MAX_WEIGHT_BYTES)
        .weigher((String key, List<DeviceIntervalDataEntity> value) -> weighIntervalHistoryEntry(key, value))
        .build();
    private final ConcurrentMap<String, InflightRequest<List<DeviceAggregationDataEntity>>> aggregationInflight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, InflightRequest<List<DeviceIntervalDataEntity>>> intervalHistoryInflight = new ConcurrentHashMap<>();

    private record LatestQueryTarget(String deviceId,
                                     DeviceMetadata metadata,
                                     List<String> properties,
                                     boolean allProperties) {
    }

    private enum TimeRangeStatus {
        ABSENT, VALID, UNSAFE
    }

    private record TimeRangeExtraction(TimeRangeStatus status,
                                       Long start,
                                       Long end,
                                       String reason) {

        private static TimeRangeExtraction absent() {
            return new TimeRangeExtraction(TimeRangeStatus.ABSENT, null, null, null);
        }

        private static TimeRangeExtraction valid(long start, long end) {
            return new TimeRangeExtraction(TimeRangeStatus.VALID, start, end, null);
        }

        private static TimeRangeExtraction unsafe(String reason) {
            return new TimeRangeExtraction(TimeRangeStatus.UNSAFE, null, null, reason);
        }
    }

    private record IntervalRange(long start, long end) {
    }

    private record IntervalDevice(String deviceId, String productId) {
    }

    private final class InflightRequest<T> {

        private final Supplier<Mono<T>> loader;
        private final Consumer<InflightRequest<T>> onClose;
        private final Sinks.One<T> result = Sinks.one();
        private int subscribers;
        private boolean started;
        private boolean closed;
        private Disposable upstream;

        private InflightRequest(Supplier<Mono<T>> loader,
                                Consumer<InflightRequest<T>> onClose) {
            this.loader = loader;
            this.onClose = onClose;
        }

        private Mono<T> join(Supplier<Mono<T>> retry) {
            return Mono.deferContextual(context -> {
                boolean shouldStart;
                synchronized (this) {
                    if (closed) {
                        return retry.get();
                    }
                    subscribers++;
                    shouldStart = !started;
                    started = true;
                }
                if (shouldStart) {
                    start(context);
                }
                AtomicBoolean released = new AtomicBoolean();
                return result.asMono().doFinally(signal -> release(released));
            });
        }

        private void start(ContextView context) {
            Disposable disposable;
            try {
                disposable = loader.get()
                    .contextWrite(context)
                    .subscribe(
                    this::completeValue,
                    this::completeError,
                    this::completeEmpty);
            } catch (Throwable error) {
                completeError(error);
                return;
            }
            synchronized (this) {
                upstream = disposable;
                if (closed && !disposable.isDisposed()) {
                    disposable.dispose();
                }
            }
        }

        private void completeValue(T value) {
            if (close()) {
                result.tryEmitValue(value);
            }
        }

        private void completeError(Throwable error) {
            if (close()) {
                result.tryEmitError(error);
            }
        }

        private void completeEmpty() {
            if (close()) {
                result.tryEmitEmpty();
            }
        }

        private boolean close() {
            synchronized (this) {
                if (closed) {
                    return false;
                }
                closed = true;
            }
            onClose.accept(this);
            return true;
        }

        private void release(AtomicBoolean released) {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            Disposable cancellation = null;
            boolean shouldClose = false;
            synchronized (this) {
                subscribers--;
                if (subscribers == 0 && !closed) {
                    closed = true;
                    cancellation = upstream;
                    shouldClose = true;
                }
            }
            if (shouldClose) {
                onClose.accept(this);
                if (cancellation != null) {
                    cancellation.dispose();
                }
                result.tryEmitEmpty();
            }
        }
    }

    public LegacyDeviceDataController(DeviceDataService deviceDataService,
                                      LocalDeviceInstanceService deviceService,
                                      LegacyDeviceLatestValueService latestValueService) {
        this(deviceDataService, deviceService, latestValueService,
            (CounterSnapshotService) null, null);
    }

    @Autowired
    public LegacyDeviceDataController(DeviceDataService deviceDataService,
                                      LocalDeviceInstanceService deviceService,
                                      LegacyDeviceLatestValueService latestValueService,
                                      ObjectProvider<CounterSnapshotService> counterSnapshotService,
                                      ApiTokenScopeService apiTokenScopeService) {
        this(deviceDataService, deviceService, latestValueService,
            counterSnapshotService.getIfAvailable(), apiTokenScopeService);
    }

    LegacyDeviceDataController(DeviceDataService deviceDataService,
                               LocalDeviceInstanceService deviceService,
                               LegacyDeviceLatestValueService latestValueService,
                               CounterSnapshotService counterSnapshotService,
                               ApiTokenScopeService apiTokenScopeService) {
        this.deviceDataService = deviceDataService;
        this.deviceService = deviceService;
        this.latestValueService = latestValueService;
        this.counterSnapshotService = counterSnapshotService;
        this.apiTokenScopeService = apiTokenScopeService;
    }

    @GetMapping({"/{deviceId}/properties/es", "/{deviceId}/properties/pg"})
    public Flux<DeviceProperty> getLatestPropertiesFromPg(@PathVariable String deviceId) {
        return withQueryTimeout(
            deviceDataService.queryEachOneProperties(deviceId, QueryParamEntity.of()));
    }

    @GetMapping({"/{deviceId}/{property}/es", "/{deviceId}/{property}/pg"})
    public Flux<DeviceProperty> getLatestPropertyFromPg(@PathVariable String deviceId,
                                                        @PathVariable String property) {
        return withQueryTimeout(
            deviceDataService.queryEachOneProperties(deviceId, QueryParamEntity.of(), property));
    }

    @PostMapping({"/devices/properties/es", "/devices/properties/pg"})
    @QueryAction
    public Flux<DeviceDataEntity> getLatestPropertiesFromPg(@RequestBody DeiceDataRequestEntity request) {
        return Flux
            .fromIterable(nullToEmpty(request.getDevices()))
            .flatMap(deviceId -> withQueryTimeout(deviceDataService
                .queryEachOneProperties(deviceId, QueryParamEntity.of(), properties(request)))
                .collectList()
                .map(data -> new DeviceDataEntity(deviceId, data)), 10);
    }

    @PostMapping("/devices/properties/redis")
    @QueryAction
    public Flux<DeviceDataEntity> getLatestPropertiesFromRedis(@RequestBody DeiceDataRequestEntity request) {
        List<String> devices = distinctInOrder(request == null ? null : request.getDevices());
        List<String> requestedProperties = distinctInOrder(request == null ? null : request.getProperties());
        validateLatestQuerySize(devices, requestedProperties);
        return Flux
            .fromIterable(devices)
            .flatMapSequential(
                deviceId -> resolveLatestQueryTarget(deviceId, requestedProperties, devices.size()),
                LATEST_QUERY_CONCURRENCY)
            .collectList()
            .flatMapMany(targets -> Flux
                .fromIterable(targets)
                .flatMapSequential(target -> queryLatestProperties(target)
                    .collectList()
                    .map(data -> new DeviceDataEntity(target.deviceId(), data)), LATEST_QUERY_CONCURRENCY));
    }

    @GetMapping("/{deviceId}/properties/redis")
    public Flux<DeviceProperty> getRedisProperties(@PathVariable String deviceId) {
        return getRedisProperties(deviceId, Collections.emptyList());
    }

    @GetMapping("/{deviceId}/{property}/redis")
    public Mono<DeviceProperty> getRedisProperty(@PathVariable String deviceId,
                                                  @PathVariable String property) {
        return getRedisProperties(deviceId, Collections.singletonList(property)).next();
    }

    @PostMapping({"/{deviceId}/{property}/history/test", "/{deviceId}/{property}/history/pg/test"})
    @QueryAction
    public Mono<PagerResult<DeviceProperty>> queryPropertyPageTest(@PathVariable String deviceId,
                                                                   @PathVariable String property,
                                                                   @RequestBody QueryParamEntity query) {
        return withQueryTimeout(
            deviceDataService.queryPropertyPage(deviceId, property, safeQuery(query)));
    }

    @PostMapping({"/{deviceId}/{property}/history/no-paging/raw", "/{deviceId}/{property}/history/no-paging/pg/raw"})
    @QueryAction
    public Flux<DeviceProperty> queryPropertyNoPagingTest(@PathVariable String deviceId,
                                                          @PathVariable String property,
                                                          @RequestBody QueryParamEntity query) {
        QueryParamEntity param = safeQuery(query);
        param.noPaging();
        return withQueryTimeout(deviceDataService.queryProperty(deviceId, param, property));
    }

    @PostMapping({"/{deviceId}/{property}/history", "/{deviceId}/{property}/history/pg"})
    @QueryAction
    public Mono<PagerResult<DeviceProperty>> queryPropertyPage(@PathVariable String deviceId,
                                                               @PathVariable String property,
                                                               @RequestBody QueryParamEntity query) {
        return deviceService
            .getMetadata(deviceId)
            .flatMap(metadata -> withQueryTimeout(deviceDataService
                .queryPropertyPage(deviceId, property, safeQuery(query)))
                .map(result -> {
                    result.getData().forEach(item -> formatProperty(item, metadata));
                    return result;
                }));
    }

    @PostMapping({"/{deviceId}/{property}/history/no-paging", "/{deviceId}/{property}/history/no-paging/pg"})
    @QueryAction
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
    @QueryAction
    public Flux<DeviceEvent> queryEventHistory(@PathVariable String deviceId,
                                               @PathVariable String event,
                                               @RequestBody QueryParamEntity query) {
        return withQueryTimeout(deviceDataService
            .queryEvent(deviceId, event, safeQuery(query), false))
            .onErrorResume(UnsupportedOperationException.class, ignore -> Flux.empty());
    }

    @PostMapping("/devices/agg/_query")
    @QueryAction
    public Flux<Map<String, List<DeviceAggregationDataEntity>>> queryAggregation(@RequestBody DeiceDataRequestEntity request) {
        validateAggregationRequest(request);
        validateDeviceQuerySize(request);
        DeviceDataService.AggregationRequest query = request.getAggregationRequest().getQuery();
        DeviceDataService.DevicePropertyAggregation[] columns = request
            .getAggregationRequest()
            .getColumns()
            .toArray(new DeviceDataService.DevicePropertyAggregation[0]);
        validateResultCells(nullToEmpty(request.getDevices()).size(), query.getLimit(), columns.length);
        String valueKey = columns[0].getAlias();
        Cache<String, List<DeviceAggregationDataEntity>> cache = aggregationCache(query);

        // The device roster and API token scope must be checked on every request,
        // including a local cache hit. Look up the roster in one query for batches.
        return authorizedAggregationDevices(request)
            .flatMapMany(devices -> aggregationContexts(devices, columns[0].getProperty())
                .flatMapMany(contexts -> Flux
                    .fromIterable(nullToEmpty(request.getDevices()))
                    .flatMap(deviceId -> {
                        IntervalDevice device = devices.get(deviceId);
                        CounterReadContext context = device == null ? null : contexts.get(device.productId());
                        boolean cacheable = deviceService == null || (
                            context != null && context.isEnabled()
                                && Objects.equals(request.getProperties().get(0), columns[0].getProperty())
                                && (query.getFilter() == null || query.getFilter().getTerms() == null
                                    || query.getFilter().getTerms().isEmpty()));
                        Supplier<Mono<List<DeviceAggregationDataEntity>>> loader = () -> deviceDataService
                            .aggregationPropertiesByDevice(deviceId, query, columns)
                            .map(AggregationData::values)
                            .map(map -> toAggregationData(valueKey, map))
                            .collectList();
                        Mono<List<DeviceAggregationDataEntity>> data = cacheable
                            ? cachedAggregation(cache,
                                aggregationCacheKey(request, deviceId,
                                    context == null ? null : context.getVersion()), loader)
                            : loader.get().timeout(QUERY_TIMEOUT);
                        return data.map(value -> {
                            Map<String, List<DeviceAggregationDataEntity>> result = new HashMap<>();
                            result.put(deviceId, value);
                            return result;
                        });
                    }, 5)));
    }

    @PostMapping("/devices/interval")
    @QueryAction
    public Flux<DeviceIntervalDataEntity> queryDeviceInterval(@RequestParam Long startTime,
                                                              @RequestParam Long endTime,
                                                              @RequestBody DeiceDataRequestEntity request) {
        validateIntervalRequest(request, startTime, endTime);
        if (request.getDevices() == null || request.getDevices().stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("devices must be provided and device ids must not be empty");
        }
        if (request.getProperties().get(0) == null || request.getProperties().get(0).isBlank()) {
            throw new IllegalArgumentException("property must not be empty");
        }
        validateDeviceQuerySize(request);
        long now = System.currentTimeMillis();
        boolean futureEnd = endTime > now;
        endTime = Math.max(startTime, Math.min(endTime, now));
        validateResultCells(nullToEmpty(request.getDevices()).size(), 1, 1);
        String property = request.getProperties().get(0);
        long actualStart = startTime;
        long actualEnd = endTime;
        return authorizedIntervalDevices(request)
            .flatMapMany(devices -> queryIntervalSummaryDevices(
                devices, nullToEmpty(request.getDevices()), property, actualStart, actualEnd, futureEnd))
            .collectList()
            .timeout(QUERY_TIMEOUT)
            .flatMapMany(Flux::fromIterable);
    }

    @PostMapping("/devices/interval/{type}/history")
    @QueryAction
    public Flux<List<DeviceIntervalDataEntity>> queryDeviceIntervalHistory(
                                                                          @Parameter(description = "分段类型：5m/15m 按分钟，day 按小时，month 按天，year 按月；"
                                                                              + "week 按自然周（周一至周日），quarter 按自然季度（1—3、4—6、7—9、10—12月）。"
                                                                              + "按北京时间切分，week/quarter 保留请求首尾，未来结束时间截到当前时刻；已启用历史统计的属性优先读取配置快照。")
                                                                          @PathVariable String type,
                                                                          @RequestParam Long startTime,
                                                                          @RequestParam Long endTime,
                                                                          @RequestBody DeiceDataRequestEntity request) {
        validateIntervalRequest(request, startTime, endTime);
        validateDeviceQuerySize(request);
        IntervalRange normalized = normalizeHistoryRange(type, startTime, endTime);
        startTime = normalized.start();
        // GpLink 1.0 includes the current partial bucket, but never future buckets.
        // Cap after alignment so rounding cannot move the query back into the future.
        endTime = Math.max(startTime, Math.min(normalized.end(), System.currentTimeMillis()));
        List<Long> boundaries = intervalBoundaries(type, startTime, endTime);
        if (boundaries.size() - 1 > INTERVAL_BUCKET_CAP) {
            throw new IllegalArgumentException("Interval bucket count exceeds " + INTERVAL_BUCKET_CAP);
        }
        validateResultCells(nullToEmpty(request.getDevices()).size(), boundaries.size() - 1, 1);

        String property = request.getProperties().get(0);
        long actualStart = startTime;
        long actualEnd = endTime;
        return authorizedIntervalDevices(request)
            .flatMapMany(devices -> actualStart >= actualEnd
                ? Flux.fromIterable(nullToEmpty(request.getDevices()))
                    .map(id -> Collections.<DeviceIntervalDataEntity>emptyList())
                : queryIntervalHistoryDevices(
                devices, nullToEmpty(request.getDevices()), property,
                type, actualStart, actualEnd, boundaries))
            .collectList()
            .timeout(QUERY_TIMEOUT)
            .flatMapMany(Flux::fromIterable);
    }

    private Flux<DeviceProperty> getRedisProperties(String deviceId, List<String> properties) {
        return resolveLatestQueryTarget(deviceId, properties, 1)
            .flatMapMany(this::queryLatestProperties);
    }

    private Mono<LatestQueryTarget> resolveLatestQueryTarget(String deviceId,
                                                             List<String> properties,
                                                             int deviceCount) {
        return deviceService
            .getMetadata(deviceId)
            .map(metadata -> {
                List<String> ids = properties.isEmpty()
                    ? orderedPropertyIds(metadata)
                    : new ArrayList<>(properties);
                validateLatestExpandedQuerySize(deviceCount, ids.size());
                return new LatestQueryTarget(deviceId, metadata, ids, properties.isEmpty());
            })
            .defaultIfEmpty(new LatestQueryTarget(deviceId, null, Collections.emptyList(), properties.isEmpty()));
    }

    private Flux<DeviceProperty> queryLatestProperties(LatestQueryTarget target) {
        if (target.metadata() == null || target.properties().isEmpty()) {
            return Flux.empty();
        }
        Mono<Map<String, LatestValue>> redisValues = target.allProperties()
            ? latestValueService.readAll(target.deviceId())
            : latestValueService.readProperties(target.deviceId(), target.properties());
        return redisValues.flatMapMany(values -> mergeLatestProperties(
            target.deviceId(), target.properties(), values, target.metadata()));
    }

    private Flux<DeviceProperty> mergeLatestProperties(String deviceId,
                                                       List<String> propertyIds,
                                                       Map<String, LatestValue> redisValues,
                                                       DeviceMetadata metadata) {
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        for (String property : propertyIds) {
            if (!isValidLatestValue(redisValues.get(property))) {
                missing.add(property);
            }
        }

        Mono<Map<String, DeviceProperty>> databaseValues;
        if (missing.isEmpty()) {
            databaseValues = Mono.just(Collections.emptyMap());
        } else {
            databaseValues = withQueryTimeout(deviceDataService
                .queryEachOneProperties(deviceId, QueryParamEntity.of(), missing.toArray(new String[0])))
                .collect(
                    () -> new LinkedHashMap<String, DeviceProperty>(),
                    (values, property) -> values.putIfAbsent(property.getProperty(), property));
        }

        return databaseValues.flatMapMany(fallback -> {
            List<DeviceProperty> merged = new ArrayList<>(propertyIds.size());
            for (String property : propertyIds) {
                LatestValue redisValue = redisValues.get(property);
                if (isValidLatestValue(redisValue)) {
                    merged.add(toDeviceProperty(deviceId, property, redisValue, metadata));
                    continue;
                }
                DeviceProperty databaseValue = fallback.get(property);
                if (databaseValue != null) {
                    merged.add(formatProperty(databaseValue, metadata));
                }
            }
            return Flux.fromIterable(merged);
        });
    }

    private boolean isValidLatestValue(LatestValue value) {
        return value != null && value.getTimestamp() > 0;
    }

    private DeviceProperty toDeviceProperty(String deviceId,
                                            String property,
                                            LatestValue latestValue,
                                            DeviceMetadata metadata) {
        DeviceProperty deviceProperty = new DeviceProperty();
        long timestamp = latestValue.getTimestamp();
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
        TimeRangeExtraction range = extractTimeRangeMillis(query);
        if (range.status() == TimeRangeStatus.UNSAFE) {
            throw new BusinessException(range.reason(), 400);
        }
        if (range.status() == TimeRangeStatus.ABSENT || range.end() - range.start() <= THREE_DAYS_MS) {
            return withQueryTimeout(deviceDataService
                .queryProperty(deviceId, query, property))
                .map(item -> formatProperty(item, metadata));
        }
        return queryDownsampledHistory(deviceId, property, range.start(), range.end(), query, metadata);
    }

    private Flux<DeviceProperty> queryDownsampledHistory(String deviceId,
                                                         String property,
                                                         long start,
                                                         long end,
                                                         QueryParamEntity sourceQuery,
                                                         DeviceMetadata metadata) {
        Interval interval = chooseHistoryInterval(start, end);
        DeviceDataService.AggregationRequest request = new DeviceDataService.AggregationRequest();
        request.setFrom(new Date(start));
        request.setTo(new Date(end));
        request.setInterval(interval);
        request.setFormat(HISTORY_AGG_TIME_FORMAT);
        request.setLimit(computeBucketLimit(start, end, interval));
        request.setFilter(downsampleFilter(sourceQuery));

        DeviceDataService.DevicePropertyAggregation aggregation =
            new DeviceDataService.DevicePropertyAggregation(property, property, Aggregation.FIRST);

        return withQueryTimeout(deviceDataService
            .aggregationPropertiesByDevice(deviceId, request, aggregation))
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
        return Mono.defer(() -> {
            List<DeviceAggregationDataEntity> cached = cache.getIfPresent(cacheKey);
            if (cached != null) {
                return Mono.just(copyAggregationValues(cached));
            }
            InflightRequest<List<DeviceAggregationDataEntity>> flight = aggregationInflight.computeIfAbsent(
                cacheKey,
                key -> new InflightRequest<>(
                    () -> loader.get()
                        .timeout(QUERY_TIMEOUT)
                        .map(LegacyDeviceDataController::snapshotAggregationValues)
                        .doOnNext(value -> putAggregationCache(cache, key, value)),
                    closed -> aggregationInflight.remove(key, closed)));
            return flight
                .join(() -> cachedAggregation(cache, cacheKey, loader))
                .map(LegacyDeviceDataController::copyAggregationValues);
        });
    }

    private String aggregationCacheKey(DeiceDataRequestEntity request,
                                       String deviceId,
                                       String counterVersion) {
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
        key.put("format", query.getFormat());
        key.put("limit", query.getLimit());
        key.put("filter", query.getFilter());
        if (counterVersion != null) {
            key.put("counterVersion", counterVersion);
        }
        return JSON.toJSONString(key);
    }

    private Mono<Map<String, IntervalDevice>> authorizedAggregationDevices(DeiceDataRequestEntity request) {
        List<String> ids = distinctInOrder(request.getDevices());
        if (ids.isEmpty()) {
            return Mono.just(Collections.emptyMap());
        }
        if (deviceService == null) {
            // Direct controller unit tests do not construct the surrounding device services.
            Map<String, IntervalDevice> devices = new LinkedHashMap<>();
            ids.forEach(id -> devices.put(id, new IntervalDevice(id, null)));
            return Mono.just(devices);
        }
        return deviceService.findById(ids)
            .collectMap(DeviceInstanceEntity::getId)
            .flatMap(found -> Flux.fromIterable(ids)
                .concatMap(id -> {
                    DeviceInstanceEntity device = found.get(id);
                    if (device == null) {
                        return Mono.error(new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Device not found: " + id));
                    }
                    return authorizeIntervalDevice(id, device);
                })
                .collectMap(IntervalDevice::deviceId));
    }

    private Mono<Map<String, CounterReadContext>> aggregationContexts(
        Map<String, IntervalDevice> devices,
        String property) {
        if (counterSnapshotService == null || property == null) {
            return Mono.just(Collections.emptyMap());
        }
        return Flux.fromIterable(devices.values())
            .filter(device -> device.productId() != null)
            .map(IntervalDevice::productId)
            .distinct()
            .flatMap(productId -> counterSnapshotService.readContext(productId, property)
                .map(context -> Map.entry(productId, context)), 5)
            .collectMap(Map.Entry::getKey, Map.Entry::getValue);
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

    private void validateResultCells(int devices, int buckets, int columns) {
        long cells = Math.multiplyExact(Math.multiplyExact((long) devices, buckets), columns);
        if (cells > MAX_QUERY_CELLS) {
            throw new BusinessException(
                "Query result cells exceed max supported size " + MAX_QUERY_CELLS,
                400);
        }
    }

    private void validateLatestQuerySize(List<String> devices, List<String> properties) {
        if (devices.size() > MAX_DEVICE_QUERY_SIZE) {
            throw new BusinessException(
                "Too many devices, max supported size is " + MAX_DEVICE_QUERY_SIZE,
                400);
        }
        if (properties.size() > MAX_PROPERTY_QUERY_SIZE) {
            throw new BusinessException(
                "Too many properties, max supported size is " + MAX_PROPERTY_QUERY_SIZE,
                400);
        }
        long cells = (long) devices.size() * properties.size();
        if (cells > MAX_LATEST_QUERY_CELLS) {
            throw new BusinessException(
                "Too many device-property cells, max supported size is " + MAX_LATEST_QUERY_CELLS,
                400);
        }
    }

    private void validateLatestExpandedQuerySize(int deviceCount, int propertyCount) {
        if (propertyCount > MAX_PROPERTY_QUERY_SIZE) {
            throw new BusinessException(
                "Too many properties, max supported size is " + MAX_PROPERTY_QUERY_SIZE,
                400);
        }
        long cells = (long) deviceCount * propertyCount;
        if (cells > MAX_LATEST_QUERY_CELLS) {
            throw new BusinessException(
                "Too many device-property cells, max supported size is " + MAX_LATEST_QUERY_CELLS,
                400);
        }
    }

    private Mono<List<IntervalDevice>> authorizedIntervalDevices(DeiceDataRequestEntity request) {
        List<String> ids = distinctInOrder(request.getDevices());
        if (deviceService == null) {
            // Direct controller unit tests do not construct the surrounding device services.
            return Mono.just(ids.stream()
                .map(id -> new IntervalDevice(id, null))
                .collect(Collectors.toList()));
        }
        return Flux.fromIterable(ids)
            .flatMapSequential(id -> deviceService
                .findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Device not found: " + id)))
                .flatMap(device -> authorizeIntervalDevice(id, device)), 10)
            .collectList();
    }

    private Mono<IntervalDevice> authorizeIntervalDevice(String id, DeviceInstanceEntity device) {
        Mono<Void> scope = apiTokenScopeService == null
            ? Mono.empty()
            : apiTokenScopeService.requireDevice(id, device.getProductId());
        return scope.thenReturn(new IntervalDevice(id, device.getProductId()));
    }

    private Flux<DeviceIntervalDataEntity> queryIntervalSummaryDevices(List<IntervalDevice> devices,
                                                                        List<String> requestedOrder,
                                                                        String property,
                                                                        long start,
                                                                        long end,
                                                                        boolean futureEnd) {
        LegacyIntervalSummaryService summaries = new LegacyIntervalSummaryService(
            deviceDataService, counterSnapshotService, latestValueService);
        if (end <= start) {
            return Flux.fromIterable(requestedOrder)
                .map(id -> LegacyIntervalSummaryService.row(id, property, start, end, null, null));
        }
        return Flux.fromIterable(intervalDeviceGroups(devices).entrySet())
            .flatMapSequential(group -> {
                String productId = group.getKey();
                List<IntervalDevice> members = group.getValue();
                if (productId == null || counterSnapshotService == null) {
                    return Flux.fromIterable(members)
                        .flatMapSequential(member -> summaries.raw(
                            member.deviceId(), property, start, end), 5);
                }
                return counterSnapshotService.readContext(productId, property)
                    .flatMapMany(context -> context.isEnabled()
                        ? summaries.configured(productId, property,
                            members.stream().map(IntervalDevice::deviceId).toList(), start, end, futureEnd,
                            nearHourSnapEnabled, nearHourSnapSeconds)
                        : Flux.fromIterable(members).flatMapSequential(member -> summaries.raw(
                            member.deviceId(), property, start, end), 5));
            }, 5)
            .collectMap(DeviceIntervalDataEntity::getDeviceId)
            .timeout(QUERY_TIMEOUT)
            .flatMapMany(results -> Flux.fromIterable(requestedOrder)
                .map(results::get));
    }

    private Flux<List<DeviceIntervalDataEntity>> queryIntervalHistoryDevices(List<IntervalDevice> devices,
                                                                              List<String> requestedOrder,
                                                                              String property,
                                                                              String type,
                                                                              long start,
                                                                              long end,
                                                                              List<Long> boundaries) {
        if (counterSnapshotService == null) {
            return Flux.fromIterable(requestedOrder)
                .flatMap(deviceId -> deviceService == null
                    ? queryIntervalHistoryForDeviceCached(
                        deviceId, property, type, start, end, boundaries)
                    : queryIntervalHistoryForDevice(
                        deviceId, property, start, end, boundaries), 5);
        }
        boolean minuteHistory = minuteIntervalForType(type) != null;
        return Flux.fromIterable(intervalDeviceGroups(devices).entrySet())
            .flatMapSequential(group -> {
                String productId = group.getKey();
                List<IntervalDevice> members = group.getValue();
                if (productId == null || minuteHistory) {
                    return rawHistoryGroup(members, property, start, end, boundaries);
                }
                return counterSnapshotService.readContext(productId, property)
                    .flatMapMany(context -> context.isEnabled()
                        ? queryConfiguredHistoryGroup(
                            productId, property, type, members, context, start, end, boundaries)
                        : rawHistoryGroup(members, property, start, end, boundaries));
            }, 5)
            .collectMap(list -> list.get(0).getDeviceId())
            .timeout(QUERY_TIMEOUT)
            .flatMapMany(results -> Flux.fromIterable(requestedOrder)
                .map(id -> copyIntervalValues(results.get(id))));
    }

    private Flux<List<DeviceIntervalDataEntity>> rawHistoryGroup(List<IntervalDevice> devices,
                                                                 String property,
                                                                 long start,
                                                                 long end,
                                                                 List<Long> boundaries) {
        return Flux.fromIterable(devices)
            .flatMapSequential(device -> queryIntervalHistoryForDevice(
                device.deviceId(), property, start, end, boundaries), 5);
    }

    private Map<String, List<IntervalDevice>> intervalDeviceGroups(List<IntervalDevice> devices) {
        Map<String, List<IntervalDevice>> groups = new LinkedHashMap<>();
        for (IntervalDevice device : devices) {
            groups.computeIfAbsent(device.productId(), ignored -> new ArrayList<>()).add(device);
        }
        return groups;
    }

    private Flux<List<DeviceIntervalDataEntity>> queryConfiguredHistoryGroup(String productId,
                                                                              String property,
                                                                              String type,
                                                                              List<IntervalDevice> members,
                                                                              CounterReadContext context,
                                                                              long start,
                                                                              long end,
                                                                              List<Long> boundaries) {
        Cache<String, List<DeviceIntervalDataEntity>> cache = intervalHistoryCache(type, end);
        Map<String, List<DeviceIntervalDataEntity>> result = new LinkedHashMap<>();
        List<String> misses = new ArrayList<>();
        for (IntervalDevice member : members) {
            String key = intervalCacheKey(member.deviceId(), property, start, end,
                type, productId, context.getVersion());
            List<DeviceIntervalDataEntity> cached = cache.getIfPresent(key);
            if (cached == null) {
                misses.add(member.deviceId());
            } else {
                result.put(member.deviceId(), copyIntervalValues(cached));
            }
        }
        if (misses.isEmpty()) {
            return Flux.fromIterable(result.values());
        }
        Mono<Map<String, Map<Long, CounterBoundary>>> resolved = "day".equals(type)
            ? counterSnapshotService.resolveBoundaries(productId, property, misses, boundaries, true)
            : counterSnapshotService.resolveBoundaries(productId, property, misses, boundaries);
        return resolved
            .flatMap(rows -> counterRangeEndValues(productId, property, misses, rows, start, end)
                .map(endValues -> {
                    for (String deviceId : misses) {
                        Map<Long, Object> values = new LinkedHashMap<>();
                        for (Long boundary : boundaries) {
                            values.put(boundary, counterValue(rows, deviceId, boundary));
                        }
                        values.put(end, endValues.get(deviceId));
                        result.put(deviceId, toIntervalHistory(
                            deviceId, property, boundaries, values.get(start), values));
                    }
                    return result;
                }))
            .flatMap(values -> counterSnapshotService.readContext(productId, property)
                .map(current -> {
                    if (context.getVersion().equals(current.getVersion())) {
                        for (String deviceId : misses) {
                            String key = intervalCacheKey(deviceId, property, start, end,
                                type, productId, context.getVersion());
                            putIntervalHistoryCache(cache, key,
                                snapshotIntervalValues(values.get(deviceId)));
                        }
                    }
                    return values;
                }))
            .flatMapMany(values -> Flux.fromIterable(values.values()));
    }

    private Mono<Map<String, Object>> counterRangeEndValues(
        String productId, String property, List<String> devices,
        Map<String, Map<Long, CounterBoundary>> rows, long start, long end) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String device : devices) {
            Object value = counterValue(rows, device, end);
            Map<Long, CounterBoundary> boundaries = rows.get(device);
            CounterBoundary point = boundaries == null ? null : boundaries.get(end);
            if (point != null && point.getSampledAt() != null && point.getSampledAt() < start) {
                value = null;
            }
            values.put(device, value);
            if (value == null && end <= now) missing.add(device);
        }
        if (missing.isEmpty()) return Mono.just(values);
        return counterSnapshotService.firstValuesAfter(productId, property, missing, end)
            .map(after -> {
                for (String device : missing) values.put(device, exactCounterValue(after.get(device)));
                return values;
            });
    }

    private Object counterValue(Map<String, Map<Long, CounterBoundary>> rows,
                                String deviceId,
                                long boundary) {
        if (boundary > System.currentTimeMillis()) {
            return null;
        }
        Map<Long, CounterBoundary> values = rows.get(deviceId);
        CounterBoundary value = values == null ? null : values.get(boundary);
        return exactCounterValue(value == null ? null : value.getValue());
    }

    private Object exactCounterValue(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException error) {
            throw new BusinessException("Non-numeric counter reading", 500);
        }
    }

    private Mono<List<DeviceIntervalDataEntity>> queryIntervalHistoryForDevice(String deviceId,
                                                                               String property,
                                                                               long startTime,
                                                                              long endTime,
                                                                              List<Long> boundaries) {
        return queryBoundaryValues(deviceId, property, startTime, endTime, boundaries)
            .map(values -> toIntervalHistory(
                deviceId,
                property,
                boundaries,
                values.getInitialValue(),
                values.getValues().stream().collect(
                    LinkedHashMap::new,
                    (target, value) -> target.put(value.getBoundary(), value.getValue()),
                    Map::putAll)));
    }

    private Mono<PropertyBoundaryValues> queryBoundaryValues(String deviceId,
                                                              String property,
                                                              long startTime,
                                                              long endTime,
                                                              List<Long> boundaries) {
        return deviceDataService
            .queryPropertyAtBoundaries(deviceId, property, startTime, endTime, boundaries)
            .timeout(QUERY_TIMEOUT);
    }

    private Object boundaryValue(PropertyBoundaryValues values, long boundary) {
        for (PropertyBoundaryValues.BoundaryValue value : values.getValues()) {
            if (value.getBoundary() == boundary) {
                return value.getValue();
            }
        }
        return null;
    }

    private Mono<List<DeviceIntervalDataEntity>> queryIntervalHistoryForDeviceCached(String deviceId,
                                                                                    String property,
                                                                                    String type,
                                                                                    long startTime,
                                                                                    long endTime,
                                                                                    List<Long> boundaries) {
        Cache<String, List<DeviceIntervalDataEntity>> cache = intervalHistoryCache(type, endTime);
        String cacheKey = intervalCacheKey(deviceId, property, startTime, endTime, type);
        return Mono.defer(() -> {
            List<DeviceIntervalDataEntity> cached = cache.getIfPresent(cacheKey);
            if (cached != null) {
                return Mono.just(copyIntervalValues(cached));
            }
            InflightRequest<List<DeviceIntervalDataEntity>> flight = intervalHistoryInflight.computeIfAbsent(
                cacheKey,
                key -> new InflightRequest<>(
                    () -> queryIntervalHistoryForDevice(deviceId, property, startTime, endTime, boundaries)
                        .map(LegacyDeviceDataController::snapshotIntervalValues)
                        .doOnNext(value -> putIntervalHistoryCache(cache, key, value)),
                    closed -> intervalHistoryInflight.remove(key, closed)));
            return flight
                .join(() -> queryIntervalHistoryForDeviceCached(
                    deviceId, property, type, startTime, endTime, boundaries))
                .map(LegacyDeviceDataController::copyIntervalValues);
        });
    }

    private Cache<String, List<DeviceIntervalDataEntity>> intervalHistoryCache(String type, long endTime) {
        if (isHotWindow(endTime)) {
            return shortIntervalHistoryCache;
        }
        return "month".equals(type) || "year".equals(type)
            || "week".equals(type) || "quarter".equals(type)
            ? longIntervalHistoryCache
            : shortIntervalHistoryCache;
    }

    private boolean isHotWindow(Long endTime) {
        return endTime == null || endTime >= System.currentTimeMillis() - HOT_WINDOW_MS;
    }

    private String intervalCacheKey(String deviceId, String property, long startTime, long endTime, String type) {
        return intervalCacheKey(deviceId, property, startTime, endTime, type, null, null);
    }

    private String intervalCacheKey(String deviceId,
                                    String property,
                                    long startTime,
                                    long endTime,
                                    String type,
                                    String productId,
                                    String version) {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("device", deviceId);
        key.put("property", property);
        key.put("startTime", startTime);
        key.put("endTime", endTime);
        key.put("type", type);
        if (productId != null) {
            key.put("product", productId);
        }
        if (version != null) {
            key.put("version", version);
        }
        return JSON.toJSONString(key);
    }

    private List<DeviceIntervalDataEntity> toIntervalHistory(String deviceId,
                                                             String property,
                                                             List<Long> boundaries,
                                                             Object startValue,
                                                             Map<Long, Object> bucketValues) {
        Object currentValue = startValue;
        // The legacy client subtracts adjacent startValue fields. Keep a continuous
        // chain, including the current bucket, and seed a missing baseline from the
        // first observed reading as GpLink 1.0 does. Never invent a zero reading.
        if (currentValue == null) {
            for (Long boundary : boundaries) {
                if (bucketValues.get(boundary) != null) {
                    currentValue = bucketValues.get(boundary);
                    break;
                }
            }
        }
        List<DeviceIntervalDataEntity> data = new ArrayList<>();
        for (int i = 0; i < boundaries.size() - 1; i++) {
            Long bucketStart = boundaries.get(i);
            Long bucketEnd = boundaries.get(i + 1);
            Object intervalStartValue = currentValue;
            if (bucketValues.containsKey(bucketEnd)) {
                Object bucketValue = bucketValues.get(bucketEnd);
                if (bucketValue != null) {
                    currentValue = bucketValue;
                }
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

    private List<Long> intervalBoundaries(String type, long startTime, long endTime) {
        List<Long> boundaries = new ArrayList<>();
        boundaries.add(startTime);

        Calendar calendar = Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        calendar.setTimeInMillis(startTime);
        Integer minuteInterval = minuteIntervalForType(type);
        if (minuteInterval != null) {
            alignToMinuteInterval(calendar, minuteInterval);
            calendar.add(Calendar.MINUTE, minuteInterval);
            addBoundaries(boundaries, calendar, Calendar.MINUTE, minuteInterval, startTime, endTime);
        } else if ("week".equals(type) || "quarter".equals(type)) {
            boolean week = "week".equals(type);
            Interval interval = Interval.of(1, week ? Interval.weeks : Interval.quarter);
            calendar.setTimeInMillis(interval.getUnit().truncatedTo(startTime, 1, HISTORY_ZONE));
            int field = week ? Calendar.DAY_OF_MONTH : Calendar.MONTH;
            int amount = week ? 7 : 3;
            calendar.add(field, amount);
            addBoundaries(boundaries, calendar, field, amount, startTime, endTime);
        } else if ("day".equals(type)) {
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            calendar.add(Calendar.HOUR_OF_DAY, 1);
            addBoundaries(boundaries, calendar, Calendar.HOUR_OF_DAY, 1, startTime, endTime);
        } else if ("month".equals(type)) {
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            calendar.add(Calendar.DAY_OF_MONTH, 1);
            addBoundaries(boundaries, calendar, Calendar.DAY_OF_MONTH, 1, startTime, endTime);
        } else if ("year".equals(type)) {
            calendar.set(Calendar.DAY_OF_MONTH, 1);
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            calendar.add(Calendar.MONTH, 1);
            addBoundaries(boundaries, calendar, Calendar.MONTH, 1, startTime, endTime);
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
                               int amount,
                               long startTime,
                               long endTime) {
        while (calendar.getTimeInMillis() <= startTime) {
            calendar.add(calendarField, amount);
        }
        while (calendar.getTimeInMillis() < endTime) {
            if (boundaries.size() > INTERVAL_BUCKET_CAP) {
                throw new IllegalArgumentException("Interval bucket count exceeds " + INTERVAL_BUCKET_CAP);
            }
            boundaries.add(calendar.getTimeInMillis());
            calendar.add(calendarField, amount);
        }
    }

    private IntervalRange normalizeHistoryRange(String type, long start, long end) {
        if ("day".equals(type)) {
            return new IntervalRange(floorHour(start), ceilHour(end));
        }
        if ("month".equals(type)) {
            return new IntervalRange(floorDay(start), ceilDay(end));
        }
        if ("year".equals(type)) {
            long tolerance = 60_000L;
            long normalizedStart = nearestMonthWithin(start, tolerance);
            long normalizedEnd = nearestMonthWithin(end, tolerance);
            if (normalizedStart < normalizedEnd) {
                return new IntervalRange(normalizedStart, normalizedEnd);
            }
        }
        return new IntervalRange(start, end);
    }

    private long nearestMonthWithin(long timestamp, long tolerance) {
        ZonedDateTime date = Instant.ofEpochMilli(timestamp).atZone(HISTORY_ZONE);
        long floor = date.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli();
        long ceil = date.withDayOfMonth(1).plusMonths(1).truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli();
        long nearest = timestamp - floor <= ceil - timestamp ? floor : ceil;
        return Math.abs(nearest - timestamp) <= tolerance ? nearest : timestamp;
    }

    private long floorHour(long timestamp) {
        return Instant.ofEpochMilli(timestamp).atZone(HISTORY_ZONE)
            .truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli();
    }

    private long ceilHour(long timestamp) {
        ZonedDateTime floor = Instant.ofEpochMilli(timestamp).atZone(HISTORY_ZONE)
            .truncatedTo(ChronoUnit.HOURS);
        long floorTime = floor.toInstant().toEpochMilli();
        return floorTime == timestamp ? timestamp : floor.plusHours(1).toInstant().toEpochMilli();
    }

    private long floorDay(long timestamp) {
        return Instant.ofEpochMilli(timestamp).atZone(HISTORY_ZONE)
            .truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli();
    }

    private long ceilDay(long timestamp) {
        ZonedDateTime floor = Instant.ofEpochMilli(timestamp).atZone(HISTORY_ZONE)
            .truncatedTo(ChronoUnit.DAYS);
        long floorTime = floor.toInstant().toEpochMilli();
        return floorTime == timestamp ? timestamp : floor.plusDays(1).toInstant().toEpochMilli();
    }

    private Integer minuteIntervalForType(String type) {
        if ("5m".equals(type) || "5min".equals(type) || "5minute".equals(type) || "5minutes".equals(type)) {
            return 5;
        }
        if ("15m".equals(type) || "15min".equals(type) || "15minute".equals(type) || "15minutes".equals(type)) {
            return 15;
        }
        return null;
    }

    private void alignToMinuteInterval(Calendar calendar, int minuteInterval) {
        int minute = calendar.get(Calendar.MINUTE);
        calendar.set(Calendar.MINUTE, minute - (minute % minuteInterval));
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }

    private TimeRangeExtraction extractTimeRangeMillis(QueryParamEntity query) {
        List<Term> terms = nullToEmptyTerms(query.getTerms());
        if (!containsTimeTerm(terms)) {
            return TimeRangeExtraction.absent();
        }
        Long start = null;
        Long end = null;
        try {
            for (Term term : terms) {
                if (term.getType() == Term.Type.or) {
                    return TimeRangeExtraction.unsafe(
                        "OR conditions combined with timestamp filters cannot be downsampled safely");
                }
                if (term.getTerms() != null && !term.getTerms().isEmpty()) {
                    if (containsTimeTerm(term.getTerms())) {
                        return TimeRangeExtraction.unsafe(
                            "Nested timestamp filters cannot be downsampled safely");
                    }
                    continue;
                }
                if (!"timestamp".equals(term.getColumn()) && !"createTime".equals(term.getColumn())) {
                    continue;
                }
                if (TermType.btw.equals(term.getTermType())) {
                    List<Object> values = toList(term.getValue());
                    if (values.size() != 2 || values.get(0) == null || values.get(1) == null) {
                        return TimeRangeExtraction.unsafe("Timestamp between filter requires two bounds");
                    }
                    start = later(start, toMillis(values.get(0)));
                    end = earlier(end, toMillis(values.get(1)));
                } else if (TermType.gt.equals(term.getTermType())) {
                    Long candidate = safeOffset(toMillis(term.getValue()), 1);
                    if (candidate == null) {
                        return TimeRangeExtraction.unsafe("Timestamp lower bound is out of range");
                    }
                    start = later(start, candidate);
                } else if (TermType.gte.equals(term.getTermType())) {
                    start = later(start, toMillis(term.getValue()));
                } else if (TermType.lt.equals(term.getTermType())) {
                    Long candidate = safeOffset(toMillis(term.getValue()), -1);
                    if (candidate == null) {
                        return TimeRangeExtraction.unsafe("Timestamp upper bound is out of range");
                    }
                    end = earlier(end, candidate);
                } else if (TermType.lte.equals(term.getTermType())) {
                    end = earlier(end, toMillis(term.getValue()));
                } else {
                    return TimeRangeExtraction.unsafe(
                        "Unsupported timestamp condition for downsampling: " + term.getTermType());
                }
            }
        } catch (RuntimeException error) {
            return TimeRangeExtraction.unsafe("Invalid timestamp filter");
        }
        if (start == null || end == null) {
            return TimeRangeExtraction.unsafe(
                "Timestamp downsampling requires both start and end bounds");
        }
        if (end <= start) {
            return TimeRangeExtraction.unsafe("Timestamp range must have end greater than start");
        }
        return TimeRangeExtraction.valid(start, end);
    }

    private boolean containsTimeTerm(List<Term> terms) {
        for (Term term : nullToEmptyTerms(terms)) {
            if ("timestamp".equals(term.getColumn()) || "createTime".equals(term.getColumn())) {
                return true;
            }
            if (term.getTerms() != null && containsTimeTerm(term.getTerms())) {
                return true;
            }
        }
        return false;
    }

    private List<Term> nullToEmptyTerms(List<Term> terms) {
        return terms == null ? Collections.emptyList() : terms;
    }

    private Long later(Long current, Long candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null ? candidate : Math.max(current, candidate);
    }

    private Long earlier(Long current, Long candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null ? candidate : Math.min(current, candidate);
    }

    private Long safeOffset(Long value, long offset) {
        if (value == null) {
            return null;
        }
        try {
            return Math.addExact(value, offset);
        } catch (ArithmeticException error) {
            return null;
        }
    }

    private QueryParamEntity downsampleFilter(QueryParamEntity source) {
        QueryParamEntity filter = safeQuery(source).clone();
        if (filter.getTerms().stream().anyMatch(term -> term.getType() == Term.Type.or)) {
            throw new BusinessException(
                "OR conditions combined with timestamp filters cannot be downsampled safely", 400);
        }
        if (filter.getTerms().stream().anyMatch(term -> term.getTerms() != null
            && !term.getTerms().isEmpty() && containsTimeTerm(term.getTerms()))) {
            throw new BusinessException("Nested timestamp filters cannot be downsampled safely", 400);
        }
        filter.getTerms().removeIf(term ->
            "timestamp".equals(term.getColumn()) || "createTime".equals(term.getColumn()));
        filter.setSorts(new ArrayList<>());
        filter.setPaging(false);
        return filter;
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

    private Interval chooseHistoryInterval(long start, long end) {
        // Use the finest supported clock-aligned width. Whole-hour/day widths remain
        // compatible with the hourly/daily CAGG layers instead of jumping straight
        // from two hours to one day for medium-sized ranges.
        for (int minutes = 5; minutes < 60; minutes++) {
            Interval candidate = Interval.ofMinutes(minutes);
            if (estimatedBucketCount(start, end, candidate) <= HISTORY_AGG_POINT_CAP) {
                return candidate;
            }
        }
        for (int hours = 1; hours < 24; hours++) {
            Interval candidate = Interval.ofHours(hours);
            if (estimatedBucketCount(start, end, candidate) <= HISTORY_AGG_POINT_CAP) {
                return candidate;
            }
        }
        Interval day = Interval.ofDays(1);
        if (estimatedBucketCount(start, end, day) <= HISTORY_AGG_POINT_CAP) {
            return day;
        }

        Interval month = Interval.ofMonth(1);
        if (estimatedBucketCount(start, end, month) <= HISTORY_AGG_POINT_CAP) {
            return month;
        }

        long months = Math.max(1, monthsBetween(start, end) + 1);
        int years = (int) Math.max(1,
            divideRoundUp(months, 12L * Math.max(1, HISTORY_AGG_POINT_CAP - 2)));
        Interval interval = Interval.of(years, Interval.year);
        while (estimatedBucketCount(start, end, interval) > HISTORY_AGG_POINT_CAP) {
            interval = Interval.of(++years, Interval.year);
        }
        return interval;
    }

    private int computeBucketLimit(long start, long end, Interval interval) {
        long limit = estimatedBucketCount(start, end, interval);
        if (limit > HISTORY_AGG_POINT_CAP) {
            throw new BusinessException("Unable to cover the full range within "
                + HISTORY_AGG_POINT_CAP + " aggregation buckets", 400);
        }
        return (int) Math.max(1, limit);
    }

    private long estimatedBucketCount(long start, long end, Interval interval) {
        if (end < start) {
            return 0;
        }
        int amount = interval.getNumber().intValueExact();
        long first = interval.getUnit().truncatedTo(start, amount, HISTORY_ZONE);
        long cursor = interval.getUnit().truncatedTo(end, amount, HISTORY_ZONE);
        long count = 0;
        while (cursor >= first && count <= HISTORY_AGG_POINT_CAP) {
            count++;
            if (cursor == first) {
                break;
            }
            long previous = interval.getUnit().advance(cursor, -amount, HISTORY_ZONE);
            if (previous >= cursor) {
                throw new BusinessException("Aggregation interval does not advance", 400);
            }
            cursor = previous;
        }
        return count;
    }

    private long monthsBetween(long start, long end) {
        YearMonth from = YearMonth.from(LocalDateTime.ofInstant(Instant.ofEpochMilli(start), HISTORY_ZONE));
        YearMonth to = YearMonth.from(LocalDateTime.ofInstant(Instant.ofEpochMilli(end), HISTORY_ZONE));
        return Math.max(0, ChronoUnit.MONTHS.between(from, to));
    }

    private long divideRoundUp(long value, long divisor) {
        return Math.floorDiv(value, divisor) + (Math.floorMod(value, divisor) == 0 ? 0 : 1);
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
                return LocalDateTime
                    .parse(String.valueOf(time), HISTORY_TIME_FORMATTER)
                    .atZone(HISTORY_ZONE)
                    .toInstant()
                    .toEpochMilli();
            } catch (DateTimeParseException error) {
                throw new IllegalArgumentException("Invalid aggregation time: " + time, error);
            }
        }
        throw new IllegalArgumentException("Aggregation time is required");
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

    private <T> Mono<T> withQueryTimeout(Mono<T> query) {
        return query.timeout(QUERY_TIMEOUT);
    }

    private <T> Flux<T> withQueryTimeout(Flux<T> query) {
        return Flux.defer(() -> {
            long deadline = System.nanoTime() + QUERY_TIMEOUT.toNanos();
            Supplier<Mono<Long>> remaining = () -> Mono.delay(Duration.ofNanos(
                Math.max(0L, deadline - System.nanoTime())));
            return query.timeout(remaining.get(), ignored -> remaining.get());
        });
    }

    private String[] properties(DeiceDataRequestEntity request) {
        List<String> properties = request == null ? Collections.emptyList() : nullToEmpty(request.getProperties());
        return properties.toArray(new String[0]);
    }

    private static List<DeviceAggregationDataEntity> snapshotAggregationValues(
        List<DeviceAggregationDataEntity> values) {
        List<DeviceAggregationDataEntity> snapshot = new ArrayList<>(values.size());
        for (DeviceAggregationDataEntity value : values) {
            DeviceAggregationDataEntity copy = new DeviceAggregationDataEntity();
            copy.setTime(value.getTime());
            copy.setValue(freezeValue(value.getValue()));
            snapshot.add(copy);
        }
        return Collections.unmodifiableList(snapshot);
    }

    private static List<DeviceAggregationDataEntity> copyAggregationValues(
        List<DeviceAggregationDataEntity> values) {
        List<DeviceAggregationDataEntity> copy = new ArrayList<>(values.size());
        for (DeviceAggregationDataEntity value : values) {
            DeviceAggregationDataEntity item = new DeviceAggregationDataEntity();
            item.setTime(value.getTime());
            item.setValue(copyCachedValue(value.getValue()));
            copy.add(item);
        }
        return copy;
    }

    private static DeviceIntervalDataEntity snapshotIntervalValue(DeviceIntervalDataEntity value) {
        return new DeviceIntervalDataEntity(
            value.getDeviceId(),
            value.getProperty(),
            value.getStartTime(),
            value.getEndTime(),
            freezeValue(value.getStartValue()),
            freezeValue(value.getEndValue()));
    }

    private static DeviceIntervalDataEntity copyIntervalValue(DeviceIntervalDataEntity value) {
        return new DeviceIntervalDataEntity(
            value.getDeviceId(),
            value.getProperty(),
            value.getStartTime(),
            value.getEndTime(),
            copyCachedValue(value.getStartValue()),
            copyCachedValue(value.getEndValue()));
    }

    private static List<DeviceIntervalDataEntity> snapshotIntervalValues(List<DeviceIntervalDataEntity> values) {
        List<DeviceIntervalDataEntity> snapshot = new ArrayList<>(values.size());
        for (DeviceIntervalDataEntity value : values) {
            snapshot.add(snapshotIntervalValue(value));
        }
        return Collections.unmodifiableList(snapshot);
    }

    private static List<DeviceIntervalDataEntity> copyIntervalValues(List<DeviceIntervalDataEntity> values) {
        List<DeviceIntervalDataEntity> copy = new ArrayList<>(values.size());
        for (DeviceIntervalDataEntity value : values) {
            copy.add(copyIntervalValue(value));
        }
        return copy;
    }

    private static void putAggregationCache(Cache<String, List<DeviceAggregationDataEntity>> cache,
                                            String key,
                                            List<DeviceAggregationDataEntity> value) {
        if (weighAggregationEntry(key, value) <= AGG_CACHE_MAX_WEIGHT_BYTES) {
            cache.put(key, value);
        }
    }

    private static void putIntervalHistoryCache(Cache<String, List<DeviceIntervalDataEntity>> cache,
                                                String key,
                                                List<DeviceIntervalDataEntity> value) {
        if (value.stream().allMatch(item -> item.getStartValue() != null && item.getEndValue() != null)
            && weighIntervalHistoryEntry(key, value) <= INTERVAL_CACHE_MAX_WEIGHT_BYTES) {
            cache.put(key, value);
        }
    }

    private static int weighAggregationEntry(String key, List<DeviceAggregationDataEntity> values) {
        long weight = estimateText(key) + 64L;
        for (DeviceAggregationDataEntity value : values) {
            weight += 80L + estimateText(value.getTime()) + estimateValue(value.getValue());
        }
        return boundedWeight(weight);
    }

    private static int weighIntervalEntry(String key, DeviceIntervalDataEntity value) {
        long weight = estimateText(key) + 112L
            + estimateText(value.getDeviceId())
            + estimateText(value.getProperty())
            + estimateValue(value.getStartValue())
            + estimateValue(value.getEndValue());
        return boundedWeight(weight);
    }

    private static int weighIntervalHistoryEntry(String key, List<DeviceIntervalDataEntity> values) {
        long weight = estimateText(key) + 64L;
        for (DeviceIntervalDataEntity value : values) {
            weight += weighIntervalEntry("", value);
        }
        return boundedWeight(weight);
    }

    private static long estimateText(String value) {
        return value == null ? 8L : 40L + (long) value.length() * 2;
    }

    private static long estimateValue(Object value) {
        return estimateValue(value, new IdentityHashMap<>());
    }

    private static long estimateValue(Object value, IdentityHashMap<Object, Boolean> visited) {
        if (value == null) {
            return 8L;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Date) {
            return 32L;
        }
        if (value instanceof CharSequence) {
            return 40L + (long) ((CharSequence) value).length() * 2;
        }
        if (visited.put(value, Boolean.TRUE) != null) {
            return 8L;
        }
        if (value instanceof byte[]) {
            return 24L + ((byte[]) value).length;
        }
        if (value instanceof char[]) {
            return 24L + (long) ((char[]) value).length * 2;
        }
        if (value instanceof Map) {
            long weight = 64L;
            for (Object entryObject : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObject;
                weight = saturatedAdd(weight, estimateValue(entry.getKey(), visited));
                weight = saturatedAdd(weight, estimateValue(entry.getValue(), visited));
            }
            return weight;
        }
        if (value instanceof Collection) {
            long weight = 48L;
            for (Object element : (Collection<?>) value) {
                weight = saturatedAdd(weight, estimateValue(element, visited));
            }
            return weight;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            Class<?> component = value.getClass().getComponentType();
            if (component.isPrimitive()) {
                int bytes = component == boolean.class || component == byte.class ? 1
                    : component == char.class || component == short.class ? 2
                    : component == int.class || component == float.class ? 4 : 8;
                return 24L + (long) length * bytes;
            }
            long weight = 24L;
            for (int i = 0; i < length; i++) {
                weight = saturatedAdd(weight, estimateValue(Array.get(value, i), visited));
            }
            return weight;
        }
        return 40L + (long) String.valueOf(value).length() * 2;
    }

    private static long saturatedAdd(long left, long right) {
        if (left >= Integer.MAX_VALUE || right >= Integer.MAX_VALUE - left) {
            return Integer.MAX_VALUE;
        }
        return left + right;
    }

    private static Object freezeValue(Object value) {
        if (value instanceof Date) {
            return new Date(((Date) value).getTime());
        }
        if (value instanceof Map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            ((Map<?, ?>) value).forEach((key, item) -> copy.put(freezeValue(key), freezeValue(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection) {
            List<Object> copy = new ArrayList<>(((Collection<?>) value).size());
            for (Object item : (Collection<?>) value) {
                copy.add(freezeValue(item));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            Object copy = Array.newInstance(value.getClass().getComponentType(), length);
            for (int i = 0; i < length; i++) {
                Array.set(copy, i, freezeValue(Array.get(value, i)));
            }
            return copy;
        }
        return value;
    }

    private static Object copyCachedValue(Object value) {
        if (value instanceof Date) {
            return new Date(((Date) value).getTime());
        }
        if (value instanceof Map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            ((Map<?, ?>) value).forEach((key, item) -> copy.put(copyCachedValue(key), copyCachedValue(item)));
            return copy;
        }
        if (value instanceof Collection) {
            List<Object> copy = new ArrayList<>(((Collection<?>) value).size());
            for (Object item : (Collection<?>) value) {
                copy.add(copyCachedValue(item));
            }
            return copy;
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            Object copy = Array.newInstance(value.getClass().getComponentType(), length);
            for (int i = 0; i < length; i++) {
                Array.set(copy, i, copyCachedValue(Array.get(value, i)));
            }
            return copy;
        }
        return value;
    }

    private static int boundedWeight(long weight) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, weight));
    }

    private List<String> nullToEmpty(List<String> value) {
        return value == null ? Collections.emptyList() : value;
    }

    private List<String> distinctInOrder(List<String> value) {
        return new ArrayList<>(new LinkedHashSet<>(nullToEmpty(value)));
    }
}
