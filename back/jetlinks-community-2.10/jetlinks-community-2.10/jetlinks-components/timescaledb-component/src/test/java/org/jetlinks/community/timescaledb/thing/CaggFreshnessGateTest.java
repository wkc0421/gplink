package org.jetlinks.community.timescaledb.thing;

import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.jetlinks.community.things.data.AggregationRequest;
import org.jetlinks.community.things.data.PropertyAggregation;
import org.jetlinks.community.timeseries.query.Aggregation;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jetlinks.community.timescaledb.thing.CaggTestSupport.*;

class CaggFreshnessGateTest {
    @Test
    void unconfiguredPropertyKeepsCaggAndUsesOneBoundedRead() {
        Executor executor = new Executor();
        PropertyAggregation column = new PropertyAggregation("p", "total", Aggregation.SUM);

        assertThat(fresh(executor, fullDay(), ContinuousAggregateManager.Layer.DAILY,
            column)).isTrue();

        assertThat(executor.requests).hasSize(1);
        SqlRequest request = executor.requests.get(0);
        assertThat(request.getSql())
            .contains("counter_snapshot_config", "counter_snapshot_change", "counter_cagg_refresh")
            .contains("range_agg(tsrange(p.from_time,p.to_time,'[)'))")
            .contains("p.source_version>=c.source_version")
            .contains("c.enabled=true")
            .contains("c.property_id IN (?)");
        assertThat(request.getParameters()).containsExactly(
            LocalDateTime.of(2026, 9, 20, 0, 0), LocalDateTime.of(2026, 9, 19, 0, 0),
            LocalDateTime.of(2026, 9, 20, 0, 0), LocalDateTime.of(2026, 9, 19, 0, 0),
            LocalDateTime.of(2026, 9, 19, 0, 0), LocalDateTime.of(2026, 9, 20, 0, 0),
            "DAILY", LocalDateTime.of(2026, 9, 20, 0, 0),
            LocalDateTime.of(2026, 9, 19, 0, 0), "metrics", "p");
    }

    @Test
    void pendingDirtyOrMissingProofFallsBackToRawForAllColumns() {
        Executor executor = new Executor();
        executor.responder = request -> Flux.just(
            Map.of("property_id", "p", "pending", false, "proven", true),
            Map.of("property_id", "p2", "pending", false, "proven", false));
        assertThat(fresh(executor, fullDay(), ContinuousAggregateManager.Layer.DAILY,
            new PropertyAggregation("p", "first", Aggregation.SUM),
            new PropertyAggregation("p2", "second", Aggregation.COUNT))).isFalse();
        assertThat(executor.requests.get(0).getSql()).contains("c.property_id IN (?,?)");

        Executor dirty = new Executor();
        dirty.responder = request -> Flux.just(
            Map.of("property_id", "p", "pending", true, "proven", true));
        assertThat(fresh(dirty, fullDay(), ContinuousAggregateManager.Layer.DAILY,
            new PropertyAggregation("p", "total", Aggregation.SUM))).isFalse();
    }

    @Test
    void partialDayHasNoCompleteDailyBucketAndNeedsNoProofQuery() {
        Executor executor = new Executor();
        AggregationRequest partial = request("1d", "2026-09-19 08:30:00",
            "2026-09-19 23:30:00", 1);

        assertThat(fresh(executor, partial, ContinuousAggregateManager.Layer.DAILY,
            new PropertyAggregation("p", "total", Aggregation.SUM))).isTrue();
        assertThat(executor.requests).isEmpty();
    }

    @Test
    void monthlyProofCoversOnlyClosedMonthAndLeavesCurrentPartialMonthOnRaw() {
        AggregationRequest request = request("1M", "2026-08-01 00:00:00",
            "2026-09-15 12:00:00", 2);
        PropertyAggregation column = new PropertyAggregation("p", "total", Aggregation.SUM);
        AggregationWindow window = AggregationWindow.create(request, new PropertyAggregation[]{column},
            config().getAggregation());

        long[] full = TimescaleDBRowModeQueryOperations.fullCaggWindow(
            window, ContinuousAggregateManager.Layer.MONTHLY);
        assertThat(AggregationWindow.local(full[0])).isEqualTo(LocalDateTime.of(2026, 8, 1, 0, 0));
        assertThat(AggregationWindow.local(full[1])).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
    }

    private static AggregationRequest fullDay() {
        return request("1d", "2026-09-19 00:00:00", "2026-09-20 00:00:00", 2);
    }

    private static boolean fresh(Executor executor, AggregationRequest request,
                                 ContinuousAggregateManager.Layer layer,
                                 PropertyAggregation... columns) {
        TimescaleDBThingsDataProperties properties = config();
        FreshQuery query = new FreshQuery(database(executor, "metrics"), properties);
        ContinuousAggregateManager manager = new ContinuousAggregateManager(
            "public", executor, properties, "deviceId");
        AggregationWindow window = AggregationWindow.create(request, columns,
            properties.getAggregation());
        return query.fresh("metrics", window, manager, layer, columns).block();
    }

    private static class FreshQuery extends Query {
        FreshQuery(DatabaseOperator database, TimescaleDBThingsDataProperties properties) {
            super(database, properties);
        }

        Mono<Boolean> fresh(String table, AggregationWindow window,
                            ContinuousAggregateManager manager,
                            ContinuousAggregateManager.Layer layer,
                            PropertyAggregation... columns) {
            return configuredCaggFresh(table, new AggregationContext(metadata, columns),
                window, manager, layer);
        }
    }
}
