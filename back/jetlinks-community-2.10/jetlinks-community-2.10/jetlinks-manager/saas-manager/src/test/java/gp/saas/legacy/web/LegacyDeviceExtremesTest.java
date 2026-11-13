package gp.saas.legacy.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import gp.saas.apitoken.service.ApiTokenScopeService;
import gp.saas.legacy.dto.DeviceExtremesData;
import gp.saas.legacy.dto.DeviceExtremesRequest;
import gp.saas.legacy.service.LegacyDeviceLatestValueService;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.community.things.data.PropertyExtremes;
import org.jetlinks.core.metadata.DeviceMetadata;
import org.jetlinks.core.metadata.SimplePropertyMetadata;
import org.jetlinks.core.metadata.types.DoubleType;
import org.jetlinks.core.metadata.types.StringType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyDeviceExtremesTest {
    private DeviceDataService data;
    private LocalDeviceInstanceService devices;
    private ApiTokenScopeService scope;
    private LegacyDeviceDataController controller;

    @Test
    void endpointRetainsBothPrefixesAndQueryAuthorization() throws Exception {
        assertThat(LegacyDeviceDataController.class.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).value())
            .contains("/api/v1/device-data", "/v1/device-data");
        var method = LegacyDeviceDataController.class.getMethod("queryDeviceExtremes", DeviceExtremesRequest.class);
        assertThat(method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class).value())
            .containsExactly("/devices/extremes/_query");
        assertThat(method.isAnnotationPresent(org.hswebframework.web.authorization.annotation.QueryAction.class)).isTrue();
    }

    @BeforeEach
    void setup() {
        data = mock(DeviceDataService.class);
        devices = mock(LocalDeviceInstanceService.class);
        scope = mock(ApiTokenScopeService.class);
        controller = new LegacyDeviceDataController(data, devices,
            mock(LegacyDeviceLatestValueService.class),
            (org.jetlinks.community.timescaledb.thing.counter.CounterSnapshotService) null, scope);
        when(devices.findById(anyList())).thenAnswer(call -> Flux.fromIterable(call.<List<String>>getArgument(0))
            .map(id -> {
                DeviceInstanceEntity device = new DeviceInstanceEntity();
                device.setId(id);
                device.setProductId("product-" + id);
                return device;
            }));
        when(scope.requireDevice(anyString(), anyString())).thenReturn(Mono.empty());
        DeviceMetadata metadata = mock(DeviceMetadata.class);
        when(metadata.getPropertyOrNull("p")).thenReturn(SimplePropertyMetadata.of("p", "P", new DoubleType()));
        when(metadata.getPropertyOrNull("text")).thenReturn(SimplePropertyMetadata.of("text", "Text", new StringType()));
        when(devices.getMetadata(anyString())).thenReturn(Mono.just(metadata));
        when(data.queryPropertyExtremes(anyList(), anyLong(), anyLong())).thenAnswer(call ->
            Flux.fromIterable(call.<List<PropertyExtremes.Target>>getArgument(0)).map(target ->
                PropertyExtremes.empty(target, PropertyExtremes.Status.NO_DATA)));
    }

    @Test
    void deduplicatesPreservesOrderAndClassifiesEachProperty() {
        DeviceExtremesRequest request = request();
        request.setDevices(List.of("b", "a", "b"));
        request.setProperties(List.of("missing", "p", "text", "p"));
        when(data.queryPropertyExtremes(anyList(), anyLong(), anyLong())).thenReturn(Flux.just(
            PropertyExtremes.empty(new PropertyExtremes.Target("a", "p"), PropertyExtremes.Status.UNSUPPORTED_STORAGE),
            new PropertyExtremes("b", "p", PropertyExtremes.Status.OK, -1.5, 1001L, 2.5, 1999L)));
        List<DeviceExtremesData> rows = controller.queryDeviceExtremes(request).collectList().block();
        assertThat(rows).extracting(DeviceExtremesData::deviceId).containsExactly("b", "b", "b", "a", "a", "a");
        assertThat(rows).extracting(DeviceExtremesData::status).containsExactly(
            DeviceExtremesData.Status.UNKNOWN_PROPERTY, DeviceExtremesData.Status.OK,
            DeviceExtremesData.Status.NON_NUMERIC, DeviceExtremesData.Status.UNKNOWN_PROPERTY,
            DeviceExtremesData.Status.UNSUPPORTED_STORAGE, DeviceExtremesData.Status.NON_NUMERIC);
        assertThat(rows.get(1).minTime()).isEqualTo(1001L);
        verify(data).queryPropertyExtremes(List.of(new PropertyExtremes.Target("b", "p"),
            new PropertyExtremes.Target("a", "p")), 1000, 2000);
        verify(scope).requireDevice("a", "product-a");
        verify(scope).requireDevice("b", "product-b");
        assertThat(request.getDevices()).hasSize(3);
    }

    @Test
    void missingOrUnauthorizedDeviceFailsBeforeAnyDataLookup() {
        when(scope.requireDevice("a", "product-a")).thenReturn(Mono.error(
            new ResponseStatusException(HttpStatus.FORBIDDEN)));
        StepVerifier.create(controller.queryDeviceExtremes(request()))
            .expectErrorSatisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(403))
            .verify();
        verifyNoInteractions(data);
        verify(devices, never()).getMetadata(anyString());
        when(devices.findById(anyList())).thenReturn(Flux.empty());
        StepVerifier.create(controller.queryDeviceExtremes(request()))
            .expectErrorSatisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(404))
            .verify();
    }

    @Test
    void noDataHasExplicitNullFieldsEvenWithGlobalNonNullSerialization() throws Exception {
        DeviceExtremesData row = controller.queryDeviceExtremes(request()).single().block();
        assertThat(row.status()).isEqualTo(DeviceExtremesData.Status.NO_DATA);
        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        var json = mapper.readTree(mapper.writeValueAsString(row));
        for (String field : List.of("minValue", "minTime", "maxValue", "maxTime")) {
            assertThat(json.has(field)).isTrue();
            assertThat(json.get(field).isNull()).isTrue();
        }
    }

    @Test
    void rejectsInvalidInputsBeforeAuthorization() {
        for (List<String> ids : Arrays.asList(List.<String>of(), Arrays.asList("a", null), List.of(" "))) {
            DeviceExtremesRequest request = request();
            request.setDevices(ids);
            StepVerifier.create(controller.queryDeviceExtremes(request)).expectError(BusinessException.class).verify();
        }
        for (int kind = 0; kind < 5; kind++) {
            DeviceExtremesRequest request = request();
            if (kind == 0) request.setEndTime(1000L);
            if (kind == 1) request.setStartTime(null);
            if (kind == 2) request.setDevices(IntStream.range(0, 501).mapToObj(String::valueOf).toList());
            if (kind == 3) request.setProperties(IntStream.range(0, 101).mapToObj(String::valueOf).toList());
            if (kind == 4) request.setProperties(null);
            StepVerifier.create(controller.queryDeviceExtremes(request)).expectError(BusinessException.class).verify();
        }
        verifyNoInteractions(scope, devices, data);
    }

    @Test
    void databaseFailureIsNotReportedAsPartialSuccessOrNoData() {
        when(data.queryPropertyExtremes(anyList(), anyLong(), anyLong())).thenReturn(Flux.concat(
            Flux.just(PropertyExtremes.empty(new PropertyExtremes.Target("a", "p"), PropertyExtremes.Status.NO_DATA)),
            Flux.error(new IllegalStateException("database failed"))));
        StepVerifier.create(controller.queryDeviceExtremes(request()))
            .expectErrorMessage("database failed").verify();
    }

    @Test
    void oneDeadlineIncludesAuthorizationAndCancelsUpstream() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(scope.requireDevice(anyString(), anyString())).thenReturn(Mono.<Void>never()
            .doOnCancel(() -> cancelled.set(true)));
        StepVerifier.withVirtualTime(() -> controller.queryDeviceExtremes(request()))
            .thenAwait(Duration.ofSeconds(30)).expectError(TimeoutException.class).verify();
        assertThat(cancelled).isTrue();
        verifyNoInteractions(data);
    }

    private DeviceExtremesRequest request() {
        DeviceExtremesRequest request = new DeviceExtremesRequest();
        request.setDevices(List.of("a"));
        request.setProperties(List.of("p"));
        request.setStartTime(1000L);
        request.setEndTime(2000L);
        return request;
    }
}
