package org.jetlinks.community.things.data.operations;

import org.jetlinks.community.things.data.PropertyBoundaryValues;
import org.jetlinks.community.things.data.ThingPropertyDetail;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.core.metadata.PropertyMetadata;
import org.jetlinks.core.things.ThingMetadata;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PropertyBoundaryQueryTest {

    @Test
    void foldsOneDescendingStreamAndUsesFirstInRangeWithoutBaseline() {
        PropertyBoundaryValues result = AbstractQueryOperations.foldBoundaryValues(
            "p", 100, 300, Arrays.asList(100L, 200L, 300L),
            Flux.just(value(250, "v3"), value(180, "v2"), value(120, "v1")))
            .block();

        assertThat(result).isNotNull();
        assertThat(result.getInitialValue()).isEqualTo("v1");
        assertThat(result.getValues())
            .extracting(PropertyBoundaryValues.BoundaryValue::getValue)
            .containsExactly(null, "v2", "v3");
    }

    @Test
    void includesExactBoundaryAndCancelsAfterBaseline() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Flux<ThingPropertyDetail> rows = Flux
            .just(value(250, "v3"), value(100, "baseline"))
            .concatWith(Flux.never())
            .doOnCancel(() -> cancelled.set(true));

        PropertyBoundaryValues result = AbstractQueryOperations.foldBoundaryValues(
            "p", 100, 300, List.of(100L, 300L), rows).block();

        assertThat(result).isNotNull();
        assertThat(result.getInitialValue()).isEqualTo("baseline");
        assertThat(result.getValues())
            .extracting(PropertyBoundaryValues.BoundaryValue::getValue)
            .containsExactly("baseline", "v3");
        // takeUntil stops the database stream as soon as <= startTime is known.
        assertThat(cancelled).isTrue();
    }

    @Test
    void emptyRangeUsesPreviousStartAndFirstFollowingEndOnlyAtOuterEdges() {
        AbstractQueryOperations query = mock(AbstractQueryOperations.class, CALLS_REAL_METHODS);
        PropertyMetadata property = mock(PropertyMetadata.class);
        when(property.getId()).thenReturn("p");
        ThingMetadata metadata = mock(ThingMetadata.class);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(query.queryProperty(any(QueryParamEntity.class), eq(metadata), anyMap()))
            .thenReturn(Flux.just(value(90, "1718469.63")),
                Flux.just(value(310, null), value(350, "1718479.63"))
                    .concatWith(Flux.never()).doOnCancel(() -> cancelled.set(true)));

        PropertyBoundaryValues result = query.queryPropertyAtBoundaries(
            "metrics", 100, 300, List.of(100L, 200L, 300L), metadata, property).block();

        assertThat(result.getInitialValue()).isEqualTo("1718469.63");
        assertThat(result.getValues()).extracting(PropertyBoundaryValues.BoundaryValue::getValue)
            .containsExactly("1718469.63", "1718469.63", "1718479.63");
        assertThat(cancelled).isTrue();
        verify(query, times(2)).queryProperty(any(QueryParamEntity.class), eq(metadata), anyMap());
    }

    @Test
    void unavailableFollowingReadingKeepsEndNullInsteadOfCopyingBaseline() {
        PropertyBoundaryValues result = AbstractQueryOperations.foldBoundaryValues(
            "p", 100, 300, List.of(100L, 200L, 300L),
            Flux.just(value(90, "10"))).block();

        assertThat(result.getInitialValue()).isEqualTo("10");
        assertThat(result.getValues()).extracting(PropertyBoundaryValues.BoundaryValue::getValue)
            .containsExactly("10", "10", null);
    }

    @Test
    void inRangeEndDoesNotReadOrUseFollowingValue() {
        AbstractQueryOperations query = mock(AbstractQueryOperations.class, CALLS_REAL_METHODS);
        PropertyMetadata property = mock(PropertyMetadata.class);
        when(property.getId()).thenReturn("p");
        ThingMetadata metadata = mock(ThingMetadata.class);
        when(query.queryProperty(any(QueryParamEntity.class), eq(metadata), anyMap()))
            .thenReturn(Flux.just(value(250, 0), value(90, 10)));

        PropertyBoundaryValues result = query.queryPropertyAtBoundaries(
            "metrics", 100, 300, List.of(100L, 300L), metadata, property).block();

        assertThat(result.getValues().get(1).getValue()).isEqualTo(0);
        verify(query).queryProperty(any(QueryParamEntity.class), eq(metadata), anyMap());
    }

    private static ThingPropertyDetail value(long timestamp, Object value) {
        ThingPropertyDetail detail = new ThingPropertyDetail();
        detail.setTimestamp(timestamp);
        detail.setProperty("p");
        detail.setValue(value);
        return detail;
    }
}
