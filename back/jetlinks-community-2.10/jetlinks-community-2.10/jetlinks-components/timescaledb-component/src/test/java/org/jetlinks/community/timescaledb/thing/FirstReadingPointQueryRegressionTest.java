package org.jetlinks.community.timescaledb.thing;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.community.things.data.ThingPropertyDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jetlinks.community.timescaledb.thing.CaggTestSupport.*;

class FirstReadingPointQueryRegressionTest {

    @Test
    void missingBaselineFallbackReadsEarliestSampleInsteadOfEndingSample() {
        Executor executor = new Executor();
        Query query = new Query(database(executor, "metrics"), config(), "device-1");
        executor.responder = statement -> {
            boolean ascending = statement.getSql().toLowerCase(Locale.ROOT)
                .matches("(?s).*order by.*\"timestamp\"\\s+asc.*");
            double reading = ascending ? 1_600_000D : 1_826_092.8D;
            return Flux.just(Map.of("timestamp", LocalDateTime.of(2026, 10, ascending ? 20 : 23, 10, 0),
                                    "property", "p", "numberValue", reading, "value", reading,
                                    "deviceId", "device-1"));
        };
        QueryParamEntity request = QueryParamEntity.of();
        request.and("timestamp", "gt", time("2026-10-16 00:00:00"));
        request.and("timestamp", "lt", time("2026-10-23 13:48:16"));
        request.setOrderBy("timestamp asc");
        request.doPaging(0, 1);

        ThingPropertyDetail first = query.each("metrics", request, "p").single().block();

        assertThat(first).isNotNull();
        assertThat(((Number) first.getValue()).doubleValue()).isEqualTo(1_600_000D);
        assertThat(1_826_092.8D - ((Number) first.getValue()).doubleValue()).isGreaterThan(0D);
        assertThat(executor.requests).hasSize(1);
        assertThat(executor.requests.get(0).getSql()).doesNotContain("latest_source");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void explicitAscendingOrderIsPreservedForSingleAndMultipleProperties(int propertyCount) {
        Executor executor = new Executor();
        Query query = new Query(database(executor, "metrics"), config(), "device-1");
        QueryParamEntity request = QueryParamEntity.of().doPaging(0, 1);
        request.setOrderBy("timestamp asc");

        query.each("metrics", request, propertyCount == 1 ? new String[]{"p"} : new String[]{"p", "p2"})
            .collectList().block();

        assertThat(executor.requests).hasSize(propertyCount);
        assertThat(executor.requests).allSatisfy(statement -> assertThat(statement.getSql())
            .doesNotContain("latest_source")
            .containsIgnoringCase("timestamp\" asc"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"numberValue desc", "timestamp desc,numberValue asc"})
    void otherExplicitSortsAreNotDiscarded(String order) {
        Executor executor = new Executor();
        Query query = new Query(database(executor, "metrics"), config(), "device-1");
        QueryParamEntity request = QueryParamEntity.of().doPaging(0, 1);
        request.setOrderBy(order);

        query.each("metrics", request, "p").collectList().block();

        assertThat(executor.requests).hasSize(1);
        assertThat(executor.requests.get(0).getSql())
            .doesNotContain("latest_source")
            .contains("numberValue");
    }

    @Test
    void ordinaryLatestQueriesKeepDescendingFastPath() {
        for (String order : List.of("", "timestamp desc")) {
            Executor executor = new Executor();
            Query query = new Query(database(executor, "metrics"), config(), "device-1");
            QueryParamEntity request = QueryParamEntity.of().doPaging(0, 1);
            if (!order.isEmpty()) request.setOrderBy(order);

            query.each("metrics", request, "p").collectList().block();

            assertThat(executor.requests).hasSize(1);
            assertThat(executor.requests.get(0).getSql())
                .startsWith("SELECT latest_source.*")
                .endsWith("ORDER BY \"timestamp\" DESC LIMIT 1");
        }
    }
}
