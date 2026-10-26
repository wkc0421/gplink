package org.jetlinks.community.timescaledb.thing.counter;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.executor.reactive.ReactiveSqlExecutor;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrapper;
import org.hswebframework.ezorm.rdb.metadata.RDBDatabaseMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBSchemaMetadata;
import org.hswebframework.ezorm.rdb.metadata.dialect.Dialect;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.jetlinks.community.timescaledb.TimescaleDBOperations;
import org.jetlinks.community.timescaledb.metadata.TimescaleDBDialectProvider;
import org.jetlinks.community.timescaledb.thing.TimescaleDBThingsDataProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Publisher;
import org.springframework.transaction.reactive.TransactionCallback;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

class CounterSnapshotServiceStateTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime BOUNDARY = LocalDateTime.of(2026, 9, 23, 0, 0);

    @ParameterizedTest
    @CsvSource({"week,VALID", "quarter,VALID", "week,NO_VALUE", "quarter,NO_VALUE"})
    void calendarBoundariesPreferDailySnapshotsIncludingKnownEmptyReadings(String type, String state) throws Exception {
        List<LocalDateTime> times = calendarTimes(type);
        RecordingExecutor executor = new RecordingExecutor();
        executor.responder = request -> {
            if (request.getSql().startsWith("SELECT * FROM")) return Flux.just(calendarConfig());
            if (request.getSql().contains("JOIN \"public\".\"counter_snapshot_day\"")) {
                return Flux.fromIterable(times).map(time -> snapshotRow(time, state));
            }
            return Flux.error(new AssertionError("Daily snapshots should resolve every boundary: " + request.getSql()));
        };
        Map<Long, CounterBoundary> result = calendarService(executor).resolveBoundaries(
            "product-1", "energy", List.of("d1"), times.stream().map(CounterSnapshotServiceStateTest::epoch).toList())
            .block().get("d1");
        assertThat(result).hasSize(2);
        for (LocalDateTime time : times) {
            assertThat(result.get(epoch(time)).getState()).isEqualTo(state);
            assertThat(result.get(epoch(time)).getValue()).isEqualTo(state.equals("VALID") ? "123.4567890123456789" : null);
        }
        assertThat(executor.queries).hasSize(2);
        assertThat(executor.updates).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"week", "quarter"})
    void calendarDailyMissReadsOnlyMissingHourlySnapshot(String type) throws Exception {
        List<LocalDateTime> times = calendarTimes(type);
        RecordingExecutor executor = new RecordingExecutor();
        executor.responder = request -> {
            if (request.getSql().startsWith("SELECT * FROM")) return Flux.just(calendarConfig());
            if (request.getSql().contains("JOIN \"public\".\"counter_snapshot_day\"")) {
                return Flux.just(snapshotRow(times.get(0), CounterBoundary.VALID));
            }
            if (request.getSql().contains("JOIN \"public\".\"counter_snapshot_hour\"")) {
                assertThat(request.getParameters()).contains(times.get(1)).doesNotContain(times.get(0));
                return Flux.just(snapshotRow(times.get(1), CounterBoundary.VALID));
            }
            return Flux.error(new AssertionError("Resolved snapshots must not query raw or CAGG"));
        };
        assertThat(calendarService(executor).resolveBoundaries("product-1", "energy", List.of("d1"),
            times.stream().map(CounterSnapshotServiceStateTest::epoch).toList()).block().get("d1")).hasSize(2);
        assertThat(executor.queries).hasSize(3);
        assertThat(executor.updates).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"week", "quarter"})
    void calendarMissingBoundaryUsesGuardedCaggThenRawWithoutRequeryingSnapshotHits(String type) throws Exception {
        List<LocalDateTime> times = calendarTimes(type);
        RecordingExecutor executor = new RecordingExecutor();
        executor.responder = request -> {
            String sql = request.getSql();
            if (sql.startsWith("SELECT * FROM")) return Flux.just(calendarConfig());
            if (sql.contains("JOIN \"public\".\"counter_snapshot_day\"")) {
                assertThat(sql).contains("c.pending_from<=s.boundary_time", "c.pending_to>=s.boundary_time",
                    "t.kind='REBUILD'", "t.status<>'SUCCESS'", "t.source_version>s.source_version",
                    "d.dirty_from<=s.boundary_time", "d.dirty_to>=s.boundary_time");
                return Flux.just(snapshotRow(times.get(0), CounterBoundary.VALID));
            }
            if (sql.contains("AS relation_name")) return Flux.just(Map.of("relation_name", "metrics"));
            assertThat(request.getParameters()).contains(times.get(1)).doesNotContain(times.get(0));
            if (sql.contains("counter_snapshot_hour")) return Flux.empty();
            if (sql.contains("metrics_daily_agg") || sql.contains("metrics_hourly_agg")) {
                assertThat(sql).contains("counter_cagg_refresh", "p.source_version>=?", "counter_snapshot_change");
                return Flux.empty(); // No sufficiently fresh CAGG candidate for this boundary.
            }
            if (sql.contains("FROM requested r LEFT JOIN LATERAL")) {
                return Flux.just(Map.of("device_id", "d1", "boundary_time", times.get(1),
                    "sample_time", times.get(1).minusSeconds(1), "value", "456.7890123456789"));
            }
            return Flux.error(new AssertionError("Unexpected lookup: " + sql));
        };
        Map<Long, CounterBoundary> result = calendarService(executor).resolveBoundaries("product-1", "energy",
            List.of("d1"), times.stream().map(CounterSnapshotServiceStateTest::epoch).toList()).block().get("d1");
        assertThat(result.get(epoch(times.get(0))).getValue()).isEqualTo("123.4567890123456789");
        assertThat(result.get(epoch(times.get(1))).getValue()).isEqualTo("456.7890123456789");
        assertThat(executor.queries).hasSize(7); // config, day, hour, daily CAGG, hourly CAGG, catalog, raw
        assertThat(executor.updates).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"week", "quarter"})
    void calendarSnapshotDatabaseFailurePropagatesWithoutFallback(String type) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.responder = request -> request.getSql().startsWith("SELECT * FROM")
            ? Flux.just(calendarConfig()) : Flux.error(new IllegalStateException("snapshot read failed"));
        StepVerifier.create(calendarService(executor).resolveBoundaries("product-1", "energy", List.of("d1"),
            calendarTimes(type).stream().map(CounterSnapshotServiceStateTest::epoch).toList()))
            .expectErrorMessage("snapshot read failed").verify();
        assertThat(executor.queries).hasSize(2);
        assertThat(executor.updates).isEmpty();
    }

    private static List<LocalDateTime> calendarTimes(String type) {
        return type.equals("week")
            ? List.of(LocalDateTime.of(2024, 1, 8, 0, 0), LocalDateTime.of(2024, 1, 15, 0, 0))
            : List.of(LocalDateTime.of(2024, 1, 1, 0, 0), LocalDateTime.of(2024, 4, 1, 0, 0));
    }

    private static long epoch(LocalDateTime time) {
        return time.atZone(SHANGHAI).toInstant().toEpochMilli();
    }

    private static Map<String, Object> calendarConfig() {
        return Map.of("product_id", "product-1", "raw_table", "metrics", "source_version", 1L);
    }

    private static Map<String, Object> snapshotRow(LocalDateTime time, String state) {
        Map<String, Object> row = new HashMap<>();
        row.put("device_id", "d1");
        row.put("boundary_time", time);
        row.put("state", state);
        row.put("value_text", state.equals(CounterBoundary.VALID) ? "123.4567890123456789" : null);
        row.put("sample_time", state.equals(CounterBoundary.VALID) ? time.minusSeconds(1) : null);
        return row;
    }

    private static CounterSnapshotService calendarService(RecordingExecutor executor) throws Exception {
        CounterSnapshotService service = service(executor);
        Field snapshotsReady = CounterSnapshotService.class.getDeclaredField("snapshotsReady");
        snapshotsReady.setAccessible(true);
        snapshotsReady.setBoolean(service, true);
        return service;
    }

    @Test
    void summaryEndKeepsAnyPredecessorAndOnlyThenSeeksSuccessorBucket() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.responder = request -> request.getSql().contains("SELECT raw_table")
            ? Flux.just(Map.of("raw_table", "metrics"))
            : Flux.just(Map.of("device_id", "d1", "value", "100"));
        long boundary = BOUNDARY.plusHours(8).plusMinutes(30).atZone(SHANGHAI).toInstant().toEpochMilli();

        Map<String, String> values = summaryService(executor)
            .summaryBoundaryValues("product-1", "energy", List.of("d1", "d2"), boundary, true, false).block();

        assertThat(values).containsEntry("d1", "100");
        SqlRequest request = executor.queries.get(1);
        assertThat(request.getSql()).contains("COALESCE(p.value,n.value)", "WHERE p.value IS NULL",
            "ORDER BY \"timestamp\" DESC LIMIT 1", "ORDER BY \"timestamp\" ASC LIMIT 1",
            "date_trunc('hour'", "interval '1 hour'");
        assertThat(request.getParameters()).contains(BOUNDARY.plusHours(9));
        assertThat(executor.updates).isEmpty();
    }

    @Test
    void summaryDailyMissFallsThroughToHourlyBoundaryBeforeRawConsumerFallback() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.responder = request -> request.getSql().contains("SELECT raw_table")
            ? Flux.just(Map.of("raw_table", "metrics"))
            : request.getSql().contains("date_trunc('day'") ? Flux.empty()
                : Flux.just(Map.of("device_id", "d1", "value", "20"));
        long boundary = BOUNDARY.atZone(SHANGHAI).toInstant().toEpochMilli();

        assertThat(summaryService(executor).summaryBoundaryValues("product-1", "energy", List.of("d1"),
            boundary, true, true).block()).containsEntry("d1", "20");
        assertThat(executor.queries).hasSize(3);
        assertThat(executor.queries.get(1).getSql()).contains("interval '1 day'");
        assertThat(executor.queries.get(2).getSql()).contains("interval '1 hour'");
        assertThat(executor.updates).isEmpty();
    }

    @Test
    void summaryStorageErrorsPropagateWithoutHourlyRetry() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.responder = request -> request.getSql().contains("SELECT raw_table")
            ? Flux.just(Map.of("raw_table", "metrics"))
            : Flux.error(new IllegalStateException("summary query failed"));
        long boundary = BOUNDARY.atZone(SHANGHAI).toInstant().toEpochMilli();

        StepVerifier.create(summaryService(executor).summaryBoundaryValues("product-1", "energy", List.of("d1"),
            boundary, true, true)).expectErrorMessage("summary query failed").verify();
        assertThat(executor.queries).hasSize(2);
        assertThat(executor.updates).isEmpty();
    }

    @Test
    void summaryRetainsSnapshotsAfterRawRetentionExpires() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.responder = request -> request.getSql().contains("SELECT raw_table")
            ? Flux.just(Map.of("raw_table", "metrics")) : Flux.error(new AssertionError("Raw lookup not needed"));
        long boundary = BOUNDARY.atZone(SHANGHAI).toInstant().toEpochMilli();
        CounterSnapshotService instance = summaryService(executor);
        org.mockito.Mockito.doReturn(Mono.just(Map.of("d1", Map.of(boundary,
            new CounterBoundary(boundary, "100", boundary - 1000, CounterBoundary.VALID, "SNAPSHOT")))))
            .when(instance).resolveBoundaries("product-1", "energy", List.of("d1"), List.of(boundary));

        assertThat(instance.summaryBoundaryValues("product-1", "energy", List.of("d1"), boundary, true, true).block())
            .containsEntry("d1", "100");
        assertThat(executor.queries).hasSize(1);
    }

    @Test
    void summaryDoesNotUseAnExactCutoffSampleFromAnInclusiveSnapshot() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.responder = request -> request.getSql().contains("SELECT raw_table")
            ? Flux.just(Map.of("raw_table", "metrics")) : Flux.just(Map.of("device_id", "d1", "value", "90"));
        long boundary = BOUNDARY.atZone(SHANGHAI).toInstant().toEpochMilli();
        CounterSnapshotService instance = summaryService(executor);
        org.mockito.Mockito.doReturn(Mono.just(Map.of("d1", Map.of(boundary,
            new CounterBoundary(boundary, "100", boundary, CounterBoundary.VALID, "SNAPSHOT")))))
            .when(instance).resolveBoundaries("product-1", "energy", List.of("d1"), List.of(boundary));

        assertThat(instance.summaryBoundaryValues("product-1", "energy", List.of("d1"), boundary, true, true).block())
            .containsEntry("d1", "90");
        assertThat(executor.queries).hasSize(2);
        assertThat(executor.queries.get(1).getSql()).contains("\"timestamp\"<?::timestamp");
    }

    private CounterSnapshotService summaryService(RecordingExecutor executor) throws Exception {
        CounterSnapshotService instance = org.mockito.Mockito.spy(service(executor));
        org.mockito.Mockito.doReturn(Mono.just(Map.of())).when(instance).resolveBoundaries(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.anyCollection());
        return instance;
    }

    @Test
    void missingOuterEndReadsFirstNonNullFollowingReadingInOneBatch() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.responder = request -> request.getSql().contains("SELECT raw_table")
            ? Flux.just(Map.of("raw_table", "metrics"))
            : Flux.just(Map.of("device_id", "d1", "value", "1718479.63"));
        long end = BOUNDARY.atZone(SHANGHAI).toInstant().toEpochMilli();

        Map<String, String> values = service(executor)
            .firstValuesAfter("product-1", "energy", List.of("d1", "d2"), end).block();

        assertThat(values).containsEntry("d1", "1718479.63").doesNotContainKey("d2");
        SqlRequest request = executor.queries.get(1);
        assertThat(request.getSql()).contains("DISTINCT ON (\"deviceId\")",
            "\"timestamp\">?::timestamp", "\"timestamp\"<=?::timestamp", "value IS NOT NULL",
            "ORDER BY \"deviceId\",\"timestamp\" ASC");
        assertThat(request.getParameters()).contains("energy", BOUNDARY, "d1", "d2");
        assertThat(executor.updates).isEmpty();
    }

    @Test
    void futureOuterEndDoesNotLookAhead() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        assertThat(service(executor).firstValuesAfter("product-1", "energy", List.of("d1"),
            System.currentTimeMillis() + 3_600_000L).block()).isEmpty();
        assertThat(executor.queries).isEmpty();
    }

    @Test
    void cancelClearsOnlyTheCancelledTaskConfiguration() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.responder = request -> request.getSql().contains("SET status='CANCELLED'")
            ? Flux.just(taskRow("CANCELLED")) : Flux.empty();
        CounterSnapshotService service = service(executor);

        CounterTask cancelled = service.cancel("task-1").block();

        assertThat(cancelled).isNotNull();
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        assertThat(executor.updates).hasSize(1);
        SqlRequest configUpdate = executor.updates.get(0);
        assertThat(configUpdate.getSql())
            .contains("UPDATE \"public\".\"counter_snapshot_config\"")
            .contains("task_id=NULL,pending_from=NULL,pending_to=NULL")
            .contains("status=CASE WHEN enabled THEN 'IDLE' ELSE status END")
            .contains("WHERE product_id=? AND property_id=? AND task_id=?")
            .doesNotContain("coverage_from=", "coverage_to=");
        assertThat(configUpdate.getParameters()).containsExactly("product-1", "energy", "task-1");
    }

    @Test
    void snapshotLookupExcludesVersionsInvalidatedByCancelledRebuild() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        CounterSnapshotService service = service(executor);

        loadHourSnapshot(service).block();

        String sql = executor.queries.stream()
            .map(SqlRequest::getSql)
            .filter(query -> query.contains("counter_snapshot_hour"))
            .findFirst().orElseThrow(() -> new AssertionError("Hourly snapshot lookup was not issued"));
        assertThat(sql)
            .contains("t.kind='REBUILD'")
            .contains("t.status<>'SUCCESS'")
            .contains("t.source_version>s.source_version")
            .contains("t.from_time<=s.boundary_time")
            .contains("t.to_time>=s.boundary_time");
    }

    @Test
    void fiftiethDeviceAdvancesToFiftyFirstWithoutCompletingTask() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.responder = request -> request.getSql().contains("SELECT device_id FROM")
            ? Flux.just(Map.of("device_id", "d051")) : Flux.empty();
        CounterSnapshotService service = service(executor);
        List<String> firstFifty = IntStream.rangeClosed(1, 50)
            .mapToObj(index -> String.format("d%03d", index))
            .collect(Collectors.toList());

        advance(service, task(), firstFifty).block();

        assertThat(executor.updates).hasSize(1);
        SqlRequest cursorUpdate = executor.updates.get(0);
        assertThat(cursorUpdate.getSql())
            .contains("SET cursor_time=from_time,cursor_device=?")
            .doesNotContain("status='SUCCESS'");
        assertThat(cursorUpdate.getParameters()[0]).isEqualTo("d051");
        assertThat(executor.queries).noneMatch(request -> request.getSql().contains("status='SUCCESS'"));
    }

    @Test
    void finalDeviceCompletesOnlyWhenNoNextDeviceExists() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.responder = request -> request.getSql().contains("SET status='SUCCESS'")
            ? Flux.just(Map.of("id", "task-1")) : Flux.empty();
        CounterSnapshotService service = service(executor);

        advance(service, task(), List.of("d051")).block();

        assertThat(executor.queries).anyMatch(request -> request.getSql().contains("SET status='SUCCESS'"));
        assertThat(executor.updates).anyMatch(request -> request.getSql().contains("published_version=published_version+1"));
    }

    @Test
    void batchWritesSnapshotValuesLoadedFromRawBeforeCompletingTask() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        LocalDateTime sampleTime = BOUNDARY.minusMinutes(1);
        executor.responder = request -> {
            String sql = request.getSql();
            if (sql.contains("AS relation_name")) {
                return Flux.just(Map.of("relation_name", "public.properties_test"));
            }
            if (sql.contains("counter_snapshot_task") && sql.contains("FOR UPDATE")) {
                return Flux.just(Map.of("id", "task-1"));
            }
            if (sql.contains("counter_snapshot_config") && sql.contains("FOR UPDATE")) {
                return Flux.just(Map.of("enabled", true, "raw_table", "properties_test",
                    "source_version", 3L, "product_id", "product-1"));
            }
            if (sql.contains("FROM requested r LEFT JOIN LATERAL")
                && sql.contains("properties_test")) {
                return Flux.just(Map.of("device_id", "d001", "boundary_time", BOUNDARY,
                    "value", "123.4", "sample_time", sampleTime));
            }
            if (sql.contains("SET status='SUCCESS'")) {
                return Flux.just(Map.of("id", "task-1"));
            }
            return Flux.empty();
        };
        CounterSnapshotService service = service(executor, mock(ConnectionFactory.class), false);
        Map<String, Object> task = task();
        task.put("device_id", "d001");
        task.put("cursor_time", BOUNDARY);

        processBatch(service, task).block();

        SqlRequest hour = executor.updates.stream()
            .filter(request -> request.getSql().contains("INSERT INTO \"public\".\"counter_snapshot_hour\""))
            .findFirst().orElseThrow(() -> new AssertionError("Hourly snapshot was not written"));
        SqlRequest day = executor.updates.stream()
            .filter(request -> request.getSql().contains("INSERT INTO \"public\".\"counter_snapshot_day\""))
            .findFirst().orElseThrow(() -> new AssertionError("Daily snapshot was not written"));
        for (SqlRequest snapshot : List.of(hour, day)) {
            assertThat(snapshot.getParameters()[1]).isEqualTo("d001");
            assertThat(snapshot.getParameters()[4]).isEqualTo("123.4");
            assertThat(snapshot.getParameters()[5]).isEqualTo(sampleTime);
        }
        assertThat(executor.queries).anyMatch(request -> request.getSql().contains("SET status='SUCCESS'"));
    }

    @Test
    void manualRebuildRefreshesPhysicalCaggDespiteMatchingOldProof() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        java.util.concurrent.atomic.AtomicBoolean currentProof = new java.util.concurrent.atomic.AtomicBoolean();
        LocalDateTime from = BOUNDARY.plusHours(8), to = BOUNDARY.plusHours(11);
        executor.responder = request -> {
            String sql = request.getSql();
            if (sql.contains("SELECT from_time,to_time FROM \"public\".\"counter_cagg_refresh\"")) {
                // An older proof exists, but does not satisfy the rebuild task's creation time.
                return sql.contains("refreshed_at >=") && !currentProof.get()
                    ? Flux.empty() : Flux.just(Map.of("from_time", from, "to_time", to));
            }
            if (sql.contains("to_regclass")) {
                return Flux.just(Map.of("view_name", "public.properties_test_hourly_agg"));
            }
            if (sql.contains("SELECT product_id,property_id,source_version")
                && sql.contains("counter_snapshot_config")) {
                return Flux.just(Map.of("product_id", "product-1",
                                        "property_id", "energy", "source_version", 4L));
            }
            return Flux.empty();
        };
        executor.updater = request -> {
            if (request.getSql().contains("INSERT INTO \"public\".\"counter_cagg_refresh\"")) {
                currentProof.set(true);
            }
        };
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        Result result = mock(Result.class);
        AtomicInteger calls = new AtomicInteger();
        when(factory.create()).thenAnswer(ignored -> Mono.just(connection));
        when(connection.createStatement(any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return statement;
        });
        when(statement.bind(anyInt(), any())).thenReturn(statement);
        when(statement.execute()).thenAnswer(ignored -> Flux.just(result));
        when(result.getRowsUpdated()).thenReturn(Mono.just(1L));
        when(connection.close()).thenReturn(Mono.empty());
        CounterSnapshotService service = service(executor, factory);
        Field extensionSchema = CounterSnapshotService.class.getDeclaredField("extensionSchema");
        extensionSchema.setAccessible(true);
        extensionSchema.set(service, "public");
        Map<String, Object> config = Map.of("product_id", "product-1", "property_id", "energy",
                                           "raw_table", "properties_test", "source_version", 4L);

        // Test one layer's proof contract, independently of month/day scheduling in maybeRefresh.
        Method refreshLayer = CounterSnapshotService.class.getDeclaredMethod("refreshAndPublish", Map.class,
            String.class, LocalDateTime.class, LocalDateTime.class, String.class);
        refreshLayer.setAccessible(true);
        assertThat(((Mono<?>) refreshLayer.invoke(service, config, "HOURLY", from, to, "task-1")).block()).isEqualTo(true);
        assertThat(currentProof.get()).isTrue();
        // A proof published for this task prevents a second physical refresh of the same range.
        assertThat(((Mono<?>) refreshLayer.invoke(service, config, "HOURLY", from, to, "task-1")).block()).isEqualTo(true);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(executor.queries).anyMatch(request -> request.getSql().contains("refreshed_at >=")
            && request.getSql().contains("counter_snapshot_task"));
        verify(connection).createStatement(argThat(sql ->
            sql.contains("refresh_continuous_aggregate") && sql.contains("force=>true")));
    }

    @Test
    void closedMonthRefreshesHourlyThenDailyThenMonthlyOnlyOnce() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        List<Map<String, Object>> proofs = new ArrayList<>();
        executor.responder = request -> {
            String sql = request.getSql();
            Object[] params = request.getParameters();
            if (sql.contains("SELECT from_time,to_time FROM")
                && sql.contains("counter_cagg_refresh")) {
                return Flux.fromIterable(proofs)
                    .filter(row -> row.get("layer").equals(params[3]))
                    .filter(row -> ((LocalDateTime) row.get("from_time"))
                        .isBefore((LocalDateTime) params[4]))
                    .filter(row -> ((LocalDateTime) row.get("to_time"))
                        .isAfter((LocalDateTime) params[5]));
            }
            if (sql.contains("SELECT product_id,property_id,source_version,from_time,to_time FROM")
                && sql.contains("counter_cagg_refresh")) {
                return Flux.fromIterable(proofs)
                    .filter(row -> row.get("layer").equals(params[1]))
                    .filter(row -> ((LocalDateTime) row.get("from_time"))
                        .isBefore((LocalDateTime) params[2]))
                    .filter(row -> ((LocalDateTime) row.get("to_time"))
                        .isAfter((LocalDateTime) params[3]));
            }
            if (sql.contains("to_regclass")) {
                return Flux.just(Map.of("view_name", "present"));
            }
            if (sql.contains("SELECT product_id,property_id,source_version FROM")
                && sql.contains("counter_snapshot_config")) {
                return Flux.just(Map.of("product_id", "product-1",
                    "property_id", "energy", "source_version", 4L));
            }
            return Flux.empty();
        };
        executor.updater = request -> {
            if (!request.getSql().contains("INSERT INTO \"public\".\"counter_cagg_refresh\"")) return;
            Object[] params = request.getParameters();
            proofs.add(Map.of("product_id", params[0], "property_id", params[1],
                "layer", params[3], "from_time", params[4], "to_time", params[5],
                "source_version", params[6]));
        };
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        Result result = mock(Result.class);
        List<String> calls = new ArrayList<>();
        when(factory.create()).thenAnswer(ignored -> Mono.just(connection));
        when(connection.createStatement(any())).thenAnswer(invocation -> {
            calls.add(invocation.getArgument(0));
            return statement;
        });
        when(statement.bind(anyInt(), any())).thenReturn(statement);
        when(statement.execute()).thenAnswer(ignored -> Flux.just(result));
        when(result.getRowsUpdated()).thenReturn(Mono.just(1L));
        when(connection.close()).thenReturn(Mono.empty());
        CounterSnapshotService service = service(executor, factory);
        Field extensionSchema = CounterSnapshotService.class.getDeclaredField("extensionSchema");
        extensionSchema.setAccessible(true);
        extensionSchema.set(service, "public");
        Map<String, Object> config = Map.of("product_id", "product-1", "property_id", "energy",
            "raw_table", "properties_test", "source_version", 4L);
        long from = LocalDateTime.of(2024, 2, 15, 10, 0).atZone(SHANGHAI).toInstant().toEpochMilli();
        // Four boundaries used to fall below late-cagg-threshold=24 and skip all layers.
        long to = LocalDateTime.of(2024, 2, 15, 13, 0).atZone(SHANGHAI).toInstant().toEpochMilli();

        refreshRange(service, config, from, to, "DIRTY").block();
        refreshRange(service, config, from, to, "DIRTY").block();

        assertThat(calls).hasSize(3);
        assertThat(calls.get(0)).contains("refresh_continuous_aggregate");
        assertThat(proofs).extracting(row -> row.get("layer"))
            .containsExactly("HOURLY", "DAILY", "MONTHLY");
        assertThat(proofs).allSatisfy(row -> {
            assertThat(row.get("from_time")).isEqualTo(LocalDateTime.of(2024, 2, 1, 0, 0));
            assertThat(row.get("to_time")).isEqualTo(LocalDateTime.of(2024, 3, 1, 0, 0));
        });
    }

    @Test
    void manualRebuildRefreshFailureCannotCompleteTask() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        Map<String, Object> task = task();
        task.put("kind", "REBUILD");
        task.put("from_time", BOUNDARY.plusHours(9));
        task.put("to_time", BOUNDARY.plusHours(10));
        task.put("cursor_time", BOUNDARY.plusHours(9));
        executor.responder = request -> {
            String sql = request.getSql();
            if (sql.contains("SELECT * FROM \"public\".\"counter_snapshot_task\"")) {
                return Flux.just(task);
            }
            if (sql.contains("SELECT id FROM \"public\".\"counter_snapshot_task\"")
                && sql.contains("FOR UPDATE")) {
                return Flux.just(Map.of("id", "task-1"));
            }
            if (sql.contains("SELECT * FROM \"public\".\"counter_snapshot_config\"")) {
                return Flux.just(Map.of("product_id", "product-1", "property_id", "energy",
                                        "raw_table", "properties_test", "source_version", 4L));
            }
            if (sql.contains("SELECT 1 FROM \"public\".\"counter_cagg_refresh\"")) {
                // The matching source version belongs to a proof predating this rebuild.
                return sql.contains("refreshed_at >=")
                    ? Flux.empty() : Flux.just(Map.of("exists", 1));
            }
            if (sql.contains("to_regclass")) {
                return Flux.just(Map.of("view_name", "public.properties_test_hourly_agg"));
            }
            if (sql.contains("SELECT product_id,property_id,source_version")) {
                return Flux.just(Map.of("product_id", "product-1",
                                        "property_id", "energy", "source_version", 4L));
            }
            if (sql.contains("SET status='SUCCESS'")) {
                return Flux.just(Map.of("id", "task-1"));
            }
            return Flux.empty();
        };
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.create()).thenAnswer(ignored -> Mono.error(new IllegalStateException("refresh failed")));
        CounterSnapshotService service = service(executor, factory);
        Field extensionSchema = CounterSnapshotService.class.getDeclaredField("extensionSchema");
        extensionSchema.setAccessible(true);
        extensionSchema.set(service, "public");

        StepVerifier.create(processCycle(service))
            .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("refresh failed"))
            .verify();

        assertThat(executor.queries).noneMatch(request ->
            request.getSql().contains("SET status='SUCCESS'"));
    }

    @Test
    void incompatibleCaggUsesRawWorkerPathWithoutPhysicalRefresh() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        ConnectionFactory factory = mock(ConnectionFactory.class);
        CounterSnapshotService service = service(executor, factory, false);
        Map<String, Object> config = Map.of("product_id", "product-1", "property_id", "energy",
            "raw_table", "properties_test", "source_version", 4L);

        refresh(service, config, "REBUILD").block();

        assertThat(executor.queries).noneMatch(request ->
            request.getSql().contains("counter_cagg_refresh"));
        verify(factory, org.mockito.Mockito.never()).create();
    }

    @Test
    void dirtyTaskPropagatesPhysicalRefreshFailureForRetry() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.responder = request -> {
            String sql = request.getSql();
            if (sql.contains("to_regclass")) return Flux.just(Map.of("view_name", "present"));
            if (sql.contains("SELECT product_id,property_id,source_version FROM")
                && sql.contains("counter_snapshot_config")) {
                return Flux.just(Map.of("product_id", "product-1",
                    "property_id", "energy", "source_version", 4L));
            }
            return Flux.empty();
        };
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.create()).thenAnswer(ignored -> Mono.error(new IllegalStateException("physical refresh failed")));
        CounterSnapshotService service = service(executor, factory);
        Field extensionSchema = CounterSnapshotService.class.getDeclaredField("extensionSchema");
        extensionSchema.setAccessible(true);
        extensionSchema.set(service, "public");
        Map<String, Object> config = Map.of("product_id", "product-1", "property_id", "energy",
            "raw_table", "properties_test", "source_version", 4L);
        long from = LocalDateTime.of(2024, 2, 15, 10, 0).atZone(SHANGHAI).toInstant().toEpochMilli();
        long to = LocalDateTime.of(2024, 2, 16, 10, 0).atZone(SHANGHAI).toInstant().toEpochMilli();

        StepVerifier.create(refreshRange(service, config, from, to, "DIRTY"))
            .expectErrorSatisfies(error -> assertThat(error)
                .hasMessageContaining("physical refresh failed"))
            .verify();
    }

    private static Map<String, Object> task() {
        Map<String, Object> task = taskRow("RUNNING");
        task.put("kind", "AUTO");
        task.put("attempts", 1L);
        return task;
    }

    private static Map<String, Object> taskRow(String status) {
        Map<String, Object> task = new HashMap<>();
        task.put("id", "task-1");
        task.put("product_id", "product-1");
        task.put("property_id", "energy");
        task.put("status", status);
        task.put("from_time", BOUNDARY);
        task.put("to_time", BOUNDARY);
        task.put("processed", 0L);
        task.put("total", 51L);
        return task;
    }

    @SuppressWarnings("unchecked")
    private static Mono<Void> advance(CounterSnapshotService service, Map<String, Object> task,
                                      List<String> devices) throws Exception {
        Method method = CounterSnapshotService.class.getDeclaredMethod("advanceTask", Map.class,
            Map.class, List.class, List.class);
        method.setAccessible(true);
        long boundary = BOUNDARY.atZone(SHANGHAI).toInstant().toEpochMilli();
        return (Mono<Void>) method.invoke(service, task, Map.of(), devices, List.of(boundary));
    }

    @SuppressWarnings("unchecked")
    private static Mono<Void> processBatch(CounterSnapshotService service,
                                           Map<String, Object> task) throws Exception {
        Method method = CounterSnapshotService.class.getDeclaredMethod("processTaskBatch", Map.class);
        method.setAccessible(true);
        return (Mono<Void>) method.invoke(service, task);
    }

    @SuppressWarnings("unchecked")
    private static Mono<Void> refresh(CounterSnapshotService service,
                                      Map<String, Object> config, String kind) throws Exception {
        Method method = CounterSnapshotService.class.getDeclaredMethod("maybeRefresh", Map.class,
            long.class, long.class, String.class, String.class);
        method.setAccessible(true);
        long from = BOUNDARY.plusHours(9).atZone(SHANGHAI).toInstant().toEpochMilli();
        long to = BOUNDARY.plusHours(10).atZone(SHANGHAI).toInstant().toEpochMilli();
        return (Mono<Void>) method.invoke(service, config, from, to, kind, "task-1");
    }

    @SuppressWarnings("unchecked")
    private static Mono<Void> refreshRange(CounterSnapshotService service,
                                           Map<String, Object> config,
                                           long from, long to, String kind) throws Exception {
        Method method = CounterSnapshotService.class.getDeclaredMethod("maybeRefresh", Map.class,
            long.class, long.class, String.class, String.class);
        method.setAccessible(true);
        return (Mono<Void>) method.invoke(service, config, from, to, kind, "task-1");
    }

    @SuppressWarnings("unchecked")
    private static Mono<Void> processCycle(CounterSnapshotService service) throws Exception {
        Method method = CounterSnapshotService.class.getDeclaredMethod("processTaskCycle", String.class,
            long.class, int.class);
        method.setAccessible(true);
        return (Mono<Void>) method.invoke(service, "task-1", 1L, 1);
    }

    @SuppressWarnings("unchecked")
    private static Mono<Void> loadHourSnapshot(CounterSnapshotService service) throws Exception {
        Class<?> requestType = Class.forName(CounterSnapshotService.class.getName() + "$BoundaryRequest");
        Constructor<?> constructor = requestType.getDeclaredConstructor(String.class, long.class);
        constructor.setAccessible(true);
        long boundary = BOUNDARY.atZone(SHANGHAI).toInstant().toEpochMilli();
        Object request = constructor.newInstance("d001", boundary);
        Method method = CounterSnapshotService.class.getDeclaredMethod("loadSnapshots", String.class,
            String.class, String.class, List.class, Map.class);
        method.setAccessible(true);
        Map<String, Map<Long, CounterBoundary>> target = new LinkedHashMap<>();
        target.put("d001", new LinkedHashMap<>());
        return (Mono<Void>) method.invoke(service, "counter_snapshot_hour", "product-1", "energy",
            List.of(request), target);
    }

    private static CounterSnapshotService service(RecordingExecutor executor) throws Exception {
        return service(executor, mock(ConnectionFactory.class));
    }

    private static CounterSnapshotService service(RecordingExecutor executor,
                                                  ConnectionFactory connectionFactory) throws Exception {
        return service(executor, connectionFactory, true);
    }

    private static CounterSnapshotService service(RecordingExecutor executor,
                                                  ConnectionFactory connectionFactory,
                                                  boolean caggReady) throws Exception {
        RDBDatabaseMetadata metadata = new RDBDatabaseMetadata(Dialect.POSTGRES);
        metadata.addFeature(executor);
        RDBSchemaMetadata schema = TimescaleDBDialectProvider.GLOBAL.createSchema("public");
        metadata.addSchema(schema);
        metadata.setCurrentSchema(schema);
        DatabaseOperator database = mock(DatabaseOperator.class);
        when(database.getMetadata()).thenReturn(metadata);
        TimescaleDBOperations operations = mock(TimescaleDBOperations.class);
        when(operations.database()).thenReturn(database);
        when(operations.connectionFactory()).thenReturn(connectionFactory);
        CounterSnapshotService service = new CounterSnapshotService(
            operations, new TimescaleDBThingsDataProperties()) {
            @Override
            Mono<Boolean> caggReady(String raw, String layer) {
                return Mono.just(caggReady);
            }
        };

        Field transaction = CounterSnapshotService.class.getDeclaredField("transaction");
        transaction.setAccessible(true);
        transaction.set(service, new TransactionalOperator() {
            @Override
            public <T> Mono<T> transactional(Mono<T> mono) {
                return mono;
            }

            @Override
            public <T> Flux<T> execute(TransactionCallback<T> action) {
                throw new UnsupportedOperationException("Only transactional(Mono) is used here");
            }
        });
        Field ready = CounterSnapshotService.class.getDeclaredField("ready");
        ready.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) ready.get(service)).set(true);
        return service;
    }

    private static final class RecordingExecutor implements ReactiveSqlExecutor {
        final List<SqlRequest> queries = new ArrayList<>();
        final List<SqlRequest> updates = new ArrayList<>();
        Function<SqlRequest, Flux<Map<String, Object>>> responder = request -> Flux.empty();
        Consumer<SqlRequest> updater = request -> { };

        @Override
        public Mono<Integer> update(Publisher<SqlRequest> requests) {
            return Flux.from(requests).doOnNext(request -> {
                updates.add(request);
                updater.accept(request);
            }).then(Mono.just(1));
        }

        @Override
        public Mono<Void> execute(Publisher<SqlRequest> requests) {
            return Flux.from(requests).doOnNext(request -> {
                updates.add(request);
                updater.accept(request);
            }).then();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <E> Flux<E> select(Publisher<SqlRequest> requests, ResultWrapper<E, ?> wrapper) {
            return Flux.from(requests).concatMap(request -> {
                queries.add(request);
                return responder.apply(request);
            }).map(row -> (E) row);
        }
    }
}
