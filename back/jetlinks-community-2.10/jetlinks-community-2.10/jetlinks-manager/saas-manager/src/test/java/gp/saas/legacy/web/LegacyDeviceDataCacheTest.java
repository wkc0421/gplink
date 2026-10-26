package gp.saas.legacy.web;

import com.github.benmanes.caffeine.cache.Cache;
import gp.saas.apitoken.service.ApiTokenScopeService;
import gp.saas.legacy.dto.DeiceDataRequestEntity;
import gp.saas.legacy.dto.DeviceAggregationDataEntity;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.community.Interval;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.community.timeseries.query.Aggregation;
import org.jetlinks.community.timeseries.query.AggregationData;
import org.jetlinks.community.timescaledb.thing.counter.CounterReadContext;
import org.jetlinks.community.timescaledb.thing.counter.CounterSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyDeviceDataCacheTest {

    private static final long AGGREGATION_CACHE_LIMIT = 16L * 1024 * 1024;

    private DeviceDataService dataService;
    private LegacyDeviceDataController controller;

    @BeforeEach
    void setUp() {
        dataService = mock(DeviceDataService.class);
        controller = new LegacyDeviceDataController(dataService, null, null);
    }

    @Test
    void aggregationFormatIsPartOfCacheAndSingleFlightKey() {
        when(dataService.aggregationPropertiesByDevice(
            eq("d1"), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class)))
            .thenAnswer(invocation -> {
                DeviceDataService.AggregationRequest query = invocation.getArgument(1);
                return Flux.just(row(query.getFormat(), 1));
            });

        List<DeviceAggregationDataEntity> month = values(request("yyyy-MM"));
        List<DeviceAggregationDataEntity> day = values(request("yyyy-MM-dd"));

        assertThat(month).singleElement().extracting(DeviceAggregationDataEntity::getTime)
            .isEqualTo("yyyy-MM");
        assertThat(day).singleElement().extracting(DeviceAggregationDataEntity::getTime)
            .isEqualTo("yyyy-MM-dd");
        verify(dataService, times(2)).aggregationPropertiesByDevice(
            eq("d1"), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void oversizedEntryWeightIsNotTruncatedAndEntryIsSkipped() {
        String huge = "x".repeat(8 * 1024 * 1024 + 1);
        DeviceAggregationDataEntity entity = new DeviceAggregationDataEntity();
        entity.setTime("2024-01-01 00:00:00");
        entity.setValue(huge);
        List<DeviceAggregationDataEntity> value = Collections.singletonList(entity);
        Integer weight = ReflectionTestUtils.invokeMethod(
            controller, "weighAggregationEntry", "oversized", value);
        Cache<String, List<DeviceAggregationDataEntity>> cache =
            (Cache<String, List<DeviceAggregationDataEntity>>) ReflectionTestUtils.getField(
                controller, "shortAggCache");

        assertThat(weight).isNotNull();
        assertThat(weight.longValue()).isGreaterThan(AGGREGATION_CACHE_LIMIT);
        ReflectionTestUtils.invokeMethod(
            controller, "putAggregationCache", cache, "oversized", value);
        assertThat(cache.getIfPresent("oversized")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void mutatingReturnedDtoAndNestedValueDoesNotPoisonCache() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("items", new ArrayList<>(List.of("original")));
        when(dataService.aggregationPropertiesByDevice(
            eq("d1"), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class)))
            .thenReturn(Flux.just(row("original-time", nested)));
        DeiceDataRequestEntity request = request("yyyy-MM-dd HH:mm:ss");

        List<DeviceAggregationDataEntity> first = values(request);
        first.get(0).setTime("mutated-time");
        ((List<Object>) ((Map<String, Object>) first.get(0).getValue()).get("items"))
            .set(0, "mutated");

        List<DeviceAggregationDataEntity> second = values(request);
        assertThat(second).singleElement().satisfies(item -> {
            assertThat(item.getTime()).isEqualTo("original-time");
            Map<String, Object> cachedValue = (Map<String, Object>) item.getValue();
            assertThat((List<Object>) cachedValue.get("items")).containsExactly("original");
        });
        verify(dataService).aggregationPropertiesByDevice(
            eq("d1"), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class));
    }

    @Test
    void concurrentSubscribersShareOneLoadAndFirstContextReachesUpstream() {
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicReference<String> observedTenant = new AtomicReference<>();
        Sinks.One<AggregationData> gate = Sinks.one();
        when(dataService.aggregationPropertiesByDevice(
            eq("d1"), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class)))
            .thenReturn(Flux.deferContextual(context -> {
                subscriptions.incrementAndGet();
                observedTenant.set(context.get("tenant"));
                return gate.asMono();
            }));
        DeiceDataRequestEntity request = request("yyyy-MM-dd");

        Mono<Map<String, List<DeviceAggregationDataEntity>>> first = controller
            .queryAggregation(request)
            .single()
            .contextWrite(Context.of("tenant", "tenant-a"));
        Mono<Map<String, List<DeviceAggregationDataEntity>>> second = controller
            .queryAggregation(request)
            .single()
            .contextWrite(Context.of("tenant", "tenant-b"));

        StepVerifier.create(Mono.zip(first, second))
            .then(() -> {
                assertThat(subscriptions).hasValue(1);
                assertThat(observedTenant).hasValue("tenant-a");
                gate.tryEmitValue(row("shared", 1));
            })
            .expectNextCount(1)
            .verifyComplete();
        verify(dataService).aggregationPropertiesByDevice(
            eq("d1"), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void errorRemovesInflightEntryAndNextRequestRetries() {
        when(dataService.aggregationPropertiesByDevice(
            eq("d1"), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class)))
            .thenReturn(
                Flux.error(new IllegalStateException("database unavailable")),
                Flux.just(row("recovered", 2)));
        DeiceDataRequestEntity request = request("yyyy-MM-dd");

        assertThatThrownBy(() -> values(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("database unavailable");
        assertThat(values(request)).singleElement()
            .extracting(DeviceAggregationDataEntity::getTime)
            .isEqualTo("recovered");

        ConcurrentMap<String, ?> inflight = (ConcurrentMap<String, ?>)
            ReflectionTestUtils.getField(controller, "aggregationInflight");
        assertThat(inflight).isEmpty();
        verify(dataService, times(2)).aggregationPropertiesByDevice(
            eq("d1"), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void timeoutRemovesInflightEntryAndNextRequestRetriesWithoutRealDelay() {
        AtomicInteger attempts = new AtomicInteger();
        when(dataService.aggregationPropertiesByDevice(
            eq("d1"), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class)))
            .thenAnswer(invocation -> attempts.incrementAndGet() == 1
                ? Flux.<AggregationData>never()
                : Flux.just(row("recovered-after-timeout", 3)));
        DeiceDataRequestEntity request = request("yyyy-MM-dd");

        StepVerifier.withVirtualTime(() -> controller.queryAggregation(request).single())
            .thenAwait(Duration.ofSeconds(31))
            .expectError(TimeoutException.class)
            .verify(Duration.ofSeconds(5));

        ConcurrentMap<String, ?> inflight = (ConcurrentMap<String, ?>)
            ReflectionTestUtils.getField(controller, "aggregationInflight");
        assertThat(inflight).isEmpty();
        assertThat(values(request)).singleElement()
            .extracting(DeviceAggregationDataEntity::getTime)
            .isEqualTo("recovered-after-timeout");
        assertThat(attempts).hasValue(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyLastSubscriberCancellationCancelsUpstreamAndRemovesInflightEntry() {
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        when(dataService.aggregationPropertiesByDevice(
            eq("d1"), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class)))
            .thenReturn(Flux.defer(() -> {
                subscriptions.incrementAndGet();
                return Mono.<AggregationData>never().doOnCancel(cancellations::incrementAndGet);
            }));
        DeiceDataRequestEntity request = request("yyyy-MM-dd");
        Mono<Map<String, List<DeviceAggregationDataEntity>>> first =
            controller.queryAggregation(request).single();
        Mono<Map<String, List<DeviceAggregationDataEntity>>> second =
            controller.queryAggregation(request).single();

        Disposable firstSubscription = first.subscribe();
        Disposable secondSubscription = second.subscribe();
        assertThat(subscriptions).hasValue(1);

        firstSubscription.dispose();
        assertThat(cancellations).hasValue(0);
        secondSubscription.dispose();
        assertThat(cancellations).hasValue(1);

        ConcurrentMap<String, ?> inflight = (ConcurrentMap<String, ?>)
            ReflectionTestUtils.getField(controller, "aggregationInflight");
        assertThat(inflight).isEmpty();
    }

    @Test
    void configuredAggregationReadsDatabaseVersionBeforeEveryCacheHit() {
        LocalDeviceInstanceService devices = mock(LocalDeviceInstanceService.class);
        CounterSnapshotService snapshots = mock(CounterSnapshotService.class);
        controller = new LegacyDeviceDataController(dataService, devices, null, snapshots, null);
        when(devices.findById(List.of("d1"))).thenReturn(Flux.just(device("d1", "product-1")));
        when(snapshots.readContext("product-1", "p1")).thenReturn(
            Mono.just(new CounterReadContext(true, 1, 1, 1)),
            Mono.just(new CounterReadContext(true, 1, 1, 1)),
            Mono.just(new CounterReadContext(true, 1, 2, 1)));
        when(dataService.aggregationPropertiesByDevice(
            eq("d1"), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class)))
            .thenReturn(Flux.just(row("before", 1)), Flux.just(row("after", 2)));
        DeiceDataRequestEntity request = request("yyyy-MM-dd");
        request.getAggregationRequest().getQuery().setInterval(Interval.ofDays(1));

        assertThat(values(request)).singleElement()
            .extracting(DeviceAggregationDataEntity::getTime).isEqualTo("before");
        assertThat(values(request)).singleElement()
            .extracting(DeviceAggregationDataEntity::getTime).isEqualTo("before");
        assertThat(values(request)).singleElement()
            .extracting(DeviceAggregationDataEntity::getTime).isEqualTo("after");

        verify(devices, times(3)).findById(List.of("d1"));
        verify(snapshots, times(3)).readContext("product-1", "p1");
        verify(dataService, times(2)).aggregationPropertiesByDevice(
            eq("d1"), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class));
    }

    @Test
    void unconfiguredAggregationBypassesResultCache() {
        LocalDeviceInstanceService devices = mock(LocalDeviceInstanceService.class);
        CounterSnapshotService snapshots = mock(CounterSnapshotService.class);
        controller = new LegacyDeviceDataController(dataService, devices, null, snapshots, null);
        when(devices.findById(List.of("d1"))).thenReturn(Flux.just(device("d1", "product-1")));
        when(snapshots.readContext("product-1", "p1"))
            .thenReturn(Mono.just(new CounterReadContext(false, 1, 1, 1)));
        when(dataService.aggregationPropertiesByDevice(
            eq("d1"), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class)))
            .thenReturn(Flux.just(row("first", 1)), Flux.just(row("second", 2)));

        assertThat(values(request("yyyy-MM-dd"))).singleElement()
            .extracting(DeviceAggregationDataEntity::getTime).isEqualTo("first");
        assertThat(values(request("yyyy-MM-dd"))).singleElement()
            .extracting(DeviceAggregationDataEntity::getTime).isEqualTo("second");
        verify(dataService, times(2)).aggregationPropertiesByDevice(
            eq("d1"), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class));
    }

    @Test
    void tokenScopeIsCheckedAgainBeforeServingAggregationCacheHit() {
        LocalDeviceInstanceService devices = mock(LocalDeviceInstanceService.class);
        CounterSnapshotService snapshots = mock(CounterSnapshotService.class);
        ApiTokenScopeService scope = mock(ApiTokenScopeService.class);
        controller = new LegacyDeviceDataController(dataService, devices, null, snapshots, scope);
        when(devices.findById(List.of("d1"))).thenReturn(Flux.just(device("d1", "product-1")));
        when(scope.requireDevice("d1", "product-1")).thenReturn(
            Mono.empty(), Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)));
        when(snapshots.readContext("product-1", "p1"))
            .thenReturn(Mono.just(new CounterReadContext(true, 1, 1, 1)));
        when(dataService.aggregationPropertiesByDevice(
            eq("d1"), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class)))
            .thenReturn(Flux.just(row("cached", 1)));
        DeiceDataRequestEntity request = request("yyyy-MM-dd");

        assertThat(values(request)).singleElement()
            .extracting(DeviceAggregationDataEntity::getTime).isEqualTo("cached");
        StepVerifier.create(controller.queryAggregation(request))
            .expectErrorMatches(error -> error instanceof ResponseStatusException status
                && status.getStatusCode().equals(HttpStatus.FORBIDDEN))
            .verify();
        verify(scope, times(2)).requireDevice("d1", "product-1");
        verify(dataService).aggregationPropertiesByDevice(
            eq("d1"), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class));
    }

    @Test
    void aggregateBatchLoadsDeviceRosterOnceAndContextOncePerProduct() {
        LocalDeviceInstanceService devices = mock(LocalDeviceInstanceService.class);
        CounterSnapshotService snapshots = mock(CounterSnapshotService.class);
        ApiTokenScopeService scope = mock(ApiTokenScopeService.class);
        controller = new LegacyDeviceDataController(dataService, devices, null, snapshots, scope);
        when(devices.findById(List.of("d1", "d2"))).thenReturn(Flux.just(
            device("d1", "product-1"), device("d2", "product-1")));
        when(scope.requireDevice(any(String.class), eq("product-1"))).thenReturn(Mono.empty());
        when(snapshots.readContext("product-1", "p1"))
            .thenReturn(Mono.just(new CounterReadContext(true, 1, 1, 1)));
        when(dataService.aggregationPropertiesByDevice(
            any(String.class), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class)))
            .thenReturn(Flux.just(row("same", 1)));
        DeiceDataRequestEntity request = request("yyyy-MM-dd");
        request.setDevices(List.of("d1", "d2"));

        assertThat(controller.queryAggregation(request).collectList().block()).hasSize(2);
        verify(devices).findById(List.of("d1", "d2"));
        verify(scope).requireDevice("d1", "product-1");
        verify(scope).requireDevice("d2", "product-1");
        verify(snapshots).readContext("product-1", "p1");
    }

    private DeviceInstanceEntity device(String id, String productId) {
        DeviceInstanceEntity device = new DeviceInstanceEntity();
        device.setId(id);
        device.setProductId(productId);
        return device;
    }

    private List<DeviceAggregationDataEntity> values(DeiceDataRequestEntity request) {
        Map<String, List<DeviceAggregationDataEntity>> response = controller
            .queryAggregation(request)
            .single()
            .block();
        assertThat(response).isNotNull();
        return response.get("d1");
    }

    private DeiceDataRequestEntity request(String format) {
        DeviceDataService.AggregationRequest query = new DeviceDataService.AggregationRequest();
        query.setFrom(new Date(1_700_000_000_000L));
        query.setTo(new Date(1_700_000_300_000L));
        query.setInterval(Interval.ofMinutes(5));
        query.setLimit(1);
        query.setFormat(format);
        query.setFilter(QueryParamEntity.of());

        DeiceDataRequestEntity.AggregationRequestBody aggregation =
            new DeiceDataRequestEntity.AggregationRequestBody();
        aggregation.setQuery(query);
        aggregation.setColumns(Collections.singletonList(
            new DeviceDataService.DevicePropertyAggregation("p1", "p1", Aggregation.FIRST)));

        DeiceDataRequestEntity request = new DeiceDataRequestEntity();
        request.setDevices(Collections.singletonList("d1"));
        request.setProperties(Collections.singletonList("p1"));
        request.setAggregationRequest(aggregation);
        return request;
    }

    private AggregationData row(String time, Object value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("time", time);
        data.put("p1", value);
        return AggregationData.of(data);
    }
}
