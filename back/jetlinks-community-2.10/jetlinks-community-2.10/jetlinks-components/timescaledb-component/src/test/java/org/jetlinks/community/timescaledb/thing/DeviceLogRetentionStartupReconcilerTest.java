package org.jetlinks.community.timescaledb.thing;

import org.jetlinks.community.Interval;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceLogRetentionStartupReconcilerTest {
    @Test
    void updatesDormantLogTablesWithoutTouchingBusinessOrSystemTables() {
        CaggTestSupport.Executor executor = new CaggTestSupport.Executor();
        AtomicInteger finds = new AtomicInteger();
        executor.responder = request -> {
            if (request.getSql().contains("FROM timescaledb_information.hypertables")) {
                return Flux.just(Map.of("log_table", "device_log_product1"),
                    Map.of("log_table", "device_all_log"), Map.of("log_table", "properties_product1"),
                    Map.of("log_table", "event_product1"), Map.of("log_table", "system_logger"));
            }
            if (request.getSql().startsWith("SELECT job_id")) {
                return Flux.just(Map.of("job_id", 10, "scheduled", true, "schedule_matches", true,
                    "retention_matches", finds.incrementAndGet() % 2 == 0));
            }
            return Flux.empty();
        };
        TimescaleDBThingsDataProperties config = new TimescaleDBThingsDataProperties();
        config.setLogRetentionPolicy(Interval.of("2M"));
        var database = CaggTestSupport.database(executor, "unused");
        StepVerifier.create(new DeviceLogRetentionStartupReconciler(
            database, config, "device_log_", "device_all_log").reconcile())
            .expectNext(2L).verifyComplete();

        assertThat(executor.writes).hasSize(2).allSatisfy(request -> {
            assertThat(request.getSql()).contains("alter_job");
            assertThat(request.getParameters()).containsExactly("2 months", 10);
        });
        assertThat(executor.requests.get(0).getParameters()).containsExactly("public");
        assertThat(executor.requests.stream().filter(r -> r.getSql().startsWith("SELECT job_id")))
            .allSatisfy(request -> assertThat(request.getParameters()[2])
                .isIn("device_log_product1", "device_all_log"));
    }

    @Test
    void supportsNormalizedCustomLogNamesAndRejectsUnrelatedTables() {
        assertThat(DeviceLogRetentionStartupReconciler.matches("custom_log_p1", "Custom-Log_", "all_logs")).isTrue();
        assertThat(DeviceLogRetentionStartupReconciler.matches("all_logs", "Custom-Log_", "All-Logs")).isTrue();
        assertThat(DeviceLogRetentionStartupReconciler.matches("customXlog_p1", "custom_log_", "all_logs")).isFalse();
        assertThat(DeviceLogRetentionStartupReconciler.matches("properties_p1", "", "")).isFalse();
    }

    @Test
    void absentOverrideDoesNotAccessDatabase() {
        StepVerifier.create(new DeviceLogRetentionStartupReconciler(
            null, new TimescaleDBThingsDataProperties(), "device_log_", "device_all_log").reconcile())
            .expectNext(0L).verifyComplete();
        StepVerifier.create(DeviceLogRetentionStartupReconciler.ensure(
            null, new TimescaleDBThingsDataProperties(), "device_log_p1")).verifyComplete();
    }

    @Test
    void rejectsEmptyPrefixBeforeAccessingDatabase() {
        TimescaleDBThingsDataProperties config = new TimescaleDBThingsDataProperties();
        config.setLogRetentionPolicy(Interval.of("2M"));
        StepVerifier.create(new DeviceLogRetentionStartupReconciler(null, config, "", "device_all_log").reconcile())
            .expectErrorMessage("Device log table prefix is empty").verify();
    }
}
