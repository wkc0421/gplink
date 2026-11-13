package org.jetlinks.community.timescaledb.thing.counter;

import io.r2dbc.spi.*;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.reactive.r2dbc.R2dbcReactiveSqlExecutor;
import org.hswebframework.ezorm.rdb.metadata.RDBDatabaseMetadata;
import org.hswebframework.ezorm.rdb.metadata.dialect.Dialect;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.jetlinks.community.timescaledb.TimescaleDBOperations;
import org.jetlinks.community.timescaledb.metadata.TimescaleDBDialectProvider;
import org.jetlinks.community.timescaledb.thing.TimescaleDBThingsDataProperties;
import org.junit.jupiter.api.*;
import reactor.core.publisher.*;

import java.lang.reflect.Field;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Requires an explicitly provisioned disposable TimescaleDB database. Never uses application credentials. */
class CounterHistoryBoundaryIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private Executor executor;
    private CounterSnapshotService service;
    private String schema;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getProperty("counter.test.url");
        Assumptions.assumeTrue(url != null, "Requires disposable counter.test.url");
        ConnectionFactory factory = ConnectionFactories.get(url);
        executor = new Executor(factory);
        schema = "counter_read_it_" + Long.toUnsignedString(System.nanoTime(), 36);
        execute("CREATE SCHEMA " + schema);
        execute("CREATE TABLE " + table("metrics")
            + " (\"deviceId\" text,property text,\"timestamp\" timestamp NOT NULL,value text)");
        execute("SELECT create_hypertable(?::regclass,'timestamp',chunk_time_interval=>interval '1 day')", table("metrics"));
        execute("CREATE INDEX ON " + table("metrics") + " (\"deviceId\",property,\"timestamp\" DESC)");
        execute("CREATE TABLE " + table("counter_snapshot_config")
            + " (product_id text,property_id text,raw_table text,enabled boolean,source_version bigint,product_eligible boolean DEFAULT true)");
        execute("INSERT INTO " + table("counter_snapshot_config")
            + " (product_id,property_id,raw_table,enabled,source_version) VALUES ('p','E','metrics',true,1)");
        execute("CREATE TABLE " + table("counter_snapshot_device")
            + " (product_id text,property_id text,device_id text,present boolean,eligible boolean,enabled_override boolean)");
        execute("CREATE TABLE " + table("counter_cagg_refresh")
            + " (product_id text,property_id text,raw_table text,layer text,from_time timestamp,to_time timestamp,source_version bigint)");
        execute("INSERT INTO " + table("counter_cagg_refresh")
            + " VALUES ('p','E','metrics','DAILY','2024-01-01','2024-02-01',1)");
        execute("CREATE TABLE " + table("counter_snapshot_change")
            + " (product_id text,property_id text,device_id text,dirty_from timestamp)");
        for (String layer : List.of("daily", "hourly")) {
            execute("CREATE TABLE " + table("metrics_" + layer + "_agg")
                + " (\"deviceId\" text,property text,bucket_start timestamp,last_timestamp timestamp,numeric_count bigint)");
        }
        insert("d1", "E", "2024-01-15T10:00:00", "9007199254740993.123456789");
        insert("d1", "E", "2024-01-15T23:00:00", "9007199254740994.123456789");
        insert("d1", "E", "2024-01-16T00:00:00", "9007199254740995.123456789");
        insert("d1", "E", "2024-01-16T01:00:00", null);
        insert("other", "E", "2024-01-15T09:00:00", "999");
        insert("d1", "other", "2024-01-15T09:00:00", "999");
        execute("INSERT INTO " + table("metrics_daily_agg")
            + " VALUES ('d1','E','2024-01-15','2024-01-15 23:00:00',2)");
        execute("ALTER TABLE " + table("metrics")
            + " SET (timescaledb.compress,timescaledb.compress_segmentby='\"deviceId\",property',timescaledb.compress_orderby='\"timestamp\" DESC')");
        execute("SELECT compress_chunk(c) FROM show_chunks(?::regclass) c", table("metrics"));

        RDBDatabaseMetadata metadata = new RDBDatabaseMetadata(Dialect.POSTGRES);
        metadata.addFeature(executor);
        var dbSchema = TimescaleDBDialectProvider.GLOBAL.createSchema(schema);
        metadata.addSchema(dbSchema);
        metadata.setCurrentSchema(dbSchema);
        DatabaseOperator database = mock(DatabaseOperator.class);
        when(database.getMetadata()).thenReturn(metadata);
        TimescaleDBOperations operations = mock(TimescaleDBOperations.class);
        when(operations.database()).thenReturn(database);
        when(operations.connectionFactory()).thenReturn(factory);
        service = new CounterSnapshotService(operations, new TimescaleDBThingsDataProperties()) {
            @Override Mono<Boolean> caggReady(String raw, String layer) { return Mono.just(true); }
        };
        Field ready = CounterSnapshotService.class.getDeclaredField("ready");
        ready.setAccessible(true);
        ((AtomicBoolean) ready.get(service)).set(true);
    }

    @AfterEach
    void cleanup() {
        if (schema != null) execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void preciseCaggCandidateCanBeSupersededByAnExactBoundaryReading() {
        long early = time("2024-01-01T00:00:00"), end = time("2024-01-16T00:00:00");
        Map<Long, CounterBoundary> values = service.resolveBoundaries("p", "E", List.of("d1"),
            List.of(early, end)).block(WAIT).get("d1");
        assertThat(values.get(early).getState()).isEqualTo(CounterBoundary.NO_VALUE);
        assertThat(values.get(end).getValue()).isEqualTo("9007199254740995.123456789");
        assertThat(values.get(end).getSource()).isEqualTo("CAGG");
    }

    @Test
    void staleProofOrDirtyRangeFallsBackToRawWithoutLosingPrecision() {
        execute("UPDATE " + table("counter_cagg_refresh") + " SET source_version=0");
        long end = time("2024-01-16T01:00:00");
        CounterBoundary value = service.resolveBoundaries("p", "E", List.of("d1"), List.of(end))
            .block(WAIT).get("d1").get(end);
        assertThat(value.getSource()).isEqualTo("RAW");
        assertThat(value.getValue()).isEqualTo("9007199254740995.123456789");
        execute("UPDATE " + table("counter_cagg_refresh") + " SET source_version=1");
        execute("INSERT INTO " + table("counter_snapshot_change") + " VALUES ('p','E','d1','2024-01-01')");
        assertThat(service.resolveBoundaries("p", "E", List.of("d1"), List.of(end)).block(WAIT)
            .get("d1").get(end).getSource()).isEqualTo("RAW");
    }

    @Test
    void initialReadingIsIndependentOfBucketsAndFiltersOtherDevicesAndProperties() {
        Map<String, String> values = service.firstValuesInRange("p", "E", List.of("d1", "absent"),
            time("2024-01-01T00:00:00"), time("2024-02-01T00:00:00")).block(WAIT);
        assertThat(values).containsOnlyKeys("d1").containsEntry("d1", "9007199254740993.123456789");
        assertThat(service.firstValuesInRange("p", "E", List.of("d1"), time("2024-01-01T00:00:00"),
            time("2024-01-14T00:00:00")).block(WAIT)).isEmpty();
    }

    @Test
    void largeEmptyPrefixDoesNotExcludeTheExactFirstSampleOrInventDataForAbsentDevices() {
        List<Long> times = List.of("2024-01-01T00:00:00", "2024-01-02T00:00:00", "2024-01-03T00:00:00",
            "2024-01-04T00:00:00", "2024-01-15T10:00:00", "2024-01-16T00:00:00")
            .stream().map(CounterHistoryBoundaryIntegrationTest::time).toList();
        var result = service.resolveBoundaries("p", "E", List.of("d1", "absent"), times).block(WAIT);
        for (long early : times.subList(0, 4)) {
            assertThat(result.get("d1").get(early).getState()).isEqualTo(CounterBoundary.NO_VALUE);
            assertThat(result.get("d1").get(early).getSource()).isEqualTo("BEFORE_FIRST_RAW");
        }
        assertThat(result.get("d1").get(times.get(4)).getValue()).isEqualTo("9007199254740993.123456789");
        assertThat(result.get("d1").get(times.get(5)).getValue()).isEqualTo("9007199254740995.123456789");
        assertThat(result.get("absent").values()).allSatisfy(value -> {
            assertThat(value.getState()).isEqualTo(CounterBoundary.NO_VALUE);
            assertThat(value.getValue()).isNull();
        });
    }

    private String table(String name) { return schema + "." + name; }
    private static long time(String value) {
        return LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }
    private void insert(String device, String property, String timestamp, String value) {
        execute("INSERT INTO " + table("metrics") + " VALUES (?,?,?::timestamp,?)",
            device, property, LocalDateTime.parse(timestamp), value);
    }
    private void execute(String sql, Object... params) { executor.execute(SqlRequests.of(sql, params)).block(WAIT); }

    private static class Executor extends R2dbcReactiveSqlExecutor {
        private final ConnectionFactory factory;
        Executor(ConnectionFactory factory) { this.factory = factory; }
        @Override protected Mono<Connection> getConnection() { return Mono.from(factory.create()); }
        @Override protected void releaseConnection(SignalType signal, Connection connection) {
            Mono.from(connection.close()).subscribe();
        }
    }
}
