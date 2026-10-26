package org.jetlinks.community.timescaledb.thing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.r2dbc.spi.*;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrapper;
import org.hswebframework.ezorm.rdb.executor.reactive.r2dbc.R2dbcReactiveSqlExecutor;
import org.jetlinks.community.Interval;
import org.jetlinks.community.things.data.*;
import org.jetlinks.community.timeseries.query.*;
import org.junit.jupiter.api.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.reactivestreams.Publisher;
import reactor.core.publisher.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.jetlinks.community.timescaledb.thing.CaggTestSupport.*;

/**
 * Default: disposable TimescaleDB 2.29.1 / PG16 container.
 * Optional -Dcagg.test.url: an explicitly provisioned DISPOSABLE local database (never a business database).
 */
class ContinuousAggregateIntegrationTest {
    static GenericContainer<?> container;
    static TestExecutor executor;
    static final Duration WAIT = Duration.ofSeconds(30);
    static final AtomicInteger counter = new AtomicInteger();
    static final String PREFIX = "cagg_it_" + Long.toUnsignedString(System.nanoTime(),36) + "_";
    String table;
    TimescaleDBThingsDataProperties properties;
    ContinuousAggregateManager manager;
    Query query;
    LocalDateTime base;
    final List<String> aggregationSql = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeAll static void start() {
        String url = System.getProperty("cagg.test.url");
        if (url == null) {
            Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");
            container = new GenericContainer<>(DockerImageName.parse("timescale/timescaledb:2.29.1-pg16"))
                .withEnv("POSTGRES_PASSWORD","test").withEnv("TIMESCALEDB_TELEMETRY","off")
                .withExposedPorts(5432).waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofMinutes(2));
            container.start();
            url = "r2dbc:postgresql://postgres:test@" + container.getHost() + ":"
                + container.getMappedPort(5432) + "/postgres";
        }
        executor = new TestExecutor(ConnectionFactories.get(url));
        execute("CREATE EXTENSION IF NOT EXISTS timescaledb");
        String version = executor.select("SELECT extversion FROM pg_extension WHERE extname='timescaledb'")
            .map(row -> row.get("extversion").toString()).blockFirst(WAIT);
        System.out.println("Cagg integration TimescaleDB version: " + version);
        if (container != null) assertThat(version).isEqualTo("2.29.1");
    }

    @AfterAll static void stop() {
        if (container != null) container.stop();
    }

    @BeforeEach void setup() {
        table = PREFIX + counter.incrementAndGet();
        base = LocalDate.now(AggregationWindow.ZONE).minusMonths(4).withDayOfMonth(1).atStartOfDay();
        execute("CREATE TABLE public.\"" + table + "\" (\"timestamp\" timestamp NOT NULL,"
            + "\"deviceId\" varchar NOT NULL,property varchar NOT NULL,\"numberValue\" double precision,"
            + "\"value\" varchar, UNIQUE(\"deviceId\",property,\"timestamp\"))");
        execute("SELECT create_hypertable(?::regclass,'timestamp',chunk_time_interval=>INTERVAL '4 hours')",table);
        properties = config();
        manager = new ContinuousAggregateManager("public",executor,properties,"deviceId");
        org.hswebframework.ezorm.rdb.operator.DatabaseOperator db = database(executor,table);
        db.getMetadata().addFeature(new CancellableAggregationExecutor(executor.factory) {
            @Override public <T> Flux<T> select(Publisher<SqlRequest> requests, ResultWrapper<T, ?> wrapper) {
                return super.select(Flux.from(requests).doOnNext(request -> aggregationSql.add(request.getSql())), wrapper);
            }
        });
        query = new Query(db,properties);
    }

    @Test void ensureExistingRawTableAndReconcilePoliciesIdempotently() {
        properties.getCagg().setDailyEnabled(false);
        manager.ensure(table).block(WAIT);
        assertThat(manager.ready(table,ContinuousAggregateManager.Layer.HOURLY).block(WAIT)).isTrue();
        properties.getCagg().setDailyEnabled(true);
        manager.ensure(table).block(WAIT);
        manager.ensure(table).block(WAIT);
        assertThat(manager.ready(table,ContinuousAggregateManager.Layer.MONTHLY).block(WAIT)).isTrue();
        List<Map<String,Object>> jobs = executor.select(
            "SELECT j.job_id,j.config,j.hypertable_name FROM timescaledb_information.jobs j "
                + "JOIN timescaledb_information.continuous_aggregates c "
                + "ON j.hypertable_name=c.view_name "
                + "WHERE c.view_name LIKE ? AND j.proc_name='policy_refresh_continuous_aggregate'",table+"%")
            .collectList().block(WAIT);
        assertThat(jobs).hasSize(3);
        int job = jobs.stream().filter(row -> (table+"_hourly_agg").equals(row.get("hypertable_name")))
            .map(row -> ((Number)row.get("job_id")).intValue()).findFirst().orElseThrow();
        execute("SELECT alter_job(job_id,scheduled=>false,config=>config || "
            + "jsonb_build_object('start_offset','3 hours')) FROM timescaledb_information.jobs WHERE job_id=?",job);
        manager.ensure(table).block(WAIT);
        assertThat(executor.select("SELECT scheduled,(config->>'start_offset')::interval=INTERVAL '5 years' AS valid"
            + " FROM timescaledb_information.jobs WHERE job_id=?",job).blockFirst(WAIT))
            .containsEntry("scheduled",true).containsEntry("valid",true);
        assertThat(executor.select("SELECT indexdef FROM pg_indexes WHERE tablename IN "
            + "(SELECT materialization_hypertable_name FROM timescaledb_information.continuous_aggregates "
            + "WHERE view_name LIKE ?)",table+"%").collectList().block(WAIT))
            .anySatisfy(row -> assertThat(row.get("indexdef").toString()).contains("\"deviceId\", property, bucket_start DESC"));
    }

    @Test void rawAndAllAggregateLayersAgreeIncludingPartialEdgesAndNullNumbers() {
        seed();
        manager.ensure(table).block(WAIT);
        assertThat(manager.ready(table,ContinuousAggregateManager.Layer.MONTHLY).block(WAIT)).isTrue();
        refresh();
        for (String interval : Arrays.asList("5m","90m","2h","25h","1d","1M","100y",null)) {
            compare(interval,base.plusMinutes(6),base.plusDays(40).plusMinutes(10),100);
        }
        // Start in the middle of an hour, end exactly at a boundary, and include that last point once.
        compare("2h",base.plusMinutes(6),base.plusHours(26),100);
        compare("1M",base.plusMinutes(6),base.plusDays(40).plusMinutes(10),100);
        AggregationRequest average = request("1h",base.toString(),base.plusHours(1).minusNanos(1_000_000).toString(),1);
        List<AggregationData> rows = query.aggregate(table,average,
            new PropertyAggregation("p","average",Aggregation.AVG),
            new PropertyAggregation("p","count",Aggregation.COUNT)).collectList().block(WAIT);
        assertThat(((Number)rows.get(0).get("average").orElseThrow()).doubleValue()).isEqualTo(15);
        assertThat(((Number)rows.get(0).get("count").orElseThrow()).longValue()).isEqualTo(3);
    }

    @Test void lateDataAndUpdatesAreRepairedByNativePoliciesAcrossAllLayers() {
        manager.ensure(table).block(WAIT);
        LocalDateTime now = LocalDateTime.now(AggregationWindow.ZONE);
        List<LocalDateTime> times = Arrays.asList(now.minusHours(5),now.minusDays(5),now.minusMonths(4));
        times.forEach(t -> insert(t,"a","p",10D,"10"));
        runRefreshJobs();
        for (LocalDateTime t : times) {
            insert(t,"a","p",25D,"25"); // historical upsert
            insert(t.plusSeconds(1),"b","p",35D,"35"); // historical insert
        }
        runRefreshJobs(); // no full/manual backfill: exercise configured start/end offsets
        compare("1h",now.minusMonths(5),now,10000);
        compare("1d",now.minusMonths(5),now,200);
        compare("1M",now.minusMonths(5),now,10);
    }

    @Test void naturalWeeksAndQuartersUseCaggAndMatchRawAcrossCalendarBoundaries() {
        base = LocalDateTime.of(2024, 1, 1, 0, 0);
        properties.setRetentionPolicy(null);
        properties.getCagg().setRetention(null);
        List<LocalDateTime> boundaries = Arrays.asList(base, base.plusMonths(3), base.plusMonths(6),
            base.plusMonths(9), base.plusYears(1), LocalDateTime.of(2024, 2, 29, 12, 30),
            LocalDateTime.of(2024, 12, 30, 0, 0));
        int value = 1;
        for (LocalDateTime boundary : boundaries) {
            for (long offset : new long[]{-1_000_000, 0, 1_000_000}) {
                insert(boundary.plusNanos(offset), "a", "p", (double) value, Integer.toString(value++));
            }
        }
        manager.ensure(table).block(WAIT);
        for (ContinuousAggregateManager.Layer layer : ContinuousAggregateManager.Layer.values()) {
            execute("CALL refresh_continuous_aggregate(?::regclass,?::timestamp,?::timestamp)",
                table + layer.suffix, base.minusMonths(1), base.plusMonths(14));
        }
        for (String interval : Arrays.asList("1w", "1q")) {
            String layer = interval.equals("1w") ? "_daily_agg" : "_monthly_agg";
            compare(interval, base.minusDays(1), base.plusYears(1), 100, layer);
            // Include only the leap-day boundary point, not its preceding sample; same for the final edge.
            compare(interval, LocalDateTime.of(2024, 2, 29, 12, 30), base.plusMonths(9), 100, layer);
            compare(interval, base, base.plusYears(1), 2, layer);
        }
        // Check SQL against independent calendar expectations, not just two paths sharing bucketSql.
        for (String[] sample : new String[][]{
            {"1w", "2023-12-31T23:59:59.999", "2023-12-25T00:00:00"},
            {"1w", "2024-01-01T00:00:00", "2024-01-01T00:00:00"},
            {"1w", "2025-01-01T12:00:00", "2024-12-30T00:00:00"},
            {"1q", "2024-02-29T12:00:00", "2024-01-01T00:00:00"},
            {"1q", "2024-04-01T00:00:00", "2024-04-01T00:00:00"},
            {"1q", "2024-07-01T00:00:00", "2024-07-01T00:00:00"},
            {"1q", "2024-10-01T00:00:00", "2024-10-01T00:00:00"},
            {"1q", "2025-01-01T00:00:00", "2025-01-01T00:00:00"}
        }) {
            String sql = "SELECT " + AggregationWindow.bucketSql(Interval.of(sample[0]), "?::timestamp",
                manager.functionSchema().block(WAIT)) + " AS bucket";
            assertThat(executor.select(sql, LocalDateTime.parse(sample[1])).blockFirst(WAIT).get("bucket"))
                .isEqualTo(LocalDateTime.parse(sample[2]));
        }
    }

    @Test void nestedDeviceFiltersAndNonNumericFirstLastStayCorrect() {
        seed();
        manager.ensure(table).block(WAIT);
        refresh();
        AggregationRequest request = request("1h",base.toString(),base.plusHours(1).toString(),3);
        Term group = new Term();
        group.or("thing_id","a").or("thingId","b");
        request.getFilter().setTerms(Arrays.asList(group,Term.of("deviceId","eq","a")));
        List<AggregationData> result = query.aggregate(table,request,
            new PropertyAggregation("p","v",Aggregation.COUNT)).collectList().block(WAIT);
        assertThat(result.stream().map(row -> row.get("v").orElse(0)).mapToLong(v -> ((Number)v).longValue()).sum())
            .isEqualTo(1);
        properties.getCagg().setEnabled(false);
        List<AggregationData> rawResult = query.aggregate(table,request,
            new PropertyAggregation("p","v",Aggregation.COUNT)).collectList().block(WAIT);
        assertThat(rawResult.stream().map(row -> row.get("v").orElse(0)).mapToLong(v -> ((Number)v).longValue()).sum())
            .isEqualTo(1);
        insert(base.plusMinutes(1),"a","text",null,"first");
        insert(base.plusMinutes(2),"a","text",null,"last");
        properties.getCagg().setEnabled(true);
        List<AggregationData> text = query.aggregate(table,request(null,base.toString(),base.plusHours(1).toString(),1),
            new PropertyAggregation("text","first",Aggregation.FIRST),
            new PropertyAggregation("text","last",Aggregation.LAST)).collectList().block(WAIT);
        assertThat(text.get(0).get("first")).contains("first");
        assertThat(text.get(0).get("last")).contains("last");
    }

    @Test void incompatibleExistingViewIsNeverDeletedOrUsed() {
        execute("CREATE MATERIALIZED VIEW \"" + table + "_hourly_agg\" AS SELECT 123 AS old_data");
        manager.ensure(table).block(WAIT);
        assertThat(manager.ready(table,ContinuousAggregateManager.Layer.HOURLY).block(WAIT)).isFalse();
        assertThat(executor.select("SELECT old_data FROM \"" + table + "_hourly_agg\"").blockFirst(WAIT))
            .containsEntry("old_data",123);
        insert(base,"a","p",10D,"10");
        assertThat(query.aggregate(table,request("1h",base.toString(),base.plusHours(1).toString(),2),
            new PropertyAggregation("p","v",Aggregation.SUM)).collectList().block(WAIT)).hasSize(2);
    }

    @Test void compressedHistoricalChunksAndMonitoringRemainUsable() {
        seed();
        manager.ensure(table).block(WAIT);
        refresh();
        execute("ALTER TABLE \"" + table + "\" SET(timescaledb.compress,"
            + "timescaledb.compress_segmentby='\"deviceId\",property',timescaledb.compress_orderby='timestamp DESC')");
        execute("SELECT compress_chunk(chunk) FROM show_chunks(?::regclass,older_than=>INTERVAL '30 days') chunk",table);
        compare("1d",base,base.plusDays(41),50);
        insert(base.plusMinutes(8),"c","p",50D,"50");
        runRefreshJobs();
        compare("1M",base,base.plusDays(41),3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CaggMonitorService monitor = new CaggMonitorService(executor,registry);
        monitor.collectMetrics().block(WAIT);
        assertThat(registry.getMeters()).isNotEmpty();
        monitor.destroy();
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test void productionDdlAndWriterRoundTripIndependentOfJvmAndSessionZone() {
        String fresh = table + "_new";
        org.hswebframework.ezorm.rdb.operator.DatabaseOperator db = database(executor,table);
        List<org.jetlinks.core.metadata.PropertyMetadata> columns = Arrays.asList(
            org.jetlinks.core.metadata.SimplePropertyMetadata.of("timestamp","Time",org.jetlinks.core.metadata.types.DateTimeType.GLOBAL),
            org.jetlinks.core.metadata.SimplePropertyMetadata.of("deviceId","Device",org.jetlinks.core.metadata.types.StringType.GLOBAL),
            org.jetlinks.core.metadata.SimplePropertyMetadata.of("property","Property",org.jetlinks.core.metadata.types.StringType.GLOBAL),
            org.jetlinks.core.metadata.SimplePropertyMetadata.of("numberValue","Number",org.jetlinks.core.metadata.types.DoubleType.GLOBAL),
            org.jetlinks.core.metadata.SimplePropertyMetadata.of("value","Value",org.jetlinks.core.metadata.types.StringType.GLOBAL));
        TimescaleDBRowModeDDLOperations ddl = new TimescaleDBRowModeDDLOperations(
            "device","test",null,new org.jetlinks.community.things.data.operations.DataSettings(),METRIC,db,properties);
        ddl.register0(org.jetlinks.community.things.data.operations.AbstractDDLOperations.MetricType.properties,
                      fresh,columns,true).block(WAIT);
        ddl.register0(org.jetlinks.community.things.data.operations.AbstractDDLOperations.MetricType.properties,
                      fresh,columns,true).block(WAIT);
        assertThat(manager.ready(fresh,ContinuousAggregateManager.Layer.MONTHLY).block(WAIT)).isTrue();
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            org.jetlinks.community.timescaledb.impl.DefaultTimescaleDBDataWriter writer =
                new org.jetlinks.community.timescaledb.impl.DefaultTimescaleDBDataWriter(
                    db,new org.jetlinks.community.buffer.BufferProperties());
            long timestamp = time(base.plusMinutes(10).toString());
            writer.save0(fresh,List.of(Map.of("timestamp",timestamp,"deviceId","a","property","p",
                                             "numberValue",12D,"value","12"))).block(WAIT);
            assertThat(executor.select("SELECT \"timestamp\" FROM \"" + fresh + "\"").blockFirst(WAIT).get("timestamp"))
                .isEqualTo(base.plusMinutes(10));
            List<AggregationData> rows = new Query(db,properties).aggregate(fresh,
                request("1h",base.toString(),base.plusHours(1).minusNanos(1_000_000).toString(),1),
                new PropertyAggregation("p","值",Aggregation.LAST)).collectList().block(WAIT);
            assertThat(((Number)rows.get(0).get("值").orElseThrow()).doubleValue()).isEqualTo(12D);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test void unboundedRawRetentionUsesOpenRefreshStart() {
        properties.setRetentionPolicy(null);
        manager.ensure(table).block(WAIT);
        assertThat(manager.ready(table,ContinuousAggregateManager.Layer.MONTHLY).block(WAIT)).isTrue();
        assertThat(executor.select("SELECT j.config->>'start_offset' IS NULL AS unbounded "
            + "FROM timescaledb_information.jobs j WHERE hypertable_name=? "
            + "AND proc_name='policy_refresh_continuous_aggregate'",table+"_hourly_agg").blockFirst(WAIT))
            .containsEntry("unbounded",true);
    }

    @Test void timeoutAndClientCancellationReleaseBlockedDatabaseQueries() {
        properties.getCagg().setEnabled(false);
        io.r2dbc.pool.ConnectionPool pool = new io.r2dbc.pool.ConnectionPool(
            io.r2dbc.pool.ConnectionPoolConfiguration.builder(executor.factory).initialSize(0).maxSize(1)
                .maxAcquireTime(Duration.ofSeconds(5)).build());
        CancellableAggregationExecutor bounded = new CancellableAggregationExecutor(pool);
        org.hswebframework.ezorm.rdb.operator.DatabaseOperator db = database(executor,table);
        db.getMetadata().addFeature(bounded);
        query = new Query(db,properties);
        TestExecutor pooled = new TestExecutor(pool);
        Object originalTimeout = pooled.select("SELECT current_setting('statement_timeout') AS timeout")
            .blockFirst(WAIT).get("timeout");
        Connection lock = Mono.from(executor.factory.create()).block(WAIT);
        try {
            Mono.from(lock.beginTransaction()).block(WAIT);
            Flux.from(lock.createStatement("LOCK TABLE \""+table+"\" IN ACCESS EXCLUSIVE MODE").execute())
                .concatMap(Result::getRowsUpdated).then().block(WAIT);
            for (boolean timeout : Arrays.asList(true,false)) {
                properties.getAggregation().setTimeout(timeout ? Duration.ofMillis(500) : Duration.ofSeconds(30));
                Flux<AggregationData> result = query.aggregate(table,
                    request("1h",base.toString(),base.plusHours(1).toString(),2),
                    new PropertyAggregation("p","value",Aggregation.COUNT));
                if (timeout) {
                    reactor.test.StepVerifier.create(result)
                        .expectError(java.util.concurrent.TimeoutException.class).verify(WAIT);
                } else {
                    reactor.test.StepVerifier.create(result).thenAwait(Duration.ofMillis(500)).thenCancel().verify(WAIT);
                }
                // The lock is still held: zero waiting backends proves cancellation,
                // rather than merely letting the blocked query finish normally.
                Long pending = Flux.interval(Duration.ofMillis(50))
                    .onBackpressureDrop()
                    .concatMap(tick -> executor.select("SELECT count(*) AS waiting FROM pg_locks "
                        + "WHERE relation=?::regclass AND NOT granted",table).map(row -> ((Number)row.get("waiting")).longValue()))
                    .filter(count -> count == 0 && pool.getMetrics().orElseThrow().acquiredSize() == 0)
                    .next().block(Duration.ofSeconds(5));
                assertThat(pending).isZero();
            }
            assertThat(pooled.select("SELECT current_setting('statement_timeout') AS timeout,1 AS ok")
                .blockFirst(WAIT)).containsEntry("timeout",originalTimeout).containsEntry("ok",1);
            reactor.test.StepVerifier.create(bounded.select("SELECT pg_sleep(10)")
                .contextWrite(context -> context.put(CancellableAggregationExecutor.TIMEOUT_CONTEXT,Duration.ofMillis(100))))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(io.r2dbc.spi.R2dbcException.class)
                    .extracting("sqlState").isEqualTo("57014"))
                .verify(Duration.ofSeconds(5));
        } finally {
            Mono.from(lock.rollbackTransaction()).then(Mono.from(lock.close())).block(WAIT);
            pool.disposeLater().block(WAIT);
        }
    }

    private void seed() {
        insert(base.plusMinutes(10),"a","p",10D,"10");
        insert(base.plusMinutes(5),"b","p",20D,"20"); // earlier timestamp but different device
        insert(base.plusMinutes(7),"z","p",null,"non-number");
        insert(base.plusHours(25),"a","p",30D,"30");
        insert(base.plusHours(26),"b","p",35D,"35");
        insert(base.plusDays(40),"b","p",40D,"40");
        insert(base.plusDays(40),"a","p",45D,"45"); // exact timestamp tie
    }

    private void insert(LocalDateTime time,String device,String property,Double number,String value) {
        execute("INSERT INTO \"" + table + "\" VALUES (?::timestamp,?,?,?::double precision,?)"
            + " ON CONFLICT(\"deviceId\",property,\"timestamp\") DO UPDATE SET "
            + "\"numberValue\"=excluded.\"numberValue\",\"value\"=excluded.\"value\"",
            time,device,property,number,value);
    }

    private void refresh() {
        for (ContinuousAggregateManager.Layer layer : ContinuousAggregateManager.Layer.values()) {
            execute("CALL refresh_continuous_aggregate(?::regclass,?::timestamp,?::timestamp)",
                    table+layer.suffix,base.minusMonths(1),base.plusMonths(3));
        }
    }

    private void runRefreshJobs() {
        for (ContinuousAggregateManager.Layer layer : ContinuousAggregateManager.Layer.values()) {
            Number job = (Number) executor.select("SELECT j.job_id FROM timescaledb_information.jobs j "
                + "JOIN timescaledb_information.continuous_aggregates c ON "
                + "j.hypertable_name=c.view_name "
                + "WHERE c.view_name=? AND j.proc_name='policy_refresh_continuous_aggregate'",table+layer.suffix)
                .blockFirst(WAIT).get("job_id");
            execute("CALL run_job(?)",job.intValue());
        }
    }

    private void compare(String interval,LocalDateTime from,LocalDateTime to,int limit) {
        compare(interval, from, to, limit, null);
    }

    private void compare(String interval,LocalDateTime from,LocalDateTime to,int limit,String expectedLayer) {
        PropertyAggregation[] columns = Arrays.stream(new Aggregation[]{Aggregation.AVG,Aggregation.SUM,Aggregation.COUNT,
            Aggregation.MIN,Aggregation.MAX,Aggregation.FIRST,Aggregation.LAST,Aggregation.TOP})
            .map(agg -> new PropertyAggregation("p",agg.name(),agg)).toArray(PropertyAggregation[]::new);
        AggregationRequest request = request(interval,from.toString(),to.toString(),limit);
        properties.getCagg().setEnabled(false);
        List<AggregationData> raw = query.aggregate(table,request,columns).collectList().block(WAIT);
        properties.getCagg().setEnabled(true);
        aggregationSql.clear();
        List<AggregationData> cagg = query.aggregate(table,request,columns).collectList().block(WAIT);
        if (expectedLayer != null) {
            assertThat(aggregationSql).anySatisfy(sql ->
                assertThat(sql).contains("FROM \"public\".\"" + table + expectedLayer + "\""));
        }
        assertThat(cagg).hasSize(raw.size());
        for (int i=0;i<raw.size();i++) {
            assertThat(cagg.get(i).get("time")).isEqualTo(raw.get(i).get("time"));
            for (PropertyAggregation column : columns) {
                Object expected = raw.get(i).get(column.getAlias()).orElse(null);
                Object actual = cagg.get(i).get(column.getAlias()).orElse(null);
                if (expected instanceof Number && actual instanceof Number) {
                    assertThat(((Number)actual).doubleValue()).as(interval+":"+column.getAlias()+":"+i)
                        .isCloseTo(((Number)expected).doubleValue(),within(0.0000001));
                } else {
                    assertThat(actual).as(interval+":"+column.getAlias()+":"+i).isEqualTo(expected);
                }
            }
        }
    }

    static void execute(String sql,Object... params) {
        executor.execute(SqlRequests.of(sql,params)).block(WAIT);
    }

    static class TestExecutor extends R2dbcReactiveSqlExecutor {
        final ConnectionFactory factory;
        TestExecutor(ConnectionFactory factory) { this.factory=factory; }
        @Override protected Mono<Connection> getConnection() {
            return Mono.from(factory.create()).flatMap(connection ->
                Flux.from(connection.createStatement("SET TIME ZONE 'UTC'").execute())
                    .concatMap(Result::getRowsUpdated).then(Mono.just(connection)));
        }
        @Override protected void releaseConnection(SignalType signal,Connection connection) {
            Mono.from(connection.close()).subscribe();
        }
    }
}
