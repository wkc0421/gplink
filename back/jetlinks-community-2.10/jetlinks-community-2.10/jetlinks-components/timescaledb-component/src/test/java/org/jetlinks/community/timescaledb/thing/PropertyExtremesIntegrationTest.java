package org.jetlinks.community.timescaledb.thing;

import io.r2dbc.spi.*;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.reactive.r2dbc.R2dbcReactiveSqlExecutor;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers;
import org.jetlinks.community.things.data.PropertyExtremes;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.jetlinks.community.timescaledb.thing.CaggTestSupport.time;

/** Requires an explicitly provisioned disposable TimescaleDB database; never uses application credentials. */
class PropertyExtremesIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static TestExecutor executor;
    private static ConnectionPool pool;
    private String table;
    private TimescaleDBPropertyExtremesQuery query;

    @BeforeAll
    static void connect() {
        String url = System.getProperty("extremes.test.url");
        Assumptions.assumeTrue(url != null, "No disposable extremes.test.url configured");
        pool = new ConnectionPool(ConnectionPoolConfiguration.builder(ConnectionFactories.get(url))
            .initialSize(1).maxSize(5).build());
        executor = new TestExecutor(pool);
    }

    @AfterAll
    static void closePool() {
        if (pool != null) pool.disposeLater().block(WAIT);
    }

    @BeforeEach
    void createFixture() {
        table = "extremes_it_" + Long.toUnsignedString(System.nanoTime(), 36);
        execute("CREATE TABLE " + table + " (\"deviceId\" varchar NOT NULL, property varchar NOT NULL,"
            + "\"timestamp\" timestamp NOT NULL,\"numberValue\" double precision)");
        execute("SELECT create_hypertable(?::regclass,'timestamp',chunk_time_interval=>INTERVAL '1 month')", table);
        execute("CREATE UNIQUE INDEX ON " + table + " (\"deviceId\",property,\"timestamp\" DESC)");
        query = new TimescaleDBPropertyExtremesQuery(executor, "public", table, "deviceId",
            sql -> executor.select(sql, ResultWrappers.map()));
    }

    @AfterEach
    void cleanup() {
        if (table != null) execute("DROP TABLE " + table);
    }

    @Test
    void originalMillisecondsClosedBoundsTiesSingleAndAllEqualSamples() {
        insert("a", "p", "2024-01-01 00:00:00.000", -999.0);
        insert("a", "p", "2024-01-01 00:00:00.001", -2.5);
        insert("a", "p", "2024-01-01 00:00:00.002", -2.5);
        insert("a", "p", "2024-01-01 00:00:00.500", 8.25);
        insert("a", "p", "2024-01-01 00:00:00.999", 8.25);
        insert("a", "p", "2024-01-01 00:00:01.000", 999.0);
        insert("a", "single", "2024-01-01 00:00:00.999", 0.0);
        insert("a", "equal", "2024-01-01 00:00:00.002", 4.0);
        insert("a", "equal", "2024-01-01 00:00:00.003", 4.0);
        insert("b", "p", "2024-01-01 00:00:00.001", 123.0);
        var rows = query.query(List.of(target("a", "p"), target("a", "single"), target("a", "equal"), target("b", "p")),
            time("2024-01-01 00:00:00.001"), time("2024-01-01 00:00:00.999"))
            .collectMap(PropertyExtremes::target).block(WAIT);
        assertThat(rows.get(target("a", "p"))).isEqualTo(new PropertyExtremes("a", "p", PropertyExtremes.Status.OK,
            -2.5, time("2024-01-01 00:00:00.001"), 8.25, time("2024-01-01 00:00:00.500")));
        assertThat(rows.get(target("a", "single")).minValue()).isZero();
        assertThat(rows.get(target("a", "single")).minTime()).isEqualTo(time("2024-01-01 00:00:00.999"));
        assertThat(rows.get(target("a", "equal")).minTime()).isEqualTo(rows.get(target("a", "equal")).maxTime())
            .isEqualTo(time("2024-01-01 00:00:00.002"));
        assertThat(rows.get(target("b", "p")).maxValue()).isEqualTo(123.0);
    }

    @Test
    void nullNonFiniteAndAbsentSamplesHaveNoExtrema() {
        insert("a", "bad", "2024-01-01 00:00:00", null);
        insert("a", "bad", "2024-01-01 00:00:01", Double.NaN);
        insert("a", "bad", "2024-01-01 00:00:02", Double.POSITIVE_INFINITY);
        insert("a", "bad", "2024-01-01 00:00:03", Double.NEGATIVE_INFINITY);
        var rows = query.query(List.of(target("a", "bad"), target("a", "absent")),
            time("2024-01-01 00:00:00"), time("2024-01-02 00:00:00")).collectList().block(WAIT);
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.status()).isEqualTo(PropertyExtremes.Status.NO_DATA);
            assertThat(row.minValue()).isNull();
            assertThat(row.minTime()).isNull();
            assertThat(row.maxValue()).isNull();
            assertThat(row.maxTime()).isNull();
        });
    }

    @Test
    void compressedChunksMatchIndependentRawSampleFold() {
        long base = time("2024-01-01 00:00:00");
        Random random = new Random(41);
        Map<PropertyExtremes.Target, PropertyExtremes> expected = new LinkedHashMap<>();
        for (String device : List.of("a", "b")) for (String property : List.of("p", "q")) {
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            long minTime = 0, maxTime = 0;
            for (int i = 0; i < 80; i++) {
                double value = random.nextInt(31) / 10.0 - 1.5;
                long timestamp = base + i * 1001;
                execute("INSERT INTO " + table + " VALUES (?,?,?::timestamp,?)",
                    device, property, AggregationWindow.local(timestamp), value);
                if (value < min) { min = value; minTime = timestamp; }
                if (value > max) { max = value; maxTime = timestamp; }
            }
            expected.put(target(device, property), new PropertyExtremes(device, property,
                PropertyExtremes.Status.OK, min, minTime, max, maxTime));
        }
        execute("ALTER TABLE " + table + " SET (timescaledb.compress,"
            + "timescaledb.compress_segmentby='\"deviceId\",property',timescaledb.compress_orderby='timestamp DESC')");
        execute("SELECT compress_chunk(chunk) FROM show_chunks(?::regclass) chunk", table);
        var actual = query.query(new ArrayList<>(expected.keySet()), base, base + 80080)
            .collectMap(PropertyExtremes::target).block(WAIT);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void representativeExecutionPlansAndComparison() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("extremes.test.benchmark"), "Benchmark explicitly opt-in");
        execute("INSERT INTO " + table + " SELECT 'd'||d,'p'||p,t,"
            + "((extract(epoch FROM t)::bigint/3600 + d*17 + p*31)%997)::double precision/10"
            + " FROM generate_series(1,8) d CROSS JOIN generate_series(1,3) p"
            + " CROSS JOIN generate_series(timestamp '2023-01-01',timestamp '2025-01-01',interval '1 hour') t");
        execute("ANALYZE " + table);
        List<PropertyExtremes.Target> targets = new ArrayList<>();
        for (int d = 1; d <= 8; d++) for (int p = 1; p <= 3; p++) targets.add(target("d" + d, "p" + p));
        String[][] ranges = {{"hour", "2024-01-01 00:00:00", "2024-01-01 01:00:00"},
            {"day", "2024-01-01 00:00:00", "2024-01-02 00:00:00"},
            {"month", "2024-01-01 00:00:00", "2024-02-01 00:00:00"},
            {"two-years", "2023-01-01 00:00:00", "2025-01-01 00:00:00"}};
        Path output = Path.of("target", "extremes-benchmark");
        Files.createDirectories(output);
        for (String[] range : ranges) {
            long start = time(range[1]), end = time(range[2]);
            SqlRequest statement = query.statement(targets, start, end);
            String plan = executor.select(SqlRequests.of("EXPLAIN (ANALYZE, BUFFERS) " + statement.getSql(),
                statement.getParameters()), ResultWrappers.map()).map(row -> String.valueOf(row.get("QUERY PLAN")))
                .collectList().map(lines -> String.join("\n", lines)).block(WAIT);
            Files.writeString(output.resolve(range[0] + "-batch-plan.txt"), plan);
            StringBuilder baselinePlans = new StringBuilder();
            List<Long> batchNanos = new ArrayList<>(), baselineNanos = new ArrayList<>();
            for (int run = 0; run < 3; run++) {
                long began = System.nanoTime();
                var actual = query.query(targets, start, end).collectMap(PropertyExtremes::target).block(WAIT);
                batchNanos.add(System.nanoTime() - began);
                began = System.nanoTime();
                for (PropertyExtremes.Target target : targets) {
                    for (boolean maximum : List.of(false, true)) {
                        SqlRequest baseline = SqlRequests.of("SELECT \"numberValue\",\"timestamp\" FROM " + table
                            + " WHERE \"deviceId\"=? AND property=? AND \"timestamp\">=?::timestamp AND \"timestamp\"<=?::timestamp"
                            + " AND \"numberValue\">'-Infinity'::double precision AND \"numberValue\"<'Infinity'::double precision"
                            + " ORDER BY \"numberValue\" " + (maximum ? "DESC" : "ASC") + ",\"timestamp\" ASC LIMIT 1",
                            target.thingId(), target.property(), AggregationWindow.local(start), AggregationWindow.local(end));
                        Map<String, Object> row = executor.select(baseline, ResultWrappers.map()).single().block(WAIT);
                        assertThat(((Number) row.get("numberValue")).doubleValue()).isEqualTo(maximum
                            ? actual.get(target).maxValue() : actual.get(target).minValue());
                        assertThat(((LocalDateTime) row.get("timestamp")).atZone(AggregationWindow.ZONE).toInstant().toEpochMilli())
                            .isEqualTo(maximum ? actual.get(target).maxTime() : actual.get(target).minTime());
                        if (run == 2 && target.equals(targets.get(0))) {
                            var lines = executor.select(SqlRequests.of("EXPLAIN (ANALYZE, BUFFERS) " + baseline.getSql(),
                                baseline.getParameters()), ResultWrappers.map()).map(r -> String.valueOf(r.get("QUERY PLAN")))
                                .collectList().block(WAIT);
                            baselinePlans.append(String.join("\n", lines)).append("\n");
                        }
                    }
                }
                baselineNanos.add(System.nanoTime() - began);
            }
            Collections.sort(batchNanos);
            Collections.sort(baselineNanos);
            String summary = range[0] + " targets=24 statements=1 vs 48 median_client_ms="
                + batchNanos.get(1)/1_000_000.0 + " vs " + baselineNanos.get(1)/1_000_000.0;
            System.out.println("EXTREMES_BENCHMARK " + summary);
            Files.writeString(output.resolve(range[0] + "-baseline-plan.txt"), baselinePlans);
            Files.writeString(output.resolve(range[0] + "-summary.txt"), summary);
        }
    }

    private static PropertyExtremes.Target target(String device, String property) {
        return new PropertyExtremes.Target(device, property);
    }

    private void insert(String device, String property, String timestamp, Double value) {
        execute("INSERT INTO " + table + " VALUES (?,?,?::timestamp,?::double precision)",
            device, property, LocalDateTime.parse(timestamp.replace(' ', 'T')), value);
    }

    private void execute(String sql, Object... parameters) {
        executor.execute(SqlRequests.of(sql, parameters)).block(WAIT);
    }

    private static class TestExecutor extends R2dbcReactiveSqlExecutor {
        private final ConnectionFactory factory;
        private TestExecutor(ConnectionFactory factory) { this.factory = factory; }
        @Override protected Mono<Connection> getConnection() {
            return Mono.from(factory.create()).flatMap(connection ->
                Flux.from(connection.createStatement("SET TIME ZONE 'UTC'").execute())
                    .concatMap(Result::getRowsUpdated).then(Mono.just(connection)));
        }
        @Override protected void releaseConnection(SignalType signal, Connection connection) {
            Mono.from(connection.close()).subscribe();
        }
    }
}
