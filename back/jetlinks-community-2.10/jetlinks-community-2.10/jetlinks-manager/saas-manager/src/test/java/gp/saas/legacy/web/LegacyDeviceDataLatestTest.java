package gp.saas.legacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import gp.saas.legacy.dto.DeiceDataRequestEntity;
import gp.saas.legacy.dto.DeviceDataEntity;
import gp.saas.legacy.service.LegacyDeviceLatestValueService;
import gp.saas.legacy.service.LegacyDeviceLatestValueService.LatestValue;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.community.device.entity.DeviceProperty;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.core.metadata.DeviceMetadata;
import org.jetlinks.core.metadata.PropertyMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyDeviceDataLatestTest {

    private DeviceDataService deviceDataService;
    private LocalDeviceInstanceService deviceService;
    private LegacyDeviceLatestValueService latestValueService;
    private DeviceMetadata metadata;
    private LegacyDeviceDataController controller;

    @BeforeEach
    void setUp() {
        deviceDataService = mock(DeviceDataService.class);
        deviceService = mock(LocalDeviceInstanceService.class);
        latestValueService = mock(LegacyDeviceLatestValueService.class);
        metadata = mock(DeviceMetadata.class);
        when(deviceService.getMetadata(anyString())).thenReturn(Mono.just(metadata));
        controller = new LegacyDeviceDataController(deviceDataService, deviceService, latestValueService);
    }

    @Test
    void shouldKeepRequestOrderDeduplicateDevicesAndAvoidSqlForFullRedisHit() {
        List<String> properties = Arrays.asList("p2", "p1");
        List<String> requestedProperties = Arrays.asList("p2", "p1", "p2");
        when(latestValueService.readProperties(anyString(), eq(properties)))
            .thenAnswer(invocation -> Mono.just(redisValues(invocation.getArgument(0))));

        DeiceDataRequestEntity request = request(
            Arrays.asList("device-2", "device-1", "device-2"),
            requestedProperties);

        List<DeviceDataEntity> result = controller.getLatestPropertiesFromRedis(request).collectList().block();

        assertThat(result).extracting(DeviceDataEntity::getId)
            .containsExactly("device-2", "device-1");
        assertThat(propertyIds(result.get(0))).containsExactly("p2", "p1");
        assertThat(propertyIds(result.get(1))).containsExactly("p2", "p1");
        verify(deviceDataService, never())
            .queryEachOneProperties(anyString(), any(QueryParamEntity.class), any(String[].class));
    }

    @Test
    void shouldFallbackMissingAndInvalidPropertiesWithOneOrderedSqlResultMerge() {
        List<String> properties = Arrays.asList("p1", "p2", "p3", "missing");
        Map<String, LatestValue> redis = new LinkedHashMap<>();
        redis.put("p1", new LatestValue("redis-1", 100));
        redis.put("p2", new LatestValue("invalid", 0));
        when(latestValueService.readProperties("device-1", properties)).thenReturn(Mono.just(redis));
        when(deviceDataService.queryEachOneProperties(
            eq("device-1"),
            any(QueryParamEntity.class),
            eq(new String[]{"p2", "p3", "missing"})))
            .thenReturn(Flux.just(
                property("device-1", "p3", "database-3", 300),
                property("device-1", "p2", "database-2", 200)));

        DeviceDataEntity result = controller
            .getLatestPropertiesFromRedis(request(List.of("device-1"), properties))
            .single()
            .block();

        List<DeviceProperty> data = properties(result);
        assertThat(data).extracting(DeviceProperty::getProperty).containsExactly("p1", "p2", "p3");
        assertThat(data).extracting(DeviceProperty::getValue)
            .containsExactly("redis-1", "database-2", "database-3");
        verify(deviceDataService).queryEachOneProperties(
            eq("device-1"),
            any(QueryParamEntity.class),
            eq(new String[]{"p2", "p3", "missing"}));
    }

    @Test
    void shouldPropagateTimescaleFailure() {
        List<String> properties = List.of("p1");
        when(latestValueService.readProperties("device-1", properties))
            .thenReturn(Mono.just(Collections.emptyMap()));
        when(deviceDataService.queryEachOneProperties(
            eq("device-1"),
            any(QueryParamEntity.class),
            eq(new String[]{"p1"})))
            .thenReturn(Flux.error(new IllegalStateException("timescale unavailable")));

        assertThatThrownBy(() -> controller
            .getLatestPropertiesFromRedis(request(List.of("device-1"), properties))
            .collectList()
            .block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("timescale unavailable");
    }

    @Test
    void shouldFallbackAllRequestedPropertiesWithOneSqlWhenRedisFails() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveHashOperations<String, String, String> hash = mock(ReactiveHashOperations.class);
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        when(hash.multiGet(
            eq("device:device-1:latest"),
            eq(Arrays.asList("property:p1", "property:p2"))))
            .thenReturn(Mono.error(new IllegalStateException("redis unavailable")));

        LegacyDeviceLatestValueService redisService =
            new LegacyDeviceLatestValueService(redis, new ObjectMapper());
        LegacyDeviceDataController redisController =
            new LegacyDeviceDataController(deviceDataService, deviceService, redisService);
        when(deviceDataService.queryEachOneProperties(
            eq("device-1"),
            any(QueryParamEntity.class),
            eq(new String[]{"p1", "p2"})))
            .thenReturn(Flux.just(
                property("device-1", "p2", "database-2", 200),
                property("device-1", "p1", "database-1", 100)));

        DeviceDataEntity result = redisController
            .getLatestPropertiesFromRedis(request(List.of("device-1"), Arrays.asList("p1", "p2")))
            .single()
            .block();

        assertThat(properties(result)).extracting(DeviceProperty::getProperty)
            .containsExactly("p1", "p2");
        verify(deviceDataService).queryEachOneProperties(
            eq("device-1"), any(QueryParamEntity.class), eq(new String[]{"p1", "p2"}));
    }

    @Test
    void shouldOmitAllMissingPropertiesWhenDatabaseHasNoHistory() {
        List<String> requested = Arrays.asList("p1", "p2");
        when(latestValueService.readProperties("device-1", requested))
            .thenReturn(Mono.just(Collections.emptyMap()));
        when(deviceDataService.queryEachOneProperties(
            eq("device-1"), any(QueryParamEntity.class), eq(new String[]{"p1", "p2"})))
            .thenReturn(Flux.empty());

        DeviceDataEntity result = controller
            .getLatestPropertiesFromRedis(request(List.of("device-1"), requested))
            .single()
            .block();

        assertThat(result.getData()).isEmpty();
        verify(deviceDataService).queryEachOneProperties(
            eq("device-1"), any(QueryParamEntity.class), eq(new String[]{"p1", "p2"}));
    }

    @Test
    void emptyPropertyRequestUsesHgetAllButExplicitSinglePropertyUsesHmget() {
        PropertyMetadata p1 = mock(PropertyMetadata.class);
        PropertyMetadata p2 = mock(PropertyMetadata.class);
        when(p1.getId()).thenReturn("p1");
        when(p2.getId()).thenReturn("p2");
        when(metadata.getProperties()).thenReturn(Arrays.asList(p1, p2));
        when(latestValueService.readAll("device-1")).thenReturn(Mono.just(redisValues("device-1")));

        assertThat(controller.getRedisProperties("device-1").collectList().block())
            .extracting(DeviceProperty::getProperty)
            .containsExactly("p1", "p2");
        verify(latestValueService).readAll("device-1");

        when(latestValueService.readProperties("device-1", List.of("p1")))
            .thenReturn(Mono.just(Collections.singletonMap(
                "p1", new LatestValue("single", 300))));
        DeviceProperty single = controller.getRedisProperty("device-1", "p1").block();

        assertThat(single).isNotNull();
        assertThat(single.getValue()).isEqualTo("single");
        verify(latestValueService).readProperties("device-1", List.of("p1"));
    }

    @Test
    void shouldAcceptFiveHundredDevicesAndOneHundredPropertiesAfterDeduplication() {
        List<String> devices = IntStream.range(0, 500)
            .mapToObj(index -> "device-" + index)
            .collect(Collectors.toList());
        List<String> properties = IntStream.range(0, 100)
            .mapToObj(index -> "p" + index)
            .collect(Collectors.toList());
        when(deviceService.getMetadata(anyString())).thenReturn(Mono.empty());

        List<DeviceDataEntity> result = controller
            .getLatestPropertiesFromRedis(request(devices, properties))
            .collectList()
            .block();

        assertThat(result).hasSize(500);
        assertThat(result).extracting(DeviceDataEntity::getId).containsExactlyElementsOf(devices);
        verify(latestValueService, never()).readAll(anyString());
        verify(latestValueService, never()).readProperties(anyString(), any());
    }

    @Test
    void shouldBoundMetadataAndRedisConcurrencyAtTenAndKeepDeviceOrder() {
        List<String> devices = IntStream.range(0, 25)
            .mapToObj(index -> "device-" + index)
            .collect(Collectors.toList());
        List<String> requested = List.of("p1");
        AtomicInteger metadataActive = new AtomicInteger();
        AtomicInteger metadataMaxActive = new AtomicInteger();
        AtomicInteger redisActive = new AtomicInteger();
        AtomicInteger redisMaxActive = new AtomicInteger();

        when(deviceService.getMetadata(anyString())).thenAnswer(invocation ->
            delayed(metadata, metadataActive, metadataMaxActive));
        when(latestValueService.readProperties(anyString(), eq(requested))).thenAnswer(invocation ->
            delayed(
                Collections.singletonMap("p1", new LatestValue(invocation.getArgument(0), 100)),
                redisActive,
                redisMaxActive));

        List<DeviceDataEntity> result = controller
            .getLatestPropertiesFromRedis(request(devices, requested))
            .collectList()
            .block();

        assertThat(result).extracting(DeviceDataEntity::getId).containsExactlyElementsOf(devices);
        assertThat(metadataMaxActive.get()).isBetween(2, 10);
        assertThat(redisMaxActive.get()).isBetween(2, 10);
        assertThat(result).allSatisfy(device -> assertThat(properties(device))
            .singleElement()
            .satisfies(property -> assertThat(property.getValue()).isEqualTo(device.getId())));
    }

    @Test
    void shouldRejectDeviceAndCellLimitsAsBadRequest() {
        List<String> tooManyDevices = IntStream.range(0, 501)
            .mapToObj(index -> "device-" + index)
            .collect(Collectors.toList());

        assertThatThrownBy(() -> controller
            .getLatestPropertiesFromRedis(request(tooManyDevices, List.of("p1"))))
            .isInstanceOf(BusinessException.class)
            .extracting("status")
            .isEqualTo(400);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
            controller, "validateLatestExpandedQuerySize", 1001, 100))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("device-property cells")
            .extracting("status")
            .isEqualTo(400);
    }

    @Test
    void shouldRejectMoreThanOneHundredPropertiesAsBadRequest() {
        List<String> properties = IntStream.range(0, 101)
            .mapToObj(index -> "p" + index)
            .collect(Collectors.toList());

        assertThatThrownBy(() -> controller
            .getLatestPropertiesFromRedis(request(List.of("device-1"), properties)))
            .isInstanceOf(BusinessException.class)
            .extracting("status")
            .isEqualTo(400);
    }

    @Test
    void shouldApplyPropertyLimitAfterExpandingMetadata() {
        List<PropertyMetadata> properties = IntStream.range(0, 101)
            .mapToObj(index -> {
                PropertyMetadata property = mock(PropertyMetadata.class);
                when(property.getId()).thenReturn("p" + index);
                return property;
            })
            .collect(Collectors.toList());
        when(metadata.getProperties()).thenReturn(properties);

        assertThatThrownBy(() -> controller.getRedisProperties("device-1").collectList().block())
            .isInstanceOf(BusinessException.class)
            .extracting("status")
            .isEqualTo(400);
    }

    private <T> Mono<T> delayed(T value, AtomicInteger active, AtomicInteger maxActive) {
        return Mono.defer(() -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            return Mono
                .delay(Duration.ofMillis(20))
                .map(ignore -> {
                    active.decrementAndGet();
                    return value;
                });
        });
    }

    private Map<String, LatestValue> redisValues(String deviceId) {
        Map<String, LatestValue> values = new LinkedHashMap<>();
        values.put("p1", new LatestValue(deviceId + "-1", 100));
        values.put("p2", new LatestValue(deviceId + "-2", 200));
        return values;
    }

    private DeiceDataRequestEntity request(List<String> devices, List<String> properties) {
        DeiceDataRequestEntity request = new DeiceDataRequestEntity();
        request.setDevices(new ArrayList<>(devices));
        request.setProperties(new ArrayList<>(properties));
        return request;
    }

    private DeviceProperty property(String deviceId, String property, Object value, long timestamp) {
        DeviceProperty result = new DeviceProperty();
        result.setDeviceId(deviceId);
        result.setProperty(property);
        result.setValue(value);
        result.setTimestamp(timestamp);
        result.setCreateTime(timestamp);
        return result;
    }

    private List<String> propertyIds(DeviceDataEntity data) {
        return properties(data)
            .stream()
            .map(DeviceProperty::getProperty)
            .collect(Collectors.toList());
    }

    private List<DeviceProperty> properties(DeviceDataEntity data) {
        return data
            .getData()
            .stream()
            .map(DeviceProperty.class::cast)
            .collect(Collectors.toList());
    }
}
