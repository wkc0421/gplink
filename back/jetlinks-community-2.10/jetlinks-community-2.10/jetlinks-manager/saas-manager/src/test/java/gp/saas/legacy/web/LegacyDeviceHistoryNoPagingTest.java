package gp.saas.legacy.web;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.core.param.TermType;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.community.device.entity.DeviceProperty;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.core.metadata.DeviceMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyDeviceHistoryNoPagingTest {

    private static final long THREE_DAYS = 3L * 24 * 60 * 60 * 1000;

    private DeviceDataService dataService;
    private LegacyDeviceDataController controller;

    @BeforeEach
    void setUp() {
        dataService = mock(DeviceDataService.class);
        LocalDeviceInstanceService deviceService = mock(LocalDeviceInstanceService.class);
        when(deviceService.getMetadata("d1")).thenReturn(Mono.just(mock(DeviceMetadata.class)));
        controller = new LegacyDeviceDataController(dataService, deviceService, null);
    }

    @Test
    void exactThreeDayRangeUsesRawHistory() {
        long start = 1_700_000_000_000L;
        long end = start + THREE_DAYS;
        QueryParamEntity query = range(start, end);
        DeviceProperty property = property(start, 12);
        when(dataService.queryProperty(eq("d1"), any(QueryParamEntity.class), eq("p1")))
            .thenReturn(Flux.just(property));

        List<DeviceProperty> result = controller
            .queryPropertyNoPaging("d1", "p1", Collections.singletonMap("query", query))
            .collectList()
            .block();

        assertThat(result).containsExactly(property);
        verify(dataService).queryProperty(eq("d1"), any(QueryParamEntity.class), eq("p1"));
        verify(dataService, never()).aggregationPropertiesByDevice(
            anyString(), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class));
    }

    @Test
    void timestampCombinedWithOrIsRejectedAsBadRequest() {
        QueryParamEntity query = range(1_700_000_000_000L, 1_700_400_000_000L);
        query.and("value", TermType.gt, 10);
        query.getTerms().get(1).setType(Term.Type.or);

        assertBadRequest(query, "OR conditions");
    }

    @Test
    void nestedTimestampConditionIsRejectedAsBadRequest() {
        Term group = new Term();
        group.setTerms(Collections.singletonList(
            Term.of("timestamp", "btw", Arrays.asList(1_700_000_000_000L, 1_700_400_000_000L))));
        QueryParamEntity query = QueryParamEntity.of();
        query.setTerms(Collections.singletonList(group));

        assertBadRequest(query, "Nested timestamp filters");
    }

    @Test
    void unsupportedTimestampConditionIsRejectedAsBadRequest() {
        QueryParamEntity query = QueryParamEntity.of();
        query.and("timestamp", TermType.eq, 1_700_000_000_000L);

        assertBadRequest(query, "Unsupported timestamp condition");
    }

    @Test
    void incompleteTimestampRangeIsRejectedAsBadRequest() {
        QueryParamEntity query = QueryParamEntity.of();
        query.and("timestamp", TermType.gte, 1_700_000_000_000L);

        assertBadRequest(query, "both start and end bounds");
    }

    @Test
    void rawHistoryHasOneTotalThirtySecondDeadlineAndPropagatesCancellation() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(dataService.queryProperty(eq("d1"), any(QueryParamEntity.class), eq("p1")))
            .thenReturn(Flux.<DeviceProperty>never().doOnCancel(() -> cancelled.set(true)));

        StepVerifier.withVirtualTime(() -> controller
                .queryPropertyNoPagingTest("d1", "p1", QueryParamEntity.of())
                .collectList())
            .thenAwait(Duration.ofSeconds(30))
            .expectError(TimeoutException.class)
            .verify();

        assertThat(cancelled).isTrue();
    }

    private void assertBadRequest(QueryParamEntity query, String message) {
        assertThatThrownBy(() -> controller
            .queryPropertyNoPaging("d1", "p1", Map.of("query", query))
            .collectList()
            .block())
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(message)
            .extracting("status")
            .isEqualTo(400);
        verify(dataService, never()).queryProperty(anyString(), any(QueryParamEntity.class), anyString());
        verify(dataService, never()).aggregationPropertiesByDevice(
            anyString(), any(DeviceDataService.AggregationRequest.class),
            any(DeviceDataService.DevicePropertyAggregation[].class));
    }

    private QueryParamEntity range(long start, long end) {
        QueryParamEntity query = QueryParamEntity.of();
        query.and("timestamp", TermType.btw, Arrays.asList(start, end));
        return query;
    }

    private DeviceProperty property(long timestamp, Object value) {
        DeviceProperty property = new DeviceProperty();
        property.setDeviceId("d1");
        property.setProperty("p1");
        property.setTimestamp(timestamp);
        property.setCreateTime(timestamp);
        property.setValue(value);
        return property;
    }
}
