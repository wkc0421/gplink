package org.jetlinks.community.timescaledb.thing;

import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.jetlinks.community.things.data.PropertyAggregation;
import org.jetlinks.community.things.data.PropertyBoundaryValues;
import org.jetlinks.community.timeseries.TimeSeriesData;
import org.jetlinks.community.timeseries.query.Aggregation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jetlinks.community.timescaledb.thing.CaggTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PointQueryOptimizationTest {

    @AfterEach
    void clearCache() {
        ContinuousAggregateManager.clearReadyCache();
    }

    @Test
    void latestPropertiesWrapsOneSourceWithDistinctOn() {
        SqlRequest source = SqlRequests.of(
            "SELECT * FROM metrics WHERE \"deviceId\"=? AND property IN (?,?)",
            "d1", "p1", "p2");

        SqlRequest result = TimescaleDBRowModeQueryOperations.latestPropertiesSql(source);

        assertThat(result.getSql())
            .startsWith("SELECT DISTINCT ON (\"property\")")
            .contains("ORDER BY \"property\",\"timestamp\" DESC");
        assertThat(result.getParameters()).containsExactly("d1", "p1", "p2");
    }

    @Test
    void latestFastPathRequiresFirstPageWithExactlyOneRow() {
        assertThat(TimescaleDBRowModeQueryOperations.isLatestPointQuery(
            QueryParamEntity.of().doPaging(0, 1))).isTrue();
        assertThat(TimescaleDBRowModeQueryOperations.isLatestPointQuery(
            QueryParamEntity.of().doPaging(0, 2))).isFalse();
        assertThat(TimescaleDBRowModeQueryOperations.isLatestPointQuery(
            QueryParamEntity.of().doPaging(1, 1))).isFalse();
        assertThat(TimescaleDBRowModeQueryOperations.isLatestPointQuery(
            QueryParamEntity.of().noPaging())).isFalse();
    }

    @Test
    void distinctOnDispatchPreservesOtherPerPropertyPagingContracts() {
        Executor executor = new Executor();
        Query query = new Query(database(executor, "metrics"), config(), "device-1");

        query.each("metrics", QueryParamEntity.of().doPaging(0, 1), "p", "p2")
            .collectList().block();
        assertThat(executor.requests).hasSize(1);
        assertThat(executor.requests.get(0).getSql())
            .startsWith("SELECT DISTINCT ON (\"property\")");

        for (QueryParamEntity parameter : Arrays.asList(
            QueryParamEntity.of().doPaging(0, 2),
            QueryParamEntity.of().doPaging(1, 1),
            QueryParamEntity.of().noPaging())) {
            executor.requests.clear();
            query.each("metrics", parameter, "p", "p2").collectList().block();
            assertThat(executor.requests).hasSize(2);
            assertThat(executor.requests)
                .allSatisfy(request -> assertThat(request.getSql())
                    .doesNotStartWith("SELECT DISTINCT ON (\"property\")"));
        }
    }

    @Test
    void singleLatestUsesDescendingIndexPointShape() {
        SqlRequest source = SqlRequests.of(
            "SELECT * FROM metrics WHERE \"deviceId\"=? AND property=?",
            "d1", "p1");

        SqlRequest result = TimescaleDBRowModeQueryOperations.latestPropertySql(source);

        assertThat(result.getSql())
            .startsWith("SELECT latest_source.*")
            .endsWith("ORDER BY \"timestamp\" DESC LIMIT 1");
        assertThat(result.getParameters()).containsExactly("d1", "p1");

        Executor executor = new Executor();
        Query query = new Query(database(executor, "metrics"), config(), "device-1");
        query.latest("metrics", QueryParamEntity.of().doPaging(0, 1)).collectList().block();
        assertThat(executor.requests).hasSize(1);
        assertThat(executor.requests.get(0).getSql())
            .endsWith("ORDER BY \"timestamp\" DESC LIMIT 1");
    }

    @Test
    void boundaryQueryUsesOneCteAndIndexedLateralLookup() {
        Executor executor = new Executor();
        Query query = new Query(database(executor, "metrics"), config(), "device-1");
        long start = time("2026-09-01 00:00:00");
        long end = time("2026-09-01 02:00:00");

        SqlRequest request = query.boundaryValuesSql(
            "metrics", "p", start, end, Arrays.asList(start, start + 3_600_000L, end));

        assertThat(request.getSql())
            .contains("WITH baseline AS", "first_in_range AS", "first_after_range AS", "initial_value AS")
            .contains("unnest(ARRAY[", "WITH ORDINALITY", "LEFT JOIN LATERAL")
            .contains("\"timestamp\"<=requested.boundary")
            .contains("ORDER BY \"timestamp\" DESC LIMIT 1")
            .contains("\"timestamp\">?::timestamp AND \"timestamp\"<=?::timestamp")
            .contains("CASE WHEN requested.boundary=?::timestamp", "point.\"timestamp\"<?::timestamp")
            .contains("THEN first_after_range.\"value\"")
            .contains("\"public\".\"metrics\"");
        assertThat(request.getSql().chars().filter(ch -> ch == '?').count())
            .isEqualTo(request.getParameters().length);
        assertThat(request.getParameters()).contains(
            LocalDateTime.of(2026, 9, 1, 0, 0),
            LocalDateTime.of(2026, 9, 1, 2, 0));
    }

    @Test
    void boundaryQueryReturnsOrderedTypedValuesWithOneSql() {
        Executor executor = new Executor();
        executor.responder = request -> Flux.just(
            Map.of("_ordinal", 1L, "_boundary_value", "1.5", "_initial_value", "1.0"),
            Map.of("_ordinal", 2L, "_boundary_value", "2.5", "_initial_value", "1.0"));
        Query query = new Query(database(executor, "metrics"), config(), "device-1");
        long start = time("2026-09-01 00:00:00");
        long end = time("2026-09-01 01:00:00");

        PropertyBoundaryValues result = query
            .boundaries("metrics", "p", start, end, List.of(start, end))
            .block();

        assertThat(executor.requests).hasSize(1);
        assertThat(result).isNotNull();
        assertThat(((Number) result.getInitialValue()).doubleValue()).isEqualTo(1.0D);
        assertThat(result.getValues())
            .extracting(value -> ((Number) value.getValue()).doubleValue())
            .containsExactly(1.5D, 2.5D);
    }

    @Test
    void boundaryPointQueryCarriesDeadlineAndCancelsOnReactiveTimeout() {
        Executor executor = new Executor();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Duration> deadline = new AtomicReference<>();
        executor.responder = request -> Flux.deferContextual(context -> {
            deadline.set(context.get(CancellableAggregationExecutor.TIMEOUT_CONTEXT));
            return Flux.<Map<String, Object>>never().doOnCancel(() -> cancelled.set(true));
        });
        TimescaleDBThingsDataProperties properties = config();
        properties.getAggregation().setTimeout(Duration.ofMillis(50));
        Query query = new Query(database(executor, "metrics"), properties, "device-1");
        long start = time("2026-09-01 00:00:00");
        long end = time("2026-09-01 01:00:00");

        RuntimeException error = assertThrows(RuntimeException.class, () -> query
            .boundaries("metrics", "p", start, end, List.of(start, end))
            .block(Duration.ofSeconds(5)));

        assertThat(error).hasRootCauseInstanceOf(TimeoutException.class);
        assertThat(deadline.get()).isEqualTo(Duration.ofMillis(50));
        assertThat(cancelled).isTrue();
    }

    @Test
    void ordinaryRowQueryStreamsThroughTheDeadlineAwareExecutor() {
        Executor executor = new Executor();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Duration> deadline = new AtomicReference<>();
        LocalDateTime timestamp = LocalDateTime.of(2026, 9, 1, 0, 0);
        executor.responder = request -> Flux.deferContextual(context -> {
            deadline.set(context.get(CancellableAggregationExecutor.TIMEOUT_CONTEXT));
            return Flux.concat(
                    Flux.just(Map.<String, Object>of(
                        "timestamp", timestamp, "property", "p", "value", "1")),
                    Flux.<Map<String, Object>>never())
                .doOnCancel(() -> cancelled.set(true));
        });
        TimescaleDBThingsDataProperties properties = config();
        properties.getAggregation().setTimeout(Duration.ofSeconds(3));
        Query query = new Query(database(executor, "metrics"), properties, "device-1");

        org.jetlinks.community.timeseries.TimeSeriesData first = query
            .regular("metrics", QueryParamEntity.of().noPaging())
            .blockFirst(Duration.ofSeconds(1));

        assertThat(first).isNotNull();
        assertThat(first.getTimestamp()).isEqualTo(time("2026-09-01 00:00:00"));
        assertThat(deadline.get()).isEqualTo(Duration.ofSeconds(3));
        assertThat(cancelled).isTrue();
        assertThat(executor.requests).hasSize(1);
        assertThat(executor.requests.get(0).getSql())
            .containsIgnoringCase("from \"public\".metrics")
            .contains("deviceId");
    }

    @Test
    void ordinaryRowQueryUsesOneTotalDeadlineEvenWhileRowsKeepArriving() throws InterruptedException {
        Executor executor = new Executor();
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicInteger emitted = new AtomicInteger();
        AtomicReference<Duration> deadline = new AtomicReference<>();
        executor.responder = request -> Flux.deferContextual(context -> {
            deadline.set(context.get(CancellableAggregationExecutor.TIMEOUT_CONTEXT));
            return Flux.interval(Duration.ZERO, Duration.ofMillis(5))
                .map(index -> {
                    emitted.incrementAndGet();
                    return Map.<String, Object>of(
                        "timestamp", LocalDateTime.of(2026, 9, 1, 0, 0).plusNanos(index),
                        "property", "p",
                        "value", index);
                })
                .doOnCancel(cancelled::countDown);
        });
        TimescaleDBThingsDataProperties properties = config();
        properties.getAggregation().setTimeout(Duration.ofMillis(75));
        Query query = new Query(database(executor, "metrics"), properties, "device-1");

        RuntimeException error = assertThrows(RuntimeException.class, () -> query
            .regular("metrics", QueryParamEntity.of().noPaging())
            .collectList()
            .block(Duration.ofSeconds(5)));

        assertThat(error).hasRootCauseInstanceOf(TimeoutException.class);
        assertThat(error).hasRootCauseMessage("TimescaleDB query exceeded 75ms");
        assertThat(emitted.get()).isGreaterThan(1);
        assertThat(deadline.get()).isEqualTo(Duration.ofMillis(75));
        assertThat(cancelled.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void pagedRowQueryRunsCountAndPageThroughOneDeadlineAwareExecutor() {
        Executor executor = new Executor();
        List<Duration> deadlines = new CopyOnWriteArrayList<>();
        executor.responder = request -> Flux.deferContextual(context -> {
            deadlines.add(context.get(CancellableAggregationExecutor.TIMEOUT_CONTEXT));
            if (request.getSql().startsWith("SELECT COUNT(1) AS _total")) {
                return Flux.just(Map.<String, Object>of("_total", 2L));
            }
            return Flux.just(Map.<String, Object>of(
                "timestamp", LocalDateTime.of(2026, 9, 1, 0, 0),
                "property", "p",
                "value", "1"));
        });
        TimescaleDBThingsDataProperties properties = config();
        properties.getAggregation().setTimeout(Duration.ofSeconds(3));
        Query query = new Query(database(executor, "metrics"), properties, "device-1");

        PagerResult<TimeSeriesData> result = query
            .page("metrics", QueryParamEntity.of().doPaging(0, 1))
            .block(Duration.ofSeconds(1));

        assertThat(result).isNotNull();
        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getPageIndex()).isZero();
        assertThat(result.getPageSize()).isEqualTo(1);
        assertThat(result.getData()).hasSize(1);
        assertThat(executor.requests).hasSize(2);
        assertThat(executor.requests.get(0).getSql())
            .startsWith("SELECT COUNT(1) AS _total FROM (")
            .doesNotContain(" ORDER BY ", " LIMIT ", " OFFSET ");
        assertThat(executor.requests.get(1).getSql())
            .containsIgnoringCase("limit ? offset ?");
        assertThat(executor.requests.get(1).getParameters()).endsWith(1, 0);
        assertThat(deadlines).containsExactly(Duration.ofSeconds(3), Duration.ofSeconds(3));
    }

    @Test
    void pagedRowQueryWithKnownTotalSkipsCountButKeepsPagingContract() {
        Executor executor = new Executor();
        executor.responder = request -> Flux.just(Map.<String, Object>of(
            "timestamp", LocalDateTime.of(2026, 9, 1, 0, 0),
            "property", "p",
            "value", "1"));
        QueryParamEntity parameter = QueryParamEntity.of().doPaging(4, 10);
        parameter.setTotal(11);
        Query query = new Query(database(executor, "metrics"), config(), "device-1");

        PagerResult<TimeSeriesData> result = query.page("metrics", parameter).block(Duration.ofSeconds(1));

        assertThat(result).isNotNull();
        assertThat(result.getTotal()).isEqualTo(11);
        assertThat(result.getPageIndex()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(10);
        assertThat(executor.requests).hasSize(1);
        assertThat(executor.requests.get(0).getSql())
            .doesNotContain("COUNT(1)")
            .containsIgnoringCase("limit ? offset ?");
        assertThat(executor.requests.get(0).getParameters()).endsWith(10, 10);
    }

    @Test
    void caggSqlSeparatesDataAndTimescaleExtensionSchemas() {
        ContinuousAggregateManager manager = new ContinuousAggregateManager(
            "telemetry", new Executor(), config(), "deviceId");

        String sql = manager.createSql(
            "metrics", ContinuousAggregateManager.Layer.HOURLY, "timescale_api");

        assertThat(sql)
            .contains("CREATE MATERIALIZED VIEW \"telemetry\".\"metrics_hourly_agg\"")
            .contains("\"timescale_api\".\"time_bucket\"(")
            .contains("\"timescale_api\".\"first\"(")
            .contains("\"timescale_api\".\"last\"(")
            .contains("FROM \"telemetry\".\"metrics\"")
            .doesNotContain("\"telemetry\".\"time_bucket\"");
        assertThat(ContinuousAggregateManager.function("timescale_api", "alter_job"))
            .isEqualTo("\"timescale_api\".\"alter_job\"");
    }

    @Test
    void caggEnsureResolvesExtensionSchemaFromCatalog() {
        Executor executor = new Executor();
        executor.functionSchema = "timescale_api";
        TimescaleDBThingsDataProperties properties = config();
        properties.setRetentionPolicy(null);
        ContinuousAggregateManager manager = new ContinuousAggregateManager(
            "telemetry", executor, properties, "deviceId");

        manager.ensure("metrics").block();

        assertThat(executor.extensionSchemaRequests).hasSize(1);
        assertThat(executor.extensionSchemaRequests.get(0).getSql())
            .contains("FROM pg_extension", "e.extname='timescaledb'");
        assertThat(executor.writes).hasSize(1);
        assertThat(executor.writes.get(0).getSql())
            .contains("\"timescale_api\".\"time_bucket\"(")
            .contains("FROM \"telemetry\".\"metrics\"")
            .doesNotContain("\"telemetry\".\"time_bucket\"");
    }

    @Test
    void extensionSchemaLookupIsSharedAndFailuresAreNotCached() {
        Executor executor = new Executor();
        executor.functionSchema = "timescale_api";
        ContinuousAggregateManager manager = new ContinuousAggregateManager(
            "telemetry", executor, config(), "deviceId");

        List<String> schemas = Flux.range(0, 16)
            .flatMap(ignore -> manager.functionSchema())
            .collectList()
            .block();

        assertThat(schemas).hasSize(16).containsOnly("timescale_api");
        assertThat(manager.functionSchema().block()).isEqualTo("timescale_api");
        assertThat(executor.extensionSchemaRequests).hasSize(1);

        Executor recovering = new Executor();
        recovering.functionSchemaError = new IllegalStateException("catalog unavailable");
        ContinuousAggregateManager recoveringManager = new ContinuousAggregateManager(
            "telemetry", recovering, config(), "deviceId");
        assertThrows(RuntimeException.class, () -> recoveringManager.functionSchema().block());
        recovering.functionSchemaError = null;

        assertThat(recoveringManager.functionSchema().block()).isEqualTo("public");
        assertThat(recovering.extensionSchemaRequests).hasSize(2);
    }

    @Test
    void readyResultIsCachedAndCanBeInvalidated() {
        Executor executor = new Executor();
        ContinuousAggregateManager manager = new ContinuousAggregateManager(
            "public", executor, config(), "deviceId");

        assertThat(manager.ready("metrics", ContinuousAggregateManager.Layer.HOURLY).block()).isFalse();
        assertThat(manager.ready("metrics", ContinuousAggregateManager.Layer.HOURLY).block()).isFalse();
        assertThat(executor.requests).hasSize(1);

        manager.invalidate("metrics");
        assertThat(manager.ready("metrics", ContinuousAggregateManager.Layer.HOURLY).block()).isFalse();
        assertThat(executor.requests).hasSize(2);
    }

    @Test
    void stateProjectionReadsOnlyRequestedAggregateState() {
        TimescaleDBRowModeQueryOperations.StateProjection average =
            TimescaleDBRowModeQueryOperations.StateProjection.of(new PropertyAggregation[]{
                new PropertyAggregation("p", "p", Aggregation.AVG)
            });
        assertThat(average.caggColumns("deviceId"))
            .contains("sum_value", "numeric_count")
            .doesNotContain("min_value", "max_value", "sample_count", "first_value", "last_value");

        TimescaleDBRowModeQueryOperations.StateProjection count =
            TimescaleDBRowModeQueryOperations.StateProjection.of(new PropertyAggregation[]{
                new PropertyAggregation("p", "p", Aggregation.COUNT)
            });
        assertThat(count.rawColumns("deviceId", "timescale_api"))
            .isEqualTo(",count(*) AS sample_count");
    }
}
