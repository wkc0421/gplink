package org.jetlinks.community.timescaledb.thing.counter;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Connection;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.reactive.r2dbc.R2dbcReactiveSqlExecutor;
import org.hswebframework.ezorm.rdb.metadata.RDBDatabaseMetadata;
import org.hswebframework.ezorm.rdb.metadata.dialect.Dialect;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.jetlinks.community.timescaledb.TimescaleDBOperations;
import org.jetlinks.community.timescaledb.metadata.TimescaleDBDialectProvider;
import org.jetlinks.community.timescaledb.thing.TimescaleDBThingsDataProperties;
import org.junit.jupiter.api.*;
import org.springframework.r2dbc.connection.ConnectionFactoryUtils;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Explicit disposable database only. Exercises real PostgreSQL locking, SQL and additive migrations. */
class CounterDevicePolicyIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private Executor executor;
    private CounterSnapshotService service;
    private String schema;

    @BeforeEach
    void setup() {
        String url = System.getProperty("counter.test.url");
        Assumptions.assumeTrue(url != null, "Requires disposable counter.test.url");
        ConnectionFactory factory = ConnectionFactories.get(url);
        executor = new Executor(factory);
        schema = "counter_policy_it_" + Long.toUnsignedString(System.nanoTime(), 36);
        execute("CREATE SCHEMA " + schema);
        execute("CREATE TABLE " + table("metrics") + " (\"deviceId\" text,property text,\"timestamp\" timestamp,value text)");
        RDBDatabaseMetadata metadata = new RDBDatabaseMetadata(Dialect.POSTGRES);
        metadata.addFeature(executor);
        var dbSchema = TimescaleDBDialectProvider.GLOBAL.createSchema(schema);
        metadata.addSchema(dbSchema); metadata.setCurrentSchema(dbSchema);
        DatabaseOperator database = mock(DatabaseOperator.class);
        when(database.getMetadata()).thenReturn(metadata);
        TimescaleDBOperations operations = mock(TimescaleDBOperations.class);
        when(operations.database()).thenReturn(database);
        when(operations.connectionFactory()).thenReturn(factory);
        TimescaleDBThingsDataProperties settings = new TimescaleDBThingsDataProperties();
        settings.getCounterSnapshot().setPollInterval(Duration.ofDays(1));
        service = new CounterSnapshotService(operations, settings) {
            @Override Mono<Boolean> caggReady(String raw, String layer) { return Mono.just(false); }
        };
        service.init();
        service.shutdown();
        assertThat(service.list("p").collectList().block(WAIT)).isEmpty();
    }

    @AfterEach
    void cleanup() {
        if (service != null) service.shutdown();
        if (schema != null) execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void overridesAreIndependentIdempotentAndInheritTheCurrentProductDefault() {
        service.upsert("p", "E", true, "metrics", "test").block(WAIT);
        service.syncDeviceProperties("p", "a", Map.of("E", true)).block(WAIT);
        service.syncDeviceProperties("p", "b", Map.of("E", true)).block(WAIT);
        CounterPolicyRow disabled = service.upsertDevice("p", "E", "a", false, "metrics", "test").block(WAIT);
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(service.readContexts("p", "E", List.of("a", "b")).block(WAIT))
            .satisfies(rows -> { assertThat(rows.get("a").isEnabled()).isFalse(); assertThat(rows.get("b").isEnabled()).isTrue(); });
        assertThat(service.upsertDevice("p", "E", "a", false, "metrics", "test").block(WAIT).getPolicyRevision())
            .isEqualTo(disabled.getPolicyRevision());
        service.upsertDevice("p", "E", "a", true, "metrics", "test").block(WAIT);
        service.upsert("p", "E", false, "metrics", "test").block(WAIT);
        assertThat(service.readContexts("p", "E", List.of("a", "b")).block(WAIT))
            .satisfies(rows -> { assertThat(rows.get("a").isEnabled()).isTrue(); assertThat(rows.get("b").isEnabled()).isFalse(); });
        CounterPolicyRow inherited = service.upsertDevice("p", "E", "a", null, "metrics", "test").block(WAIT);
        assertThat(inherited.getMode()).isEqualTo("INHERIT");
        assertThat(inherited.isEnabled()).isFalse();
        assertThat(inherited.getPolicyRevision()).isGreaterThan(disabled.getPolicyRevision());
    }

    @Test
    void deviceOnlyPropertyDoesNotCreateAVisibleProductDefaultAndStopsForInvalidOrDeletedDevices() {
        service.upsertDevice("p", "custom", "a", true, "metrics", "test").block(WAIT);
        assertThat(service.list("p").collectList().block(WAIT)).isEmpty();
        assertThat(service.readContext("p", "custom", "a").block(WAIT).isEnabled()).isTrue();
        assertThat(service.readContext("p", "custom", "b").block(WAIT).isEnabled()).isFalse();
        CounterPolicyQuery query = deviceQuery(); query.setSource("OVERRIDE");
        CounterPolicyPage page = service.queryPolicies(query).block(WAIT);
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getData().get(0).isProductEnabled()).isFalse();
        service.syncDeviceProperties("p", "a", Map.of("custom", false)).block(WAIT);
        assertThat(service.readContext("p", "custom", "a").block(WAIT).isEnabled()).isFalse();
        assertThat(service.queryPolicies(query).block(WAIT).getData().get(0).isEligible()).isFalse();
        service.syncDeviceProperties("p", "a", Map.of("custom", true)).block(WAIT);
        service.markDeviceInactive("a").block(WAIT);
        assertThat(service.readContext("p", "custom", "a").block(WAIT).isEnabled()).isFalse();
        assertThat(service.queryPolicies(query).block(WAIT).getTotal()).isZero();
    }

    @Test
    void disabledProductStillSchedulesOnlyEnabledDevicesAndHonorsTheirFirstDay() throws Exception {
        long oldStart = LocalDate.now(ZONE).minusDays(2).atStartOfDay(ZONE).toInstant().toEpochMilli();
        service.upsert("p", "E", false, "metrics", oldStart, "test").block(WAIT);
        service.upsertDevice("p", "E", "a", true, "metrics", "test").block(WAIT);
        service.syncDeviceProperties("p", "b", Map.of("E", true)).block(WAIT);
        invoke("tick");
        var snapshots = executor.select("SELECT device_id,min(boundary_time) AS first FROM " + table("counter_snapshot_hour")
            + " GROUP BY device_id").collectList().block(WAIT);
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).get("device_id")).isEqualTo("a");
        assertThat((LocalDateTime) snapshots.get(0).get("first")).isAfterOrEqualTo(LocalDate.now(ZONE).atStartOfDay());
        assertThat(service.readContext("p", "E").block(WAIT).isEnabled()).isFalse();
    }

    @Test
    void singleDeviceRebuildDoesNotInvalidateOrOverwriteSiblingSnapshots() throws Exception {
        service.upsert("p", "E", true, "metrics", "test").block(WAIT);
        service.syncDeviceProperties("p", "a", Map.of("E", true)).block(WAIT);
        service.syncDeviceProperties("p", "b", Map.of("E", true)).block(WAIT);
        LocalDateTime boundary = LocalDate.now(ZONE).minusDays(1).atStartOfDay();
        long timestamp = boundary.atZone(ZONE).toInstant().toEpochMilli();
        execute("INSERT INTO " + table("metrics") + " VALUES ('a','E',?::timestamp,'7')", boundary);
        execute("INSERT INTO " + table("counter_snapshot_day")
            + " (product_id,device_id,property_id,boundary_time,value_text,sample_time,state,rule_version,source_version)"
            + " VALUES ('p','b','E',?::timestamp,'99',?::timestamp,'VALID',1,0)", boundary, boundary);
        String task = service.rebuildDevice("p", "E", "a", timestamp, timestamp, "test").block(WAIT);
        assertThat(service.task(task).block(WAIT).getDeviceId()).isEqualTo("a");
        assertThat(service.list("p").blockFirst(WAIT).getPendingFrom()).isNull();
        assertThat(service.resolveBoundaries("p", "E", List.of("b"), List.of(timestamp)).block(WAIT)
            .get("b").get(timestamp).getSource()).isEqualTo("DAY_SNAPSHOT");
        invoke("tick");
        assertThat(service.resolveBoundaries("p", "E", List.of("a"), List.of(timestamp)).block(WAIT)
            .get("a").get(timestamp).getValue()).isEqualTo("7");
        assertThat(executor.select("SELECT value_text FROM " + table("counter_snapshot_day")
            + " WHERE device_id='b' AND boundary_time=?::timestamp", boundary).blockFirst(WAIT).get("value_text")).isEqualTo("99");
    }

    @Test
    void disablingADeviceMidBatchDoesNotSkipEarlierBoundariesForTheNextPage() throws Exception {
        var settings = (TimescaleDBThingsDataProperties.CounterSnapshot) serviceField("settings");
        settings.setBatchDevices(2); settings.setBatchBoundaries(1);
        LocalDateTime from = LocalDate.now(ZONE).minusDays(1).atStartOfDay();
        long start = from.atZone(ZONE).toInstant().toEpochMilli();
        service.upsert("p", "E", true, "metrics", start, "test").block(WAIT);
        for (String device : List.of("a", "b", "c")) {
            service.syncDeviceProperties("p", device, Map.of("E", true)).block(WAIT);
            execute("INSERT INTO " + table("metrics") + " VALUES (?,'E',?::timestamp,'7')", device, from);
        }
        execute("INSERT INTO " + table("counter_snapshot_task")
            + " (id,product_id,property_id,kind,from_time,to_time,cursor_time,status,lease_owner,attempts,total)"
            + " VALUES ('manual','p','E','AUTO',?::timestamp,?::timestamp,?::timestamp,'RUNNING',?,1,6)",
            from, from.plusHours(1), from, serviceField("owner"));
        processOneBatch("manual"); // a and b at the first boundary
        service.upsertDevice("p", "E", "a", false, "metrics", "test").block(WAIT);
        processOneBatch("manual"); // the fixed a/b batch must not pull c into its second boundary
        processOneBatch("manual");
        processOneBatch("manual");
        var counts = executor.select("SELECT device_id,count(*) AS n FROM " + table("counter_snapshot_hour")
            + " WHERE boundary_time>=?::timestamp AND boundary_time<=?::timestamp GROUP BY device_id", from, from.plusHours(1))
            .collectMap(row -> String.valueOf(row.get("device_id")), row -> ((Number) row.get("n")).intValue()).block(WAIT);
        assertThat(counts).containsEntry("a", 1).containsEntry("b", 2).containsEntry("c", 2);
        assertThat(service.task("manual").block(WAIT).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void reenablingProductCatchesUpInheritedDevicesBehindTheSharedWatermarkOnly() throws Exception {
        LocalDateTime from = LocalDate.now(ZONE).minusDays(1).atStartOfDay();
        long start = from.atZone(ZONE).toInstant().toEpochMilli();
        service.upsert("p", "E", true, "metrics", start, "test").block(WAIT);
        for (String device : List.of("a", "b", "c")) service.syncDeviceProperties("p", device, Map.of("E", true)).block(WAIT);
        execute("DELETE FROM " + table("counter_snapshot_task"));
        service.upsertDevice("p", "E", "a", true, "metrics", "test").block(WAIT);
        service.upsertDevice("p", "E", "c", false, "metrics", "test").block(WAIT);
        service.upsert("p", "E", false, "metrics", "test").block(WAIT);
        invoke("tick");
        assertThat(service.list("p").blockFirst(WAIT).getCoverageTo()).isGreaterThan(start);
        assertThat(executor.select("SELECT count(*) AS n FROM " + table("counter_snapshot_hour")
            + " WHERE device_id='b'").blockFirst(WAIT).get("n")).isEqualTo(0L);
        service.upsert("p", "E", true, "metrics", "test").block(WAIT);
        var queued = executor.select("SELECT device_id FROM " + table("counter_snapshot_task")
            + " WHERE kind='CATCHUP' AND status='PENDING'").map(row -> row.get("device_id")).collectList().block(WAIT);
        assertThat(queued).containsExactly("b");
        invoke("tick");
        assertThat(((Number) executor.select("SELECT count(*) AS n FROM " + table("counter_snapshot_hour")
            + " WHERE device_id='b'").blockFirst(WAIT).get("n")).longValue()).isGreaterThan(1L);
        assertThat(executor.select("SELECT count(*) AS n FROM " + table("counter_snapshot_hour")
            + " WHERE device_id='c'").blockFirst(WAIT).get("n")).isEqualTo(0L);
    }

    @Test
    void additiveMigrationPreservesLegacyDefaultsAndSnapshots() {
        service.upsert("p", "E", true, "metrics", "test").block(WAIT);
        service.registerDevices("p", List.of("a")).block(WAIT);
        execute("ALTER TABLE " + table("counter_snapshot_config") + " DROP COLUMN product_configured,DROP COLUMN product_eligible");
        for (String column : List.of("enabled_override", "policy_revision", "eligible", "present", "start_time", "updated_by", "updated_at")) {
            execute("ALTER TABLE " + table("counter_snapshot_device") + " DROP COLUMN " + column);
        }
        service.init(); service.shutdown();
        assertThat(service.list("p").blockFirst(WAIT).isProductConfigured()).isTrue();
        assertThat(service.readContext("p", "E", "a").block(WAIT).isEnabled()).isTrue();
    }

    private CounterPolicyQuery deviceQuery() { CounterPolicyQuery query = new CounterPolicyQuery(); query.setScope("DEVICE"); return query; }
    private String table(String name) { return schema + "." + name; }
    private void execute(String sql, Object... params) { executor.execute(SqlRequests.of(sql, params)).block(WAIT); }
    private Object serviceField(String name) throws Exception {
        Field field = CounterSnapshotService.class.getDeclaredField(name); field.setAccessible(true); return field.get(service);
    }
    @SuppressWarnings("unchecked")
    private void processOneBatch(String taskId) throws Exception {
        Map<String, Object> task = executor.select("SELECT * FROM " + table("counter_snapshot_task") + " WHERE id=?", taskId).blockFirst(WAIT);
        Method method = CounterSnapshotService.class.getDeclaredMethod("processTaskBatch", Map.class); method.setAccessible(true);
        ((Mono<Void>) method.invoke(service, task))
            .as(((org.springframework.transaction.reactive.TransactionalOperator) serviceField("transaction"))::transactional).block(WAIT);
    }
    @SuppressWarnings("unchecked")
    private void invoke(String name) throws Exception {
        Method method = CounterSnapshotService.class.getDeclaredMethod(name); method.setAccessible(true);
        ((Mono<Void>) method.invoke(service)).block(WAIT);
    }
    private static class Executor extends org.hswebframework.web.crud.sql.DefaultR2dbcExecutor {
        private final ConnectionFactory factory;
        Executor(ConnectionFactory factory) {
            this.factory = factory;
            setDefaultFactory(factory); setBindSymbol("$"); setBindCustomSymbol(true);
        }
        @Override protected Mono<Connection> getConnection() { return ConnectionFactoryUtils.getConnection(factory); }
    }
}
