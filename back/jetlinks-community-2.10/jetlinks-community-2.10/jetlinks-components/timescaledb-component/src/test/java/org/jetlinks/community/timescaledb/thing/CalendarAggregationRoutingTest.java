package org.jetlinks.community.timescaledb.thing;

import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.jetlinks.community.things.data.PropertyAggregation;
import org.jetlinks.community.timeseries.query.Aggregation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.DigestUtils;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jetlinks.community.timescaledb.thing.CaggTestSupport.*;
import static org.jetlinks.community.timescaledb.thing.ContinuousAggregateManager.Layer;

class CalendarAggregationRoutingTest {
    @ParameterizedTest
    @CsvSource({
        "1w,NONE,DAILY", "1w,DAILY,HOURLY",
        "1q,NONE,MONTHLY", "1q,MONTHLY,DAILY", "1q,DAILY,HOURLY"
    })
    void prefersCoarsestReadyCaggAndFallsBackToCompatibleLowerLayer(String interval, String unavailable, Layer expected) {
        Fixture fixture = new Fixture(unavailable, false, true);
        SqlRequest sql = fixture.query(interval);
        assertThat(sql.getSql()).contains("FROM \"public\".\"metrics" + expected.suffix + "\"", "UNION ALL");
        for (Layer other : Layer.values()) {
            if (other != expected) assertThat(sql.getSql()).doesNotContain("metrics" + other.suffix);
        }
        String width = interval.equals("1w") ? "1 weeks" : "3 months";
        String origin = interval.equals("1w") ? "2000-01-03" : "2000-01-01";
        assertThat(sql.getSql()).contains("INTERVAL '" + width + "'", "TIMESTAMP '" + origin + " 00:00:00'");
        // The first/last raw fragments retain the exact request bounds, not the enclosing natural period.
        assertThat(sql.getParameters()).contains(LocalDateTime.of(2024, 2, 29, 12, 30),
            LocalDateTime.of(2024, 7, 3, 18, 45, 0, 1_000_000));
        assertThat(sql.getSql().chars().filter(c -> c == '?').count()).isEqualTo(sql.getParameters().length);
        SqlRequest proof = fixture.executor.requests.stream()
            .filter(request -> request.getSql().contains("counter_snapshot_config")).findFirst().orElseThrow();
        assertThat(proof.getParameters()).contains(expected.name());
        assertThat(fixture.executor.writes).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"1w,true,true", "1w,false,false", "1q,true,true", "1q,false,false"})
    void pendingChangesOrMissingFreshnessProofUseRaw(String interval, boolean pending, boolean proven) {
        Fixture fixture = new Fixture("NONE", pending, proven);
        assertThat(fixture.query(interval).getSql())
            .doesNotContain("metrics_daily_agg", "metrics_monthly_agg", "metrics_hourly_agg", "UNION ALL");
        assertThat(fixture.executor.requests).anySatisfy(request ->
            assertThat(request.getSql()).contains("counter_snapshot_config"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1w", "1q"})
    void missingAllCompatibleViewsUsesRaw(String interval) {
        Fixture fixture = new Fixture("HOURLY", false, true);
        assertThat(fixture.query(interval).getSql())
            .doesNotContain("metrics_daily_agg", "metrics_monthly_agg", "metrics_hourly_agg");
    }

    private static final class Fixture {
        final Executor executor = new Executor();
        final TimescaleDBThingsDataProperties properties = config();

        Fixture(String unavailable, boolean pending, boolean proven) {
            // Fixed historical dates exercise leap years without eventually aging out of this routing test.
            properties.getCagg().setRetention(null);
            ContinuousAggregateManager manager = new ContinuousAggregateManager("public", executor, properties, "deviceId");
            executor.responder = request -> {
                String sql = request.getSql();
                if (sql.contains("AS columns")) {
                    Object[] parameters = request.getParameters();
                    String view = parameters[parameters.length - 1].toString();
                    Layer layer = Arrays.stream(Layer.values()).filter(candidate -> view.endsWith(candidate.suffix))
                        .findFirst().orElseThrow();
                    if (layer.name().equals(unavailable)) return Flux.empty();
                    String marker = "gplink:cagg:v3:shanghai:" + DigestUtils.md5DigestAsHex(
                        manager.createSql("metrics", layer, "__timescaledb_extension__").getBytes(StandardCharsets.UTF_8));
                    return Flux.just(Map.of("version", marker, "columns", 12));
                }
                if (sql.contains("AS ready")) return Flux.just(Map.of("ready", true));
                if (sql.contains("counter_snapshot_config")) {
                    return Flux.just(Map.of("property_id", "p", "pending", pending, "proven", proven));
                }
                return Flux.empty();
            };
        }

        SqlRequest query(String interval) {
            new Query(database(executor, "metrics"), properties).aggregate("metrics",
                request(interval, "2024-02-29 12:30:00", "2024-07-03 18:45:00", 100),
                new PropertyAggregation("p", "sum", Aggregation.SUM),
                new PropertyAggregation("p", "count", Aggregation.COUNT),
                new PropertyAggregation("p", "avg", Aggregation.AVG),
                new PropertyAggregation("p", "first", Aggregation.FIRST),
                new PropertyAggregation("p", "last", Aggregation.LAST)).collectList().block();
            return executor.requests.get(executor.requests.size() - 1);
        }
    }
}
