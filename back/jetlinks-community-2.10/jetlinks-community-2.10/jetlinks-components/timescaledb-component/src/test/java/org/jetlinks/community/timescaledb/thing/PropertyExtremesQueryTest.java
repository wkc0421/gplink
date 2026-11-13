package org.jetlinks.community.timescaledb.thing;

import org.jetlinks.community.things.data.PropertyExtremes;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class PropertyExtremesQueryTest {
    @Test
    void propagatesServerDeadlineAndCancelsSqlOnTimeout() {
        var executor = new CaggTestSupport.Executor();
        var cancelled = new AtomicBoolean();
        var contextSeen = new AtomicBoolean();
        executor.responder = ignored -> Flux.deferContextual(context -> {
            assertThat(context.<Duration>get(CancellableAggregationExecutor.TIMEOUT_CONTEXT)).isEqualTo(Duration.ofSeconds(1));
            contextSeen.set(true);
            return Flux.<java.util.Map<String, Object>>never().doOnCancel(() -> cancelled.set(true));
        });
        var config = CaggTestSupport.config();
        config.getAggregation().setTimeout(Duration.ofSeconds(1));
        var query = new CaggTestSupport.Query(CaggTestSupport.database(executor, "device_properties_test"), config, "a")
            .propertyExtremesQuery().orElseThrow();
        StepVerifier.withVirtualTime(() -> query.query(List.of(new PropertyExtremes.Target("a", "p")), 1, 2))
            .thenAwait(Duration.ofSeconds(1)).expectError(TimeoutException.class).verify();
        assertThat(contextSeen).isTrue();
        assertThat(cancelled).isTrue();
    }
    @Test
    void bindsValuesQuotesIdentifiersAndUsesBoundedTimestampLookups() {
        var query = new TimescaleDBPropertyExtremesQuery(new Object(), "schema\"name", "table\"name", "deviceId",
            ignored -> Flux.empty());
        var sql = query.statement(List.of(new PropertyExtremes.Target("a' OR true --", "p'")), 1000, 2000);
        assertThat(sql.getSql()).contains("\"schema\"\"name\".\"table\"\"name\"", "min(d.\"numberValue\")",
            "max(d.\"numberValue\")", "LEFT JOIN LATERAL", "ORDER BY d.\"timestamp\" ASC LIMIT 1")
            .doesNotContain("a' OR true", "p'", "array_agg", "time_bucket");
        assertThat(sql.getParameters()).contains("a' OR true --", "p'").hasSize(8);
    }

    @Test
    void resolvesActualPhysicalTableAndDatabaseAsBatchKey() {
        var db = CaggTestSupport.database(new CaggTestSupport.Executor(), "device_properties_test");
        var first = new CaggTestSupport.Query(db, CaggTestSupport.config(), "a").propertyExtremesQuery().orElseThrow();
        var second = new CaggTestSupport.Query(db, CaggTestSupport.config(), "b").propertyExtremesQuery().orElseThrow();
        var other = new CaggTestSupport.Query(CaggTestSupport.database(new CaggTestSupport.Executor(), "device_properties_test"),
            CaggTestSupport.config(), "a").propertyExtremesQuery().orElseThrow();
        assertThat(first.storageKey()).isEqualTo(second.storageKey()).isNotEqualTo(other.storageKey());
    }
}
