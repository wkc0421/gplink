package org.jetlinks.community.timescaledb.thing.counter;

import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.reactive.ReactiveSqlExecutor;
import org.jetlinks.community.timescaledb.TimescaleDBOperations;
import org.jetlinks.community.timescaledb.impl.DefaultTimescaleDBOperations;
import org.jetlinks.community.timescaledb.thing.ContinuousAggregateManager;
import org.jetlinks.community.timescaledb.thing.ShanghaiTimestampCodec;
import org.jetlinks.community.timescaledb.thing.TimescaleDBThingsDataProperties;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.util.StringUtils;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Durable cumulative-meter boundary snapshots. All SQL uses the actual TimescaleDB pool.
 * The public read method never refreshes aggregates or writes snapshots.
 */
@Slf4j
public class CounterSnapshotService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String HOUR = "counter_snapshot_hour";
    private static final String DAY = "counter_snapshot_day";
    private static final String CONFIG = "counter_snapshot_config";
    private static final String TASK = "counter_snapshot_task";
    private static final String CHANGE = "counter_snapshot_change";
    private static final String DEVICE = "counter_snapshot_device";
    private static final String REFRESH = "counter_cagg_refresh";

    private final TimescaleDBOperations operations;
    private final ReactiveSqlExecutor executor;
    private final TimescaleDBThingsDataProperties.CounterSnapshot settings;
    private final TimescaleDBThingsDataProperties properties;
    private final TransactionalOperator transaction;
    private final MeterRegistry metrics;
    private final AtomicLong pendingTasks = new AtomicLong();
    private final String schema;
    private final String owner = UUID.randomUUID().toString();
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean unavailableWarned = new AtomicBoolean();
    private volatile boolean snapshotsReady;
    private volatile String extensionSchema;
    private volatile Disposable scheduler;

    public CounterSnapshotService(TimescaleDBOperations operations,
                                  TimescaleDBThingsDataProperties properties) {
        this(operations, properties, null);
    }

    public CounterSnapshotService(TimescaleDBOperations operations,
                                  TimescaleDBThingsDataProperties properties,
                                  MeterRegistry metrics) {
        this.operations = operations;
        this.properties = properties;
        this.executor = operations.database().getMetadata().getFeatureNow(ReactiveSqlExecutor.ID);
        this.settings = properties.getCounterSnapshot();
        this.transaction = TransactionalOperator.create(
            new R2dbcTransactionManager(operations.connectionFactory()));
        this.schema = operations.database().getMetadata().getCurrentSchema().getName();
        this.metrics = metrics;
        if (metrics != null) metrics.gauge("gplink.counter.tasks.pending", pendingTasks);
    }

    private void hit(String source) {
        if (metrics != null) metrics.counter("gplink.counter.boundary.resolved", "source", source).increment();
    }

    public void init() {
        if (!settings.isEnabled()) return;
        if (operations instanceof DefaultTimescaleDBOperations) {
            ((DefaultTimescaleDBOperations) operations).setCounterSnapshotService(this);
        }
        try {
            extensionSchema = rows("SELECT n.nspname AS schema_name FROM pg_extension e"
                + " JOIN pg_namespace n ON n.oid=e.extnamespace WHERE e.extname='timescaledb'")
                .next().map(row -> string(row.get("schema_name")))
                .switchIfEmpty(Mono.error(new IllegalStateException("TimescaleDB extension not installed")))
                .block(Duration.ofSeconds(15));
            createSchema().block(Duration.ofSeconds(60));
            ready.set(true);
            snapshotsReady = Boolean.TRUE.equals(validateSnapshotLayer().block(Duration.ofSeconds(15)));
            if (!snapshotsReady) {
                log.warn("Counter snapshot hypertables are structurally incompatible; snapshot reads disabled until repaired");
            }
            scheduler = Flux.interval(settings.getPollInterval())
                .concatMap(ignore -> tick().onErrorResume(error -> {
                    log.warn("Counter snapshot task tick failed", error);
                    return Mono.empty();
                }))
                .subscribe();
        } catch (Throwable error) {
            // Raw writes continue; management and read methods fail explicitly until repaired.
            log.error("Counter snapshot schema unavailable; no snapshot layer will be used", error);
        }
    }

    public void shutdown() {
        if (scheduler != null) scheduler.dispose();
    }

    private Mono<Void> requireReady() {
        return ready.get() ? Mono.empty() : Mono.error(new IllegalStateException(
            "Counter snapshot storage is not ready in TimescaleDB"));
    }

    private static String q(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String table(String name) { return q(schema) + "." + q(name); }

    private String ts(String function) { return q(extensionSchema) + "." + q(function); }

    private Mono<Void> execute(String sql, Object... parameters) {
        return executor.update(Mono.just(SqlRequests.of(sql, parameters))).then();
    }

    private Flux<Map<String, Object>> rows(String sql, Object... parameters) {
        return executor.select(sql, parameters);
    }

    private Flux<Map<String, Object>> writeRows(String sql, Object... parameters) {
        // The shared select executor starts a read-only transaction unless a
        // writable one already exists. DML with RETURNING must join a writable
        // transaction, including the raw-write and task-batch transactions.
        // Consume RETURNING before exposing rows: callers use next(), whose
        // cancellation would otherwise roll back this transaction.
        return Flux.defer(() -> {
            List<Map<String, Object>> result = new ArrayList<>();
            return executor.select(sql, parameters)
                .doOnNext(result::add)
                .then()
                .as(transaction::transactional)
                .thenMany(Flux.fromIterable(result));
        });
    }

    private static LocalDateTime local(long instant) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(instant), ZONE);
    }

    private static long instant(Object value) {
        if (value == null) return 0;
        return ShanghaiTimestampCodec.local(value).atZone(ZONE).toInstant().toEpochMilli();
    }

    private static Long instantOrNull(Object value) {
        return value == null ? null : instant(value);
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() :
            value == null ? 0 : Long.parseLong(String.valueOf(value));
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static long floorHour(long epoch) {
        return local(epoch).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            .atZone(ZONE).toInstant().toEpochMilli();
    }

    static long nextHour(long epoch) {
        return local(epoch).plusHours(1).atZone(ZONE).toInstant().toEpochMilli();
    }

    static long ceilHour(long epoch) {
        long floor = floorHour(epoch);
        return floor == epoch ? floor : nextHour(floor);
    }

    static boolean midnight(long epoch) {
        return local(epoch).toLocalTime().equals(LocalTime.MIDNIGHT);
    }

    private Mono<Void> createSchema() {
        String config = table(CONFIG), task = table(TASK), change = table(CHANGE), device = table(DEVICE);
        String hour = table(HOUR), day = table(DAY), refresh = table(REFRESH);
        return execute("CREATE TABLE IF NOT EXISTS " + config + " ("
                + "product_id text NOT NULL,property_id text NOT NULL,raw_table text NOT NULL,"
                + "enabled boolean NOT NULL DEFAULT false,start_time timestamp without time zone NOT NULL,"
                + "version bigint NOT NULL DEFAULT 1,source_version bigint NOT NULL DEFAULT 0,"
                + "published_version bigint NOT NULL DEFAULT 0,status text NOT NULL DEFAULT 'IDLE',"
                + "coverage_from timestamp without time zone,coverage_to timestamp without time zone,"
                + "pending_from timestamp without time zone,pending_to timestamp without time zone,"
                + "last_success timestamp without time zone,last_failure timestamp without time zone,"
                + "last_error text,task_id text,created_by text,updated_by text,"
                + "created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),"
                + "PRIMARY KEY(product_id,property_id))")
            .then(execute("CREATE TABLE IF NOT EXISTS " + task + " ("
                + "id text PRIMARY KEY,product_id text NOT NULL,property_id text NOT NULL,device_id text,"
                + "kind text NOT NULL,from_time timestamp without time zone NOT NULL,"
                + "to_time timestamp without time zone NOT NULL,cursor_time timestamp without time zone,"
                + "cursor_device text,status text NOT NULL DEFAULT 'PENDING',"
                + "processed bigint NOT NULL DEFAULT 0,total bigint NOT NULL DEFAULT 0,"
                + "source_version bigint NOT NULL DEFAULT 0,dirty_version bigint,"
                + "lease_owner text,lease_until timestamptz,attempts integer NOT NULL DEFAULT 0,"
                + "last_error text,last_success timestamp without time zone,last_failure timestamp without time zone,"
                + "created_by text,created_at timestamptz NOT NULL DEFAULT now(),"
                + "updated_at timestamptz NOT NULL DEFAULT now())"))
            .then(execute("CREATE INDEX IF NOT EXISTS " + q("idx_counter_task_pending")
                + " ON " + task + " (status,lease_until,created_at)"))
            .then(execute("CREATE INDEX IF NOT EXISTS " + q("idx_counter_task_unfinished_rebuild")
                + " ON " + task + " (product_id,property_id,source_version,from_time,to_time)"
                + " WHERE kind='REBUILD' AND status<>'SUCCESS'"))
            .then(execute("CREATE UNIQUE INDEX IF NOT EXISTS " + q("idx_counter_task_active_auto")
                + " ON " + task + " (product_id,property_id,kind)"
                + " WHERE kind='AUTO' AND status IN ('PENDING','RUNNING')"))
            .then(execute("CREATE UNIQUE INDEX IF NOT EXISTS " + q("idx_counter_task_active_dirty")
                + " ON " + task + " (product_id,property_id,device_id,kind)"
                + " WHERE kind='DIRTY' AND status IN ('PENDING','RUNNING')"))
            .then(execute("CREATE TABLE IF NOT EXISTS " + change + " ("
                + "product_id text NOT NULL,property_id text NOT NULL,device_id text NOT NULL,"
                + "dirty_from timestamp without time zone NOT NULL,"
                + "changed_through timestamp without time zone,"
                + "dirty_to timestamp without time zone,version bigint NOT NULL,"
                + "lease_owner text,lease_until timestamptz,"
                + "updated_at timestamptz NOT NULL DEFAULT now(),"
                + "PRIMARY KEY(product_id,property_id,device_id))"))
            .then(execute("CREATE INDEX IF NOT EXISTS " + q("idx_counter_change_dirty")
                + " ON " + change + " (dirty_from,updated_at)"))
            .then(execute("ALTER TABLE " + change
                + " ADD COLUMN IF NOT EXISTS changed_through timestamp without time zone"))
            .then(execute("CREATE TABLE IF NOT EXISTS " + device + " ("
                + "product_id text NOT NULL,property_id text NOT NULL,device_id text NOT NULL,"
                + "PRIMARY KEY(product_id,property_id,device_id))"))
            .then(execute("CREATE TABLE IF NOT EXISTS " + refresh + " ("
                + "product_id text NOT NULL,property_id text NOT NULL,raw_table text NOT NULL,"
                + "layer text NOT NULL DEFAULT 'HOURLY',"
                + "from_time timestamp without time zone NOT NULL,to_time timestamp without time zone NOT NULL,"
                + "source_version bigint NOT NULL,refreshed_at timestamptz NOT NULL DEFAULT now(),"
                + "PRIMARY KEY(product_id,property_id,layer,from_time,to_time))"))
            .then(validateOrdinarySchema().flatMap(valid -> valid ? Mono.empty() :
                Mono.error(new IllegalStateException("Counter snapshot control tables are incompatible"))))
            .then(createSnapshotSchema(hour, day).onErrorResume(error -> {
                log.warn("Counter snapshot hypertables unavailable; control tables remain available and reads fall back", error);
                return Mono.empty();
            }));
    }

    private Mono<Void> createSnapshotSchema(String hour, String day) {
        return createSnapshotTable(hour)
            .then(createSnapshotTable(day))
            .then(execute("SELECT " + ts("create_hypertable") + "(?::regclass,'boundary_time',"
                + "chunk_time_interval=>?::interval,if_not_exists=>TRUE)",
                hour, intervalSql(settings.getHourChunkInterval())))
            .then(execute("SELECT " + ts("create_hypertable") + "(?::regclass,'boundary_time',"
                + "chunk_time_interval=>?::interval,if_not_exists=>TRUE)",
                day, intervalSql(settings.getDayChunkInterval())))
            .then(execute("CREATE INDEX IF NOT EXISTS " + q("idx_counter_hour_lookup")
                + " ON " + hour + " (product_id,property_id,device_id,boundary_time DESC)"))
            .then(execute("CREATE INDEX IF NOT EXISTS " + q("idx_counter_day_lookup")
                + " ON " + day + " (product_id,property_id,device_id,boundary_time DESC)"))
            .then(ensureSnapshotRetention(hour, HOUR))
            .then(ensureSnapshotRetention(day, DAY));
    }

    private Mono<Boolean> validateOrdinarySchema() {
        Mono<Boolean> columns = rows("SELECT count(*) AS n, bool_and(CASE"
            + " WHEN column_name IN ('product_id','property_id','device_id','raw_table','layer',"
            + "'status','kind','id','cursor_device','lease_owner','last_error','task_id',"
            + "'created_by','updated_by') THEN data_type='text'"
            + " WHEN column_name IN ('start_time','coverage_from','coverage_to','pending_from',"
            + "'pending_to','last_success','last_failure','from_time','to_time',"
            + "'cursor_time','dirty_from','dirty_to','changed_through')"
            + " THEN data_type='timestamp without time zone'"
            + " WHEN column_name IN ('version','source_version','published_version',"
            + "'processed','total','dirty_version') THEN data_type='bigint'"
            + " WHEN column_name='enabled' THEN data_type='boolean'"
            + " WHEN column_name='attempts' THEN data_type='integer'"
            + " WHEN column_name IN ('lease_until','created_at','updated_at','refreshed_at')"
            + " THEN data_type='timestamp with time zone'"
            + " ELSE true END) AS types FROM information_schema.columns"
            + " WHERE table_schema=? AND ("
            + "(table_name=? AND column_name IN ('product_id','property_id','raw_table','enabled',"
            + "'start_time','version','source_version','published_version','status',"
            + "'coverage_from','coverage_to','pending_from','pending_to','last_success',"
            + "'last_failure','last_error','task_id','created_by','updated_by','created_at',"
            + "'updated_at')) OR"
            + "(table_name=? AND column_name IN ('id','product_id','property_id','device_id',"
            + "'kind','from_time','to_time','cursor_time','cursor_device','status','processed',"
            + "'total','source_version','dirty_version','lease_owner','lease_until','attempts',"
            + "'last_error','last_success','last_failure','created_by','created_at','updated_at')) OR"
            + "(table_name=? AND column_name IN ('product_id','property_id','device_id',"
            + "'dirty_from','changed_through','dirty_to','version','lease_owner',"
            + "'lease_until','updated_at')) OR"
            + "(table_name=? AND column_name IN ('product_id','property_id','device_id')) OR"
            + "(table_name=? AND column_name IN ('product_id','property_id','raw_table',"
            + "'layer','from_time','to_time','source_version','refreshed_at')))"
            , schema, CONFIG, TASK, CHANGE, DEVICE, REFRESH)
            .next().map(row -> number(row.get("n")) == 65
                && Boolean.TRUE.equals(row.get("types")));
        Mono<Boolean> keys = rows("SELECT count(*) AS n FROM pg_constraint c"
            + " JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace"
            + " WHERE n.nspname=? AND c.contype='p' AND ("
            + "(t.relname=? AND pg_get_constraintdef(c.oid) LIKE 'PRIMARY KEY (product_id, property_id)%') OR"
            + "(t.relname=? AND pg_get_constraintdef(c.oid) LIKE 'PRIMARY KEY (id)%') OR"
            + "(t.relname=? AND pg_get_constraintdef(c.oid) LIKE 'PRIMARY KEY (product_id, property_id, device_id)%') OR"
            + "(t.relname=? AND pg_get_constraintdef(c.oid) LIKE 'PRIMARY KEY (product_id, property_id, device_id)%') OR"
            + "(t.relname=? AND pg_get_constraintdef(c.oid) LIKE"
            + " 'PRIMARY KEY (product_id, property_id, layer, from_time, to_time)%'))",
            schema, CONFIG, TASK, CHANGE, DEVICE, REFRESH)
            .next().map(row -> number(row.get("n")) == 5);
        Mono<Boolean> indexes = rows("SELECT count(*) AS n FROM pg_indexes"
            + " WHERE schemaname=? AND ("
            + "(indexname=? AND indexdef LIKE '%(status, lease_until, created_at)%') OR"
            + "(indexname=? AND indexdef LIKE '%(dirty_from, updated_at)%'))",
            schema, "idx_counter_task_pending", "idx_counter_change_dirty")
            .next().map(row -> number(row.get("n")) == 2);
        return Mono.zip(columns, keys, indexes)
            .map(tuple -> tuple.getT1() && tuple.getT2() && tuple.getT3());
    }

    private Mono<Void> ensureSnapshotRetention(String qualified, String name) {
        String interval = intervalSql(settings.getRetention());
        return execute("SELECT " + ts("add_retention_policy")
            + "(?::regclass,drop_after=>?::interval,if_not_exists=>TRUE)",
            qualified, interval)
            .then(execute("SELECT " + ts("alter_job") + "(j.job_id,scheduled=>true,"
                + "config=>j.config || jsonb_build_object('drop_after',?::text))"
                + " FROM timescaledb_information.jobs j WHERE j.proc_name='policy_retention'"
                + " AND j.hypertable_schema=? AND j.hypertable_name=?",
                interval, schema, name));
    }

    private Mono<Boolean> validateSnapshotLayer() {
        String columns = "SELECT count(*) AS n FROM information_schema.columns"
            + " WHERE table_schema=? AND table_name=? AND ("
            + "(column_name IN ('product_id','device_id','property_id','state','value_text') AND data_type='text')"
            + " OR (column_name IN ('boundary_time','sample_time') AND data_type='timestamp without time zone')"
            + " OR (column_name='rule_version' AND data_type='integer')"
            + " OR (column_name='source_version' AND data_type='bigint'))";
        return Mono.zip(
            rows("SELECT count(*) AS n FROM timescaledb_information.hypertables"
                + " WHERE hypertable_schema=? AND hypertable_name IN (?,?)", schema, HOUR, DAY)
                .next().map(row -> number(row.get("n")) == 2),
            rows(columns, schema, HOUR).next().map(row -> number(row.get("n")) == 9),
            rows(columns, schema, DAY).next().map(row -> number(row.get("n")) == 9),
            rows("SELECT count(*) AS n FROM pg_constraint c"
                + " JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace"
                + " WHERE n.nspname=? AND t.relname IN (?,?) AND c.contype='p'"
                + " AND pg_get_constraintdef(c.oid) LIKE 'PRIMARY KEY (product_id, device_id, property_id, boundary_time)%'",
                schema, HOUR, DAY).next().map(row -> number(row.get("n")) == 2),
            rows("SELECT count(*) AS n FROM pg_indexes WHERE schemaname=?"
                + " AND indexname IN (?,?)"
                + " AND indexdef LIKE '%(product_id, property_id, device_id, boundary_time DESC)%'",
                schema, "idx_counter_hour_lookup", "idx_counter_day_lookup")
                .next().map(row -> number(row.get("n")) == 2),
            rows("SELECT count(*) AS n FROM timescaledb_information.jobs j"
                + " WHERE j.proc_name='policy_retention' AND j.hypertable_schema=?"
                + " AND j.hypertable_name IN (?,?) AND j.scheduled"
                + " AND (j.config->>'drop_after')::interval=?::interval",
                schema, HOUR, DAY, intervalSql(settings.getRetention()))
                .next().map(row -> number(row.get("n")) == 2)
        ).map(tuple -> tuple.getT1() && tuple.getT2() && tuple.getT3()
            && tuple.getT4() && tuple.getT5() && tuple.getT6());
    }

    private Mono<Void> createSnapshotTable(String name) {
        return execute("CREATE TABLE IF NOT EXISTS " + name + " ("
            + "product_id text NOT NULL,device_id text NOT NULL,property_id text NOT NULL,"
            + "boundary_time timestamp without time zone NOT NULL,value_text text,"
            + "sample_time timestamp without time zone,state text NOT NULL,"
            + "rule_version integer NOT NULL DEFAULT 1,source_version bigint NOT NULL DEFAULT 0,"
            + "updated_at timestamptz NOT NULL DEFAULT now(),"
            + "PRIMARY KEY(product_id,device_id,property_id,boundary_time))");
    }

    private static String intervalSql(org.jetlinks.community.Interval interval) {
        return interval.getNumber().longValue() + " " + interval.getUnit().name().toLowerCase(Locale.ROOT);
    }

    private CounterConfig config(Map<String, Object> row) {
        CounterConfig item = new CounterConfig();
        item.setProductId(string(row.get("product_id")));
        item.setPropertyId(string(row.get("property_id")));
        item.setRawTable(string(row.get("raw_table")));
        item.setEnabled(Boolean.TRUE.equals(row.get("enabled")));
        item.setStartTime(instant(row.get("start_time")));
        item.setVersion(number(row.get("version")));
        item.setStatus(string(row.get("status")));
        item.setCoverageFrom(instantOrNull(row.get("coverage_from")));
        item.setCoverageTo(instantOrNull(row.get("coverage_to")));
        item.setPendingFrom(instantOrNull(row.get("pending_from")));
        item.setPendingTo(instantOrNull(row.get("pending_to")));
        item.setLastSuccess(instantOrNull(row.get("last_success")));
        item.setLastFailure(instantOrNull(row.get("last_failure")));
        item.setLastError(string(row.get("last_error")));
        item.setTaskId(string(row.get("task_id")));
        return item;
    }

    private CounterTask task(Map<String, Object> row) {
        CounterTask item = new CounterTask();
        item.setId(string(row.get("id")));
        item.setProductId(string(row.get("product_id")));
        item.setPropertyId(string(row.get("property_id")));
        item.setStatus(string(row.get("status")));
        item.setKind(string(row.get("kind")));
        item.setFrom(instant(row.get("from_time")));
        item.setTo(instant(row.get("to_time")));
        item.setProcessed(number(row.get("processed")));
        item.setTotal(number(row.get("total")));
        item.setLastError(string(row.get("last_error")));
        item.setLastSuccess(instantOrNull(row.get("last_success")));
        item.setLastFailure(instantOrNull(row.get("last_failure")));
        return item;
    }

    public Flux<CounterConfig> list(String productId) {
        return requireReady().thenMany(rows("SELECT * FROM " + table(CONFIG)
            + " WHERE product_id=? ORDER BY property_id", productId).map(this::config));
    }

    public Mono<CounterConfig> upsert(String productId, String propertyId, boolean enabled,
                                      String rawTable, String operator) {
        return upsert(productId, propertyId, enabled, rawTable, null, operator);
    }

    public Mono<CounterConfig> upsert(String productId, String propertyId, boolean enabled,
                                      String rawTable, Long requestedStart, String operator) {
        if (!StringUtils.hasText(productId) || !StringUtils.hasText(propertyId)
            || !safeTable(rawTable)) return Mono.error(new IllegalArgumentException("Invalid product, property or raw table"));
        long today = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant().toEpochMilli();
        long start = requestedStart == null ? today : requestedStart;
        if (start > today) return Mono.error(new IllegalArgumentException("startTime must not be in the future"));
        return requireReady()
            .then(writeRows("INSERT INTO " + table(CONFIG) + " (product_id,property_id,raw_table,enabled,start_time,updated_by) "
                + "VALUES (?,?,?,?,?::timestamp,?) ON CONFLICT (product_id,property_id) DO UPDATE SET "
                + "raw_table=EXCLUDED.raw_table,enabled=EXCLUDED.enabled,version=" + table(CONFIG) + ".version+1,"
                + "status=CASE WHEN EXCLUDED.enabled THEN 'PENDING' ELSE 'DISABLED' END,"
                + "task_id=CASE WHEN EXCLUDED.enabled THEN " + table(CONFIG) + ".task_id ELSE NULL END,"
                + "pending_from=CASE WHEN EXCLUDED.enabled THEN " + table(CONFIG) + ".pending_from ELSE NULL END,"
                + "pending_to=CASE WHEN EXCLUDED.enabled THEN " + table(CONFIG) + ".pending_to ELSE NULL END,"
                + "start_time=CASE WHEN EXCLUDED.enabled AND NOT " + table(CONFIG) + ".enabled "
                + "THEN LEAST(" + table(CONFIG) + ".start_time,EXCLUDED.start_time) ELSE "
                + table(CONFIG) + ".start_time END,updated_by=EXCLUDED.updated_by,updated_at=now()"
                + " WHERE " + table(CONFIG) + ".raw_table=EXCLUDED.raw_table RETURNING *",
                productId, propertyId, rawTable, enabled, local(start), operator).next()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                    "Configured raw table cannot change without explicit migration"))))
            .map(this::config)
            .flatMap(item -> enabled ? Mono.just(item) :
                execute("UPDATE " + table(TASK)
                    + " SET status='CANCELLED',lease_owner=NULL,lease_until=NULL,updated_at=now()"
                    + " WHERE product_id=? AND property_id=? AND status IN ('PENDING','RUNNING','FAILED')",
                    productId, propertyId).thenReturn(item));
    }

    private static boolean safeTable(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]{0,62}");
    }

    private static long hourCount(long from, long to) {
        return Duration.between(local(from), local(to)).toHours() + 1;
    }

    private Mono<Long> rosterCount(String productId, String propertyId) {
        return rows("SELECT count(*) AS n FROM " + table(DEVICE)
            + " WHERE product_id=? AND property_id=?", productId, propertyId)
            .next().map(row -> number(row.get("n"))).defaultIfEmpty(0L);
    }

    public Mono<String> rebuild(String productId, String propertyId, long from, long to, String operator) {
        if (from > to || from <= 0 || to > System.currentTimeMillis()) {
            return Mono.error(new IllegalArgumentException("Invalid rebuild time range"));
        }
        String id = UUID.randomUUID().toString();
        long first = floorHour(from);
        long last = Math.min(ceilHour(to),
            floorHour(System.currentTimeMillis() - settings.getBoundaryDelay().toMillis()));
        if (first > last) return Mono.error(new IllegalArgumentException(
            "Rebuild range has no mature hourly boundary"));
        return requireReady()
            .then(rows("SELECT * FROM " + table(CONFIG) + " WHERE product_id=? AND property_id=?",
                productId, propertyId).next())
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Counter property is not configured")))
            .flatMap(row -> {
                if (!Boolean.TRUE.equals(row.get("enabled"))) {
                    return Mono.error(new IllegalArgumentException("Counter property is disabled"));
                }
                long earliest = LocalDate.now(ZONE).minusYears(5).atStartOfDay(ZONE).toInstant().toEpochMilli();
                if (first < earliest) return Mono.error(new IllegalArgumentException("Rebuild range is outside retention"));
                return rosterCount(productId, propertyId).flatMap(devices ->
                    writeRows("UPDATE " + table(CONFIG)
                        + " SET source_version=source_version+1,task_id=?,pending_from=?::timestamp,"
                        + "pending_to=?::timestamp,status='PENDING',updated_at=now()"
                        + " WHERE product_id=? AND property_id=? AND enabled=true RETURNING source_version",
                        id, local(first), local(last), productId, propertyId).next()
                        .switchIfEmpty(Mono.error(new IllegalStateException(
                            "Counter property was disabled during rebuild creation")))
                        .flatMap(updated -> execute("INSERT INTO " + table(TASK)
                            + " (id,product_id,property_id,kind,from_time,to_time,cursor_time,status,"
                            + "source_version,total,created_by)"
                            + " VALUES (?,?,?,'REBUILD',?::timestamp,?::timestamp,?::timestamp,'PENDING',?,?,?)",
                            id, productId, propertyId, local(first), local(last), local(first),
                            number(updated.get("source_version")),
                            Math.multiplyExact(devices, hourCount(first, last)), operator)
                            .thenReturn(id))
                        .as(transaction::transactional));
            });
    }

    public Mono<CounterTask> task(String taskId) {
        return requireReady().then(rows("SELECT * FROM " + table(TASK) + " WHERE id=?", taskId)
            .next().map(this::task));
    }

    public Mono<CounterTask> cancel(String taskId) {
        return requireReady().then(writeRows("UPDATE " + table(TASK)
            + " SET status='CANCELLED',lease_owner=NULL,lease_until=NULL,updated_at=now()"
            + " WHERE id=? AND status IN ('PENDING','RUNNING','FAILED') RETURNING *", taskId)
            .next()
            .switchIfEmpty(rows("SELECT * FROM " + table(TASK) + " WHERE id=?", taskId).next())
            .flatMap(row -> !"CANCELLED".equals(row.get("status")) ? Mono.just(row)
                : execute("UPDATE " + table(CONFIG)
                    + " SET task_id=NULL,pending_from=NULL,pending_to=NULL,"
                    + "status=CASE WHEN enabled THEN 'IDLE' ELSE status END,updated_at=now()"
                    + " WHERE product_id=? AND property_id=? AND task_id=?",
                    string(row.get("product_id")), string(row.get("property_id")), taskId)
                    .thenReturn(row))
            .as(transaction::transactional)
            .map(this::task));
    }

    /** Pause an invalid product property without deleting snapshots or configuration. */
    public Mono<Void> suspend(String productId, String propertyId, String reason) {
        String message = reason == null ? "Property is no longer numeric" : reason;
        return requireReady().then(execute("UPDATE " + table(CONFIG)
            + " SET enabled=false,status='SUSPENDED',version=version+1,last_error=?,"
            + "task_id=NULL,pending_from=NULL,pending_to=NULL,"
            + "updated_at=now() WHERE product_id=? AND property_id=? AND enabled=true",
            message, productId, propertyId))
            .then(execute("UPDATE " + table(TASK)
                + " SET status='CANCELLED',lease_owner=NULL,lease_until=NULL,updated_at=now()"
                + " WHERE product_id=? AND property_id=? AND status IN ('PENDING','RUNNING','FAILED')",
                productId, propertyId));
    }

    /** Register product devices (including devices without a valid historical sample). */
    public Mono<Void> registerDevices(String productId, Collection<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) return Mono.empty();
        Set<String> ids = deviceIds.stream().filter(StringUtils::hasText)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) return Mono.empty();
        return requireReady().thenMany(rows("SELECT c.property_id,c.start_time,c.coverage_to,c.source_version,"
            + "(SELECT max(t.to_time) FROM " + table(TASK)
            + " t WHERE t.product_id=c.product_id AND t.property_id=c.property_id"
            + " AND t.kind='AUTO' AND t.status IN ('PENDING','RUNNING')) AS active_to"
            + " FROM " + table(CONFIG) + " c WHERE c.product_id=? AND c.enabled=true"
            + " ORDER BY c.property_id FOR UPDATE OF c", productId))
            .concatMap(row -> Flux.fromIterable(ids)
                .concatMap(id -> writeRows("INSERT INTO " + table(DEVICE)
                    + " (product_id,property_id,device_id) VALUES (?,?,?)"
                    + " ON CONFLICT DO NOTHING RETURNING device_id",
                    productId, string(row.get("property_id")), id).next()
                    .flatMap(inserted -> {
                        long end = Math.max(instant(row.get("coverage_to")),
                            instant(row.get("active_to")));
                        if (end == 0) return Mono.empty();
                        long from = instant(row.get("start_time"));
                        long to = end;
                        return execute("INSERT INTO " + table(TASK)
                            + " (id,product_id,property_id,device_id,kind,from_time,to_time,"
                            + "cursor_time,status,source_version,total)"
                            + " VALUES (?,?,?,?,'DIRTY',?::timestamp,?::timestamp,"
                            + "?::timestamp,'PENDING',?,?) ON CONFLICT DO NOTHING",
                            UUID.randomUUID().toString(), productId, string(row.get("property_id")), id,
                            row.get("start_time"), local(end), row.get("start_time"),
                            number(row.get("source_version")), hourCount(from, to));
                    })))
            .then().as(transaction::transactional);
    }

    public Mono<CounterReadContext> readContext(String productId, String propertyId) {
        if (!ready.get()) {
            if (unavailableWarned.compareAndSet(false, true)) {
                log.warn("Counter snapshot infrastructure is unavailable; configured interval reads use the existing raw route");
            }
            return Mono.just(new CounterReadContext(false, 0, 0, 0));
        }
        return rows("SELECT enabled,version,source_version,published_version FROM "
            + table(CONFIG) + " WHERE product_id=? AND property_id=?", productId, propertyId)
            .next().map(row -> new CounterReadContext(Boolean.TRUE.equals(row.get("enabled")),
                number(row.get("version")), number(row.get("source_version")),
                number(row.get("published_version"))))
            .defaultIfEmpty(new CounterReadContext(false, 0, 0, 0));
    }

    public Mono<Boolean> enabled(String productId, String propertyId) {
        return readContext(productId, propertyId).map(CounterReadContext::isEnabled);
    }

    /** Called only after raw UPSERT executes, under the writer's actual TimescaleDB transaction. */
    public Mono<Void> markRawBatch(String rawTable, Collection<Map<String, Object>> data) {
        long started = System.nanoTime();
        if (data.isEmpty()) return Mono.empty();
        if (!ready.get()) {
            Set<String> properties = data.stream().map(row -> string(row.get("property")))
                .filter(Objects::nonNull).collect(Collectors.toSet());
            if (properties.isEmpty()) return Mono.empty();
            List<Object> params = new ArrayList<>();
            params.add(rawTable);
            params.addAll(properties);
            return rows("SELECT 1 FROM " + table(CONFIG)
                + " WHERE raw_table=? AND property_id IN ("
                + placeholders(properties.size()) + ") LIMIT 1", params.toArray())
                .next().flatMap(row -> Mono.<Void>error(new IllegalStateException(
                    "Configured counter change cannot be recorded: snapshot infrastructure unavailable")))
                .onErrorResume(error -> missingTable(error) ? Mono.empty() : Mono.error(error))
                .then();
        }
        Map<String, Map<String, LongRange>> byProperty = new LinkedHashMap<>();
        for (Map<String, Object> row : data) {
            String property = string(row.get("property"));
            String device = string(row.get("deviceId"));
            Object timestamp = row.get("timestamp");
            if (property == null || device == null || timestamp == null) continue;
            long time = timestamp instanceof Number ? ((Number) timestamp).longValue() : instant(timestamp);
            byProperty.computeIfAbsent(property, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(device, ignored -> new LongRange()).add(time);
        }
        return Flux.fromIterable(byProperty.entrySet())
            .concatMap(entry -> markConfiguredProperty(rawTable, entry.getKey(), entry.getValue()))
            .then().doFinally(signal -> {
                if (metrics != null) metrics.timer("gplink.counter.writer.mark")
                    .record(Duration.ofNanos(System.nanoTime() - started));
            });
    }

    private Mono<Void> markConfiguredProperty(String rawTable, String property,
                                              Map<String, LongRange> devices) {
        return writeRows("UPDATE " + table(CONFIG)
            + " SET source_version=source_version+1,updated_at=now()"
            + " WHERE raw_table=? AND property_id=?"
            + " RETURNING product_id,source_version,coverage_to", rawTable, property)
            .next().flatMap(cfg -> rows("SELECT max(to_time) AS active_to FROM " + table(TASK)
                + " WHERE product_id=? AND property_id=? AND status IN ('PENDING','RUNNING')",
                string(cfg.get("product_id")), property).next()
                .flatMap(active -> Flux.fromIterable(devices.entrySet())
                    .concatMap(device -> markDeviceChange(cfg, property, device.getKey(),
                        device.getValue(), instant(active.get("active_to"))))
                    .then()));
    }

    private Mono<Void> markDeviceChange(Map<String, Object> config, String property,
                                        String device, LongRange range, long activeTo) {
        String product = string(config.get("product_id"));
        Mono<Void> roster = execute("INSERT INTO " + table(DEVICE)
            + " (product_id,property_id,device_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",
            product, property, device);
        if (range.from > Math.max(instant(config.get("coverage_to")), activeTo)) return roster;
        return roster.then(execute("INSERT INTO " + table(CHANGE)
            + " (product_id,property_id,device_id,dirty_from,changed_through,version)"
            + " VALUES (?,?,?,?::timestamp,?::timestamp,?) ON CONFLICT (product_id,property_id,device_id)"
            + " DO UPDATE SET dirty_from=LEAST(" + table(CHANGE)
            + ".dirty_from,EXCLUDED.dirty_from),changed_through=GREATEST(COALESCE("
            + table(CHANGE) + ".changed_through,EXCLUDED.changed_through),EXCLUDED.changed_through),"
            + "dirty_to=NULL,version=EXCLUDED.version,updated_at=now()",
            product, property, device, local(range.from), local(range.to),
            number(config.get("source_version"))));
    }

    private static final class LongRange {
        long from = Long.MAX_VALUE;
        long to = Long.MIN_VALUE;
        void add(long time) { from = Math.min(from, time); to = Math.max(to, time); }
    }

    public Mono<Map<String, Map<Long, CounterBoundary>>> resolveBoundaries(
        String productId, String propertyId, Collection<String> deviceIds, Collection<Long> boundaries) {
        return resolveBoundaries(productId, propertyId, deviceIds, boundaries, false);
    }

    public Mono<Map<String, Map<Long, CounterBoundary>>> resolveBoundaries(
        String productId, String propertyId, Collection<String> deviceIds,
        Collection<Long> boundaries, boolean hourlyOnlySnapshot) {
        if (deviceIds.isEmpty() || boundaries.isEmpty()) return Mono.just(Collections.emptyMap());
        if ((long) deviceIds.size() * boundaries.size() > 100_000) {
            return Mono.error(new IllegalArgumentException("Counter boundary result exceeds 100000 cells"));
        }
        return requireReady().then(rows("SELECT * FROM " + table(CONFIG)
            + " WHERE product_id=? AND property_id=? AND enabled=true", productId, propertyId).next())
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Counter property is not enabled")))
            .flatMap(config -> Mono.defer(() -> {
                Map<String, Map<Long, CounterBoundary>> result = new LinkedHashMap<>();
                Set<String> devices = new LinkedHashSet<>(deviceIds);
                Set<Long> times = new LinkedHashSet<>(boundaries);
                long now = System.currentTimeMillis();
                for (String device : devices) {
                    Map<Long, CounterBoundary> values = new LinkedHashMap<>();
                    for (long time : times) {
                        if (time > now) values.put(time, new CounterBoundary(time, null, null,
                            CounterBoundary.NOT_GENERATED, "FUTURE"));
                        if (time > now) hit("future");
                    }
                    result.put(device, values);
                }
                List<BoundaryRequest> requests = pending(result, times);
                Mono<Void> snapshots = snapshotsReady
                    ? (hourlyOnlySnapshot ? Mono.<Void>empty() : loadSnapshots(DAY, productId, propertyId,
                        requests.stream().filter(pair -> midnight(pair.time)).collect(Collectors.toList()), result)
                        ).then(Mono.defer(() -> loadSnapshots(HOUR, productId, propertyId,
                            pending(result, times), result)))
                    : Mono.empty();
                return snapshots.then(Mono.defer(() -> {
                    List<BoundaryRequest> missing = pending(result, times);
                    Mono<Void> daily = hourlyOnlySnapshot ? Mono.empty()
                        : loadCagg(config, propertyId, missing, result, false, "DAILY");
                    return daily.then(Mono.defer(() -> loadCagg(config, propertyId,
                            pending(result, times), result, false, "HOURLY")))
                        .then(Mono.defer(() -> loadRaw(config, propertyId,
                            pending(result, times), result)))
                        .thenReturn(result);
                }));
            }));
    }

    public Mono<Map<String, String>> firstValuesInRange(String productId, String propertyId,
                                                         Collection<String> deviceIds, long start, long end) {
        if (start > end) return Mono.error(new IllegalArgumentException("Invalid time range"));
        if (deviceIds.isEmpty()) return Mono.just(Collections.emptyMap());
        return requireReady().then(rows("SELECT raw_table FROM " + table(CONFIG)
            + " WHERE product_id=? AND property_id=? AND enabled=true", productId, propertyId).next())
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Counter property is not enabled")))
            .flatMap(config -> {
                String rawTable = string(config.get("raw_table"));
                if (!safeTable(rawTable)) return Mono.error(new IllegalStateException("Invalid configured raw table"));
                List<Object> params = new ArrayList<>();
                params.add(propertyId);
                params.add(local(start));
                params.add(local(end));
                params.addAll(deviceIds);
                String sql = "SELECT DISTINCT ON (\"deviceId\") \"deviceId\" AS device_id,value "
                    + "FROM " + table(rawTable) + " WHERE property=? AND \"timestamp\">?::timestamp"
                    + " AND \"timestamp\"<=?::timestamp AND value IS NOT NULL AND \"deviceId\" IN ("
                    + placeholders(deviceIds.size()) + ") ORDER BY \"deviceId\",\"timestamp\" ASC";
                return rows(sql, params.toArray()).collectMap(row -> string(row.get("device_id")),
                    row -> string(row.get("value")))
                    .onErrorResume(error -> missingTable(error)
                        ? Mono.just(Collections.emptyMap()) : Mono.error(error));
            });
    }

    /** First observed reading after a missing outer end; never substitutes zero or a future sample. */
    public Mono<Map<String, String>> firstValuesAfter(String productId, String propertyId,
                                                       Collection<String> deviceIds, long end) {
        long now = System.currentTimeMillis();
        if (end >= now || deviceIds.isEmpty()) return Mono.just(Collections.emptyMap());
        return firstValuesInRange(productId, propertyId, deviceIds, end, now);
    }

    /**
     * Two-point compatibility with GpLink 1.0's completed day/hour tables.
     * Reuse retained snapshots, reconstruct missing last-reading-per-bucket boundaries using indexed raw seeks;
     * this does not change the snapshot/history read policy or write any data.
     * An end uses a successor only when no predecessor exists at all.
     */
    public Mono<Map<String, String>> summaryBoundaryValues(String productId, String propertyId,
                                                          Collection<String> devices, long boundary,
                                                          boolean end, boolean daily) {
        if (devices.isEmpty()) return Mono.just(Collections.emptyMap());
        if (devices.size() > 500) return Mono.error(new IllegalArgumentException("Too many summary devices"));
        return requireReady().then(rows("SELECT raw_table FROM " + table(CONFIG)
            + " WHERE product_id=? AND property_id=? AND enabled=true", productId, propertyId).next())
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Counter property is not enabled")))
            .flatMap(config -> {
                String raw = string(config.get("raw_table"));
                if (!safeTable(raw)) return Mono.error(new IllegalStateException("Invalid configured raw table"));
                long now = System.currentTimeMillis();
                long horizon = daily ? local(now).toLocalDate().atStartOfDay(ZONE).toInstant().toEpochMilli() : floorHour(now);
                long cutoff = Math.min(daily ? boundary : ceilHour(boundary), horizon);
                // Keep retained cumulative readings usable after raw retention expires.
                // A sample exactly at cutoff belongs to the following GpLink1 bucket.
                return resolveBoundaries(productId, propertyId, devices, Collections.singletonList(cutoff))
                    .flatMap(snapshots -> {
                        Map<String, String> retained = new LinkedHashMap<>();
                        for (String device : devices) {
                            Map<Long, CounterBoundary> points = snapshots.get(device);
                            CounterBoundary point = points == null ? null : points.get(cutoff);
                            if (point != null && point.getValue() != null
                                && (point.getSampledAt() == null || point.getSampledAt() < cutoff)) {
                                retained.put(device, point.getValue());
                            }
                        }
                        List<String> missing = devices.stream().filter(id -> !retained.containsKey(id)).collect(Collectors.toList());
                        if (missing.isEmpty()) return Mono.just(retained);
                        return summaryValuesFromRaw(raw, propertyId, missing, boundary, end, daily)
                            .flatMap(found -> {
                                List<String> hourlyMissing = missing.stream().filter(id -> !found.containsKey(id)).collect(Collectors.toList());
                                Mono<Map<String, String>> hourly = !daily || hourlyMissing.isEmpty()
                                    ? Mono.just(Collections.emptyMap())
                                    : summaryValuesFromRaw(raw, propertyId, hourlyMissing, boundary, end, false);
                                return hourly.map(values -> {
                                    retained.putAll(found);
                                    retained.putAll(values);
                                    return retained;
                                });
                            });
                    });
            });
    }

    private Mono<Map<String, String>> summaryValuesFromRaw(String raw, String property,
                                                            Collection<String> devices, long boundary,
                                                            boolean end, boolean daily) {
        String unit = daily ? "day" : "hour";
        long now = System.currentTimeMillis();
        long horizon = daily ? local(now).toLocalDate().atStartOfDay(ZONE).toInstant().toEpochMilli() : floorHour(now);
        long cutoff = daily ? boundary : ceilHour(boundary);
        List<Object> params = new ArrayList<>(devices);
        params.add(property);
        params.add(local(Math.min(cutoff, horizon)));
        String sql = "WITH requested(device_id) AS (VALUES "
            + devices.stream().map(id -> "(?::varchar)").collect(Collectors.joining(",")) + ") "
            + "SELECT r.device_id," + (end ? "COALESCE(p.value,n.value)" : "p.value") + " AS value FROM requested r "
            + "LEFT JOIN LATERAL (SELECT value FROM " + table(raw)
            + " WHERE \"deviceId\"=r.device_id AND property=? AND \"timestamp\"<?::timestamp"
            + " AND value IS NOT NULL ORDER BY \"timestamp\" DESC LIMIT 1) p ON TRUE ";
        if (end) {
            // Seek the first successor bucket, then its last reading, exactly as
            // an earliest hourly/daily row lookup. Never substitute literal zero.
            sql += "LEFT JOIN LATERAL (SELECT date_trunc('" + unit + "',\"timestamp\") AS bucket FROM " + table(raw)
                + " WHERE p.value IS NULL AND \"deviceId\"=r.device_id AND property=?"
                + " AND \"timestamp\">=?::timestamp AND \"timestamp\"<?::timestamp"
                + " AND value IS NOT NULL ORDER BY \"timestamp\" ASC LIMIT 1) b ON TRUE "
                + "LEFT JOIN LATERAL (SELECT value FROM " + table(raw)
                + " WHERE \"deviceId\"=r.device_id AND property=? AND \"timestamp\">=b.bucket"
                + " AND \"timestamp\"<b.bucket+interval '1 " + unit + "'"
                + " AND value IS NOT NULL ORDER BY \"timestamp\" DESC LIMIT 1) n ON TRUE";
            params.add(property);
            // GpLink1's hourly successor is strictly after stat_hour; daily is
            // allowed to use the requested day's end when its baseline is absent.
            params.add(local(daily ? boundary : floorHour(boundary) + 3_600_000L));
            params.add(local(horizon));
            params.add(property);
        }
        return rows(sql, params.toArray()).filter(row -> row.get("value") != null)
            .collectMap(row -> string(row.get("device_id")), row -> string(row.get("value")), LinkedHashMap::new);
    }

    private static String placeholders(int size) {
        return String.join(",", Collections.nCopies(size, "?"));
    }

    private static final class BoundaryRequest {
        final String device;
        final long time;
        BoundaryRequest(String device, long time) { this.device = device; this.time = time; }
    }

    private static List<BoundaryRequest> pending(Map<String, Map<Long, CounterBoundary>> result,
                                                 Collection<Long> times) {
        List<BoundaryRequest> pending = new ArrayList<>();
        result.forEach((device, values) -> times.forEach(time -> {
            if (!values.containsKey(time)) pending.add(new BoundaryRequest(device, time));
        }));
        return pending;
    }

    private Mono<Void> loadSnapshots(String snapshot, String productId, String propertyId,
                                     List<BoundaryRequest> requests,
                                     Map<String, Map<Long, CounterBoundary>> target) {
        if (requests.isEmpty()) return Mono.empty();
        if (requests.size() > 500) {
            return Flux.fromIterable(partition(requests, 500))
                .concatMap(batch -> loadSnapshots(snapshot, productId, propertyId, batch, target)).then();
        }
        List<Object> params = new ArrayList<>();
        String requested = requested(requests, params);
        params.add(productId);
        params.add(propertyId);
        String sql = "WITH requested(device_id,boundary_time) AS (VALUES " + requested + ")"
            + " SELECT s.device_id,s.boundary_time,s.value_text,s.sample_time,s.state FROM requested r JOIN "
            + table(snapshot) + " s ON s.device_id=r.device_id AND s.boundary_time=r.boundary_time"
            + " WHERE s.product_id=? AND s.property_id=?"
            + " AND NOT EXISTS (SELECT 1 FROM " + table(CONFIG)
            + " c WHERE c.product_id=s.product_id AND c.property_id=s.property_id"
            + " AND c.pending_from IS NOT NULL AND c.pending_to IS NOT NULL"
            + " AND c.pending_from<=s.boundary_time AND c.pending_to>=s.boundary_time)"
            + " AND NOT EXISTS (SELECT 1 FROM " + table(TASK)
            + " t WHERE t.product_id=s.product_id AND t.property_id=s.property_id"
            + " AND t.kind='REBUILD' AND t.status<>'SUCCESS'"
            + " AND t.source_version>s.source_version"
            + " AND t.from_time<=s.boundary_time AND t.to_time>=s.boundary_time)"
            + " AND NOT EXISTS (SELECT 1 FROM " + table(CHANGE)
            + " d WHERE d.product_id=s.product_id AND d.property_id=s.property_id"
            + " AND d.device_id=s.device_id AND d.dirty_from<=s.boundary_time"
            + " AND (d.dirty_to IS NULL OR d.dirty_to>=s.boundary_time))";
        return rows(sql, params.toArray()).doOnNext(row -> {
            String device = string(row.get("device_id"));
            long boundary = instant(row.get("boundary_time"));
            Map<Long, CounterBoundary> values = target.get(device);
            if (values != null && !values.containsKey(boundary)) {
                values.put(boundary, new CounterBoundary(boundary, string(row.get("value_text")),
                    instantOrNull(row.get("sample_time")), string(row.get("state")),
                    snapshot.equals(DAY) ? "DAY_SNAPSHOT" : "HOUR_SNAPSHOT"));
                hit(snapshot.equals(DAY) ? "day_snapshot" : "hour_snapshot");
            }
        }).then();
    }

    /**
     * CAGG identifies a candidate sample time only. The precise text value is point-read
     * from raw data because numberValue can lose integer/decimal precision. Refresh proof
     * and dirty-range exclusion are mandatory; any uncertainty leaves the pair for raw.
     */
    private Mono<Void> loadCagg(Map<String, Object> config, String propertyId,
                                List<BoundaryRequest> missing,
                                Map<String, Map<Long, CounterBoundary>> target,
                                boolean workerAfterRefresh,
                                String layer) {
        if (missing.isEmpty()) return Mono.empty();
        String raw = string(config.get("raw_table"));
        if (!safeTable(raw)) return Mono.error(new IllegalStateException("Invalid configured raw table"));
        String view = raw + ("DAILY".equals(layer) ? "_daily_agg" : "_hourly_agg");
        // Legacy CAGGs can exist without last_timestamp/numeric_count. Check
        // compatibility before executing their SQL, including on the AUTO path.
        // A SQL failure must roll back the batch: swallowing it here would leave
        // PostgreSQL's transaction aborted and hide its cause behind 25P02.
        return caggReady(raw, layer)
            .flatMap(compatible -> !compatible ? Mono.empty() :
                Flux.fromIterable(partition(missing, 500))
                    .concatMap(batch -> {
                        List<Object> params = new ArrayList<>();
                        String requested = requested(batch, params);
                        params.add(string(config.get("product_id")));
                        params.add(propertyId);
                        params.add(propertyId);
                        String sql = "WITH requested(device_id,boundary_time) AS (VALUES " + requested + ") "
                            + "SELECT r.device_id,r.boundary_time,c.bucket_start,c.last_timestamp,raw.value,"
                            + "raw.\"timestamp\" AS sample_time FROM requested r "
                            + "LEFT JOIN LATERAL (SELECT c.bucket_start,c.last_timestamp FROM " + table(view)
                            + " c WHERE c.\"deviceId\"=r.device_id AND c.property=?"
                            + " AND c.bucket_start<r.boundary_time AND c.numeric_count>0"
                            + " ORDER BY c.bucket_start DESC LIMIT 1) c ON TRUE "
                            + "LEFT JOIN LATERAL (SELECT x.value,x.\"timestamp\" FROM " + table(raw)
                            + " x WHERE x.\"deviceId\"=r.device_id AND x.property=?"
                            + " AND x.\"timestamp\"<=r.boundary_time AND x.\"timestamp\">=c.last_timestamp"
                            + " AND x.value IS NOT NULL ORDER BY x.\"timestamp\" DESC LIMIT 1) raw ON TRUE "
                            + "WHERE c.bucket_start IS NOT NULL AND EXISTS (SELECT 1 FROM " + table(REFRESH)
                            + " p WHERE p.product_id=? AND p.property_id=?"
                            + " AND p.raw_table=? AND p.layer=?"
                            + " AND p.from_time<=c.bucket_start AND p.to_time>c.bucket_start"
                            + " AND p.source_version>=?)"
                            + (workerAfterRefresh ? "" : " AND NOT EXISTS (SELECT 1 FROM " + table(CHANGE)
                            + " d WHERE d.product_id=? AND d.property_id=? AND d.device_id=r.device_id"
                            + " AND d.dirty_from<=r.boundary_time)");
                        // requested VALUES, then cagg/raw property, proof pair, dirty pair.
                        List<Object> ordered = new ArrayList<>(params.subList(0, batch.size() * 2));
                        ordered.add(propertyId);
                        ordered.add(propertyId);
                        ordered.add(string(config.get("product_id")));
                        ordered.add(propertyId);
                        ordered.add(raw);
                        ordered.add(layer);
                        ordered.add(number(config.get("source_version")));
                        if (!workerAfterRefresh) {
                            ordered.add(string(config.get("product_id")));
                            ordered.add(propertyId);
                        }
                        return rows(sql, ordered.toArray()).doOnNext(row -> {
                            String device = string(row.get("device_id"));
                            long boundary = instant(row.get("boundary_time"));
                            String value = string(row.get("value"));
                            // A null point read cannot prove that the CAGG timestamp denotes a valid sample.
                            if (value != null && target.containsKey(device)
                                && !target.get(device).containsKey(boundary)) {
                                target.get(device).put(boundary, new CounterBoundary(boundary, value,
                                    instantOrNull(row.get("sample_time")), CounterBoundary.VALID, "CAGG"));
                                hit("cagg_" + layer.toLowerCase(Locale.ROOT));
                            }
                        }).then();
                    }).then());
    }

    private Mono<Boolean> relationExists(String name) {
        return rows("SELECT to_regclass(?) AS relation_name", table(name)).next()
            .map(row -> row.get("relation_name") != null)
            .defaultIfEmpty(false);
    }

    private static boolean missingTable(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (e instanceof io.r2dbc.spi.R2dbcException
                && "42P01".equals(((io.r2dbc.spi.R2dbcException) e).getSqlState())) return true;
        }
        return false;
    }

    private static List<List<BoundaryRequest>> partition(List<BoundaryRequest> items, int size) {
        List<List<BoundaryRequest>> parts = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            parts.add(items.subList(i, Math.min(items.size(), i + size)));
        }
        return parts;
    }

    private static String requested(List<BoundaryRequest> pairs, List<Object> params) {
        StringJoiner rows = new StringJoiner(",");
        for (BoundaryRequest pair : pairs) {
            rows.add("(?::text,?::timestamp)");
            params.add(pair.device);
            params.add(local(pair.time));
        }
        return rows.toString();
    }

    private Mono<Void> loadRaw(Map<String, Object> config, String propertyId,
                               List<BoundaryRequest> missing,
                               Map<String, Map<Long, CounterBoundary>> target) {
        if (missing.isEmpty()) return Mono.empty();
        String raw = string(config.get("raw_table"));
        if (!safeTable(raw)) return Mono.error(new IllegalStateException("Invalid configured raw table"));
        // A new product can have a device roster before its raw table exists.
        // Detect that through the catalog rather than raising 42P01 inside the
        // snapshot transaction and then attempting to write NO_VALUE in it.
        return relationExists(raw).flatMap(exists -> {
            if (!exists) {
                for (BoundaryRequest pair : missing) {
                    Map<Long, CounterBoundary> fallbackValues = target.get(pair.device);
                    if (fallbackValues != null) fallbackValues.put(pair.time, new CounterBoundary(pair.time,
                        null, null, CounterBoundary.NO_VALUE, "RAW_ABSENT"));
                    hit("raw_absent");
                }
                return Mono.empty();
            }
            return Flux.fromIterable(partition(missing, 500)).concatMap(batch -> {
                List<Object> params = new ArrayList<>();
                String values = requested(batch, params);
                params.add(propertyId);
                String sql = "WITH requested(device_id,boundary_time) AS (VALUES " + values + ") "
                    + "SELECT r.device_id,r.boundary_time,x.value,x.\"timestamp\" AS sample_time "
                    + "FROM requested r LEFT JOIN LATERAL (SELECT value,\"timestamp\" FROM " + table(raw)
                    + " WHERE \"deviceId\"=r.device_id AND property=? AND \"timestamp\"<=r.boundary_time"
                    + " AND value IS NOT NULL ORDER BY \"timestamp\" DESC LIMIT 1) x ON TRUE";
                return rows(sql, params.toArray()).doOnNext(row -> {
                    String device = string(row.get("device_id"));
                    long boundary = instant(row.get("boundary_time"));
                    Map<Long, CounterBoundary> valuesForDevice = target.get(device);
                    if (valuesForDevice == null) return;
                    String value = string(row.get("value"));
                    valuesForDevice.put(boundary, new CounterBoundary(boundary, value,
                        instantOrNull(row.get("sample_time")),
                        value == null ? CounterBoundary.NO_VALUE : CounterBoundary.VALID, "RAW"));
                    hit("raw");
                }).then();
            }).then();
        });
    }

    private Mono<Void> tick() {
        if (!ready.get() || !snapshotsReady) return Mono.empty();
        return rows("SELECT count(*) AS n FROM " + table(TASK)
            + " WHERE status IN ('PENDING','FAILED')").next()
            .doOnNext(row -> pendingTasks.set(number(row.get("n"))))
            .then(scheduleDirty())
            .then(scheduleDue())
            .thenMany(Flux.range(0, Math.max(1, settings.getConcurrency()))
                .flatMap(ignored -> runAvailable(10), Math.max(1, settings.getConcurrency())))
            .then();
    }

    private Mono<Void> runAvailable(int remaining) {
        if (remaining <= 0) return Mono.empty();
        return claimTask()
            .flatMap(row -> processTask(row)
                .onErrorResume(error -> failTask(string(row.get("id")),
                    number(row.get("attempts")), error))
                .then(runAvailable(remaining - 1)));
    }

    private Mono<Void> scheduleDue() {
        long mature = floorHour(System.currentTimeMillis() - settings.getBoundaryDelay().toMillis());
        return rows("SELECT product_id,property_id,start_time,coverage_to,source_version FROM "
            + table(CONFIG) + " WHERE enabled=true")
            .concatMap(row -> {
                long from = row.get("coverage_to") == null
                    ? instant(row.get("start_time")) : nextHour(instant(row.get("coverage_to")));
                if (from > mature) return Mono.empty();
                String product = string(row.get("product_id"));
                String property = string(row.get("property_id"));
                // Keep a retryable failure as the next AUTO task, including its
                // backoff period. Creating a replacement while it is FAILED
                // would make retrying it conflict with the active-task index.
                return rosterCount(product, property).flatMap(devices -> execute("INSERT INTO " + table(TASK)
                    + " (id,product_id,property_id,kind,from_time,to_time,cursor_time,status,source_version,total)"
                    + " SELECT ?,?,?,'AUTO',?::timestamp,?::timestamp,?::timestamp,'PENDING',?,?"
                    + " WHERE NOT EXISTS (SELECT 1 FROM " + table(TASK) + " retry"
                    + " WHERE retry.product_id=? AND retry.property_id=? AND retry.kind='AUTO'"
                    + " AND retry.status='FAILED' AND retry.attempts<5)"
                    + " ON CONFLICT DO NOTHING", UUID.randomUUID().toString(), product, property,
                    local(from), local(mature), local(from), number(row.get("source_version")),
                    Math.multiplyExact(devices, hourCount(from, mature)), product, property));
            }).then();
    }

    private Mono<Void> scheduleDirty() {
        long mature = floorHour(System.currentTimeMillis() - settings.getBoundaryDelay().toMillis());
        long retainedFrom = LocalDate.now(ZONE).minusYears(5).atStartOfDay(ZONE)
            .toInstant().toEpochMilli();
        return rows("SELECT d.product_id,d.property_id,d.device_id,d.dirty_from,"
            + "d.changed_through,d.version,c.raw_table,c.source_version FROM "
            + table(CHANGE) + " d JOIN " + table(CONFIG)
            + " c ON c.product_id=d.product_id AND c.property_id=d.property_id"
            + " WHERE c.enabled=true AND d.dirty_from<=?::timestamp", local(mature))
            .concatMap(row -> {
                long from = Math.max(ceilHour(instant(row.get("dirty_from"))), retainedFrom);
                String raw = string(row.get("raw_table"));
                if (!safeTable(raw)) return Mono.error(new IllegalStateException("Invalid counter raw table"));
                long changedThrough = row.get("changed_through") == null
                    ? instant(row.get("dirty_from")) : instant(row.get("changed_through"));
                return rows("SELECT \"timestamp\" AS next_time FROM " + table(raw)
                    + " WHERE \"deviceId\"=? AND property=? AND \"timestamp\">?::timestamp"
                    + " AND value IS NOT NULL ORDER BY \"timestamp\" ASC LIMIT 1",
                    string(row.get("device_id")), string(row.get("property_id")), local(changedThrough))
                    .next().map(next -> Math.min(mature,
                        floorHour(instant(next.get("next_time")) - 1)))
                    .defaultIfEmpty(mature)
                    .flatMap(to -> {
                        if (from > to) {
                            return execute("DELETE FROM " + table(CHANGE)
                                + " WHERE product_id=? AND property_id=? AND device_id=? AND version=?",
                                string(row.get("product_id")), string(row.get("property_id")),
                                string(row.get("device_id")), number(row.get("version")));
                        }
                        return execute("UPDATE " + table(CHANGE)
                            + " SET dirty_to=?::timestamp WHERE product_id=? AND property_id=?"
                            + " AND device_id=? AND version=?", local(to),
                            string(row.get("product_id")), string(row.get("property_id")),
                            string(row.get("device_id")), number(row.get("version")))
                            .then(execute("INSERT INTO " + table(TASK)
                    + " (id,product_id,property_id,device_id,kind,from_time,to_time,cursor_time,"
                    + "status,source_version,dirty_version,total)"
                    + " SELECT ?,?,?,?,'DIRTY',?::timestamp,?::timestamp,?::timestamp,'PENDING',?,?,?"
                    + " WHERE NOT EXISTS (SELECT 1 FROM " + table(TASK) + " retry"
                    + " WHERE retry.product_id=? AND retry.property_id=? AND retry.device_id=?"
                    + " AND retry.kind='DIRTY' AND retry.status='FAILED' AND retry.attempts<5)"
                    + " ON CONFLICT DO NOTHING", UUID.randomUUID().toString(),
                    string(row.get("product_id")), string(row.get("property_id")),
                    string(row.get("device_id")), local(from), local(to), local(from),
                    number(row.get("source_version")), number(row.get("version")), hourCount(from, to),
                    string(row.get("product_id")), string(row.get("property_id")),
                    string(row.get("device_id"))));
                    });
            }).then();
    }

    private Mono<Map<String, Object>> claimTask() {
        return writeRows("UPDATE " + table(TASK) + " SET status='RUNNING',lease_owner=?,"
            + "lease_until=now()+interval '2 minutes',attempts=attempts+1,updated_at=now()"
            + " WHERE id=(SELECT candidate.id FROM " + table(TASK) + " candidate"
            + " WHERE candidate.status='PENDING'"
            + " OR (candidate.status='RUNNING' AND candidate.lease_until<now())"
            + " OR (candidate.status='FAILED' AND candidate.attempts<5"
            + " AND candidate.updated_at<now()-interval '5 minutes'"
            + " AND (candidate.kind NOT IN ('AUTO','DIRTY') OR NOT EXISTS (SELECT 1 FROM " + table(TASK)
            + " active WHERE active.product_id=candidate.product_id AND active.property_id=candidate.property_id"
            + " AND active.kind=candidate.kind AND active.id<>candidate.id"
            + " AND active.status IN ('PENDING','RUNNING')"
            + " AND (candidate.kind='AUTO' OR active.device_id IS NOT DISTINCT FROM candidate.device_id))))"
            + " ORDER BY CASE candidate.kind WHEN 'DIRTY' THEN 0 WHEN 'AUTO' THEN 1 ELSE 2 END,candidate.created_at"
            + " FOR UPDATE OF candidate SKIP LOCKED LIMIT 1) RETURNING *", owner).next()
            // Existing FAILED + PENDING pairs are recovered by claiming the
            // active task first. A concurrent scheduler/worker may still win the
            // unique key after our SELECT: retry only after writeRows rolls back.
            .retryWhen(Retry.max(2).filter(CounterSnapshotService::activeTaskConflict)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    private static boolean activeTaskConflict(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof io.r2dbc.spi.R2dbcException
                && "23505".equals(((io.r2dbc.spi.R2dbcException) cause).getSqlState())) {
                String message = String.valueOf(cause.getMessage());
                return message.contains("idx_counter_task_active_auto")
                    || message.contains("idx_counter_task_active_dirty");
            }
        }
        return false;
    }

    private Mono<Void> processTask(Map<String, Object> claimed) {
        return processTaskCycle(string(claimed.get("id")), number(claimed.get("attempts")), 20);
    }

    private Mono<Void> processTaskCycle(String id, long attempt, int remaining) {
        if (remaining <= 0) return Mono.empty();
        return rows("SELECT * FROM " + table(TASK)
                + " WHERE id=? AND status='RUNNING' AND lease_owner=? AND attempts=?",
            id, owner, attempt).next()
            .flatMap(task -> rows("SELECT * FROM " + table(CONFIG)
                + " WHERE product_id=? AND property_id=?", string(task.get("product_id")),
                string(task.get("property_id"))).next()
                .flatMap(config -> {
                    long from = task.get("cursor_time") == null
                        ? instant(task.get("from_time")) : instant(task.get("cursor_time"));
                    long to = Math.min(instant(task.get("to_time")),
                        local(from).plusHours(Math.max(1, settings.getBatchBoundaries()) - 1L)
                            .atZone(ZONE).toInstant().toEpochMilli());
                    return maybeRefresh(config, from, to, string(task.get("kind")), id)
                        .then(processTaskBatch(task).as(transaction::transactional));
                }))
            .timeout(settings.getSqlTimeout())
            .then(rows("SELECT id FROM " + table(TASK)
                + " WHERE id=? AND status='RUNNING' AND lease_owner=? AND attempts=?",
                id, owner, attempt).next())
            .flatMap(ignored -> processTaskCycle(id, attempt, remaining - 1));
    }

    private Mono<Void> processTaskBatch(Map<String, Object> task) {
        String id = string(task.get("id"));
        String product = string(task.get("product_id"));
        String property = string(task.get("property_id"));
        return rows("SELECT id FROM " + table(TASK)
            + " WHERE id=? AND status='RUNNING' AND lease_owner=? AND attempts=? FOR UPDATE",
            id, owner, number(task.get("attempts")))
            .next().flatMap(locked -> rows("SELECT * FROM " + table(CONFIG)
                + " WHERE product_id=? AND property_id=? FOR UPDATE", product, property).next())
            .switchIfEmpty(Mono.error(new IllegalStateException("Counter configuration disappeared")))
            .flatMap(config -> devicesForTask(task).flatMap(devices -> {
                if (!Boolean.TRUE.equals(config.get("enabled"))) {
                    return execute("UPDATE " + table(TASK)
                        + " SET status='CANCELLED',lease_owner=NULL,lease_until=NULL,updated_at=now()"
                        + " WHERE id=? AND status='RUNNING' AND lease_owner=? AND attempts=?",
                        id, owner, number(task.get("attempts")));
                }
                if (devices.isEmpty()) return completeTask(task, config, 0);
                long taskFrom = instant(task.get("from_time"));
                long cursor = task.get("cursor_time") == null ? taskFrom : instant(task.get("cursor_time"));
                long end = Math.min(instant(task.get("to_time")),
                    local(cursor).plusHours(Math.max(1, settings.getBatchBoundaries()) - 1L)
                        .atZone(ZONE).toInstant().toEpochMilli());
                List<Long> boundaries = new ArrayList<>();
                for (long time = cursor; time <= end; time = nextHour(time)) boundaries.add(time);
                Map<String, Map<Long, CounterBoundary>> values = new LinkedHashMap<>();
                for (String device : devices) values.put(device, new LinkedHashMap<>());
                List<BoundaryRequest> pairs = pending(values, boundaries);
                return loadCagg(config, property, pairs, values, true, "DAILY")
                    .then(Mono.defer(() -> loadCagg(config, property,
                        pending(values, boundaries), values, true, "HOURLY")))
                    .then(Mono.defer(() -> loadRaw(config, property, pending(values, boundaries), values)))
                    .then(Mono.defer(() -> writeSnapshots(config, product, property, values)))
                    .then(advanceTask(task, config, devices, boundaries));
            }));
    }

    private Mono<List<String>> devicesForTask(Map<String, Object> task) {
        String specific = string(task.get("device_id"));
        if (specific != null) return Mono.just(Collections.singletonList(specific));
        String after = string(task.get("cursor_device"));
        if (after == null) after = "";
        return rows("SELECT device_id FROM " + table(DEVICE)
            + " WHERE product_id=? AND property_id=? AND device_id>=?"
            + " ORDER BY device_id LIMIT ?", string(task.get("product_id")),
            string(task.get("property_id")), after, Math.max(1, settings.getBatchDevices()))
            .map(row -> string(row.get("device_id"))).collectList();
    }

    private Mono<Void> maybeRefresh(Map<String, Object> config, long from, long to,
                                    String kind, String taskId) {
        if ("AUTO".equals(kind)) return Mono.empty();
        String raw = string(config.get("raw_table"));
        if (!safeTable(raw)) return Mono.empty();
        long refreshFrom = local(from).minusHours(1).atZone(ZONE).toInstant().toEpochMilli();
        long refreshTo = nextHour(to);
        boolean rebuild = "REBUILD".equals(kind);
        String rebuildTaskId = rebuild ? taskId : null;
        // A boundary batch often straddles midnight (e.g. 10:00 through 09:00).
        // Refreshing only complete days inside that batch skips both calendar days.
        // A closed month is handled once as a whole; its proof makes subsequent
        // per-day batches cheap. The open month must retain real-time CAGG semantics.
        LocalDateTime start = local(refreshFrom);
        LocalDateTime end = local(refreshTo);
        LocalDateTime currentDay = LocalDate.now(ZONE).atStartOfDay();
        LocalDateTime currentMonth = currentDay.toLocalDate().withDayOfMonth(1).atStartOfDay();
        List<LocalDateTime> months = calendarStarts(start, end, true);
        List<LocalDateTime> days = calendarStarts(start, end, false);
        return Flux.fromIterable(months)
            .concatMap(month -> month.plusMonths(1).isAfter(currentMonth)
                ? Mono.empty() : refreshHierarchy(config, month, month.plusMonths(1),
                    rebuildTaskId, true))
            .thenMany(Flux.fromIterable(days)
                .concatMap(day -> {
                    LocalDateTime dayEnd = day.plusDays(1);
                    if (!dayEnd.isAfter(currentDay)) {
                        return refreshHierarchy(config, day, dayEnd, rebuildTaskId, false);
                    }
                    // Only the completed part of the current day is refreshable.
                    LocalDateTime completedHour = local(floorHour(System.currentTimeMillis()));
                    LocalDateTime[] partial = openDayRefreshWindow(start, end, day, completedHour);
                    return partial != null && !day.isAfter(currentDay)
                        ? refreshAndPublish(config, "HOURLY", partial[0], partial[1], rebuildTaskId).then()
                        : Mono.empty();
                }))
            .then()
            // A missing view returns false from refreshAndPublish and the worker
            // reads raw. A real CALL failure must leave DIRTY/REBUILD retryable;
            // otherwise the task can say SUCCESS while the CAGG stays stale.
            .doOnError(error -> log.warn("Counter CAGG refresh failed for {}", raw, error));
    }

    static List<LocalDateTime> calendarStarts(LocalDateTime from, LocalDateTime to,
                                             boolean months) {
        List<LocalDateTime> starts = new ArrayList<>();
        LocalDateTime cursor = months ? from.toLocalDate().withDayOfMonth(1).atStartOfDay()
            : from.toLocalDate().atStartOfDay();
        while (cursor.isBefore(to)) {
            starts.add(cursor);
            cursor = months ? cursor.plusMonths(1) : cursor.plusDays(1);
        }
        return starts;
    }

    /** Never claim an open/future hour, and avoid revisiting unaffected earlier hours today. */
    static LocalDateTime[] openDayRefreshWindow(LocalDateTime from, LocalDateTime to,
                                                LocalDateTime day, LocalDateTime completedHour) {
        LocalDateTime start = from.isAfter(day) ? from : day;
        LocalDateTime end = to.isBefore(completedHour) ? to : completedHour;
        return start.isBefore(end) ? new LocalDateTime[]{start, end} : null;
    }

    private Mono<Void> refreshHierarchy(Map<String, Object> config, LocalDateTime from,
                                        LocalDateTime to, String rebuildTaskId,
                                        boolean monthly) {
        return refreshAndPublish(config, "HOURLY", from, to, rebuildTaskId)
            .flatMap(hourlyReady -> hourlyReady
                ? refreshAndPublish(config, "DAILY", from, to, rebuildTaskId)
                : Mono.just(false))
            .flatMap(dailyReady -> dailyReady && monthly
                ? refreshAndPublish(config, "MONTHLY", from, to, rebuildTaskId)
                : Mono.just(dailyReady))
            .then();
    }

    static final class VersionKey {
        final String product;
        final String property;
        VersionKey(String product, String property) {
            this.product = product;
            this.property = property;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof VersionKey)) return false;
            VersionKey key = (VersionKey) other;
            return Objects.equals(product, key.product) && Objects.equals(property, key.property);
        }
        @Override public int hashCode() { return Objects.hash(product, property); }
    }

    private Mono<Map<VersionKey, Long>> sourceVersions(String raw) {
        return rows("SELECT product_id,property_id,source_version FROM " + table(CONFIG)
            + " WHERE raw_table=?", raw).collectMap(
                row -> new VersionKey(string(row.get("product_id")), string(row.get("property_id"))),
                row -> number(row.get("source_version")));
    }

    static Set<VersionKey> unchangedVersions(Map<VersionKey, Long> before,
                                             Map<VersionKey, Long> after) {
        Set<VersionKey> unchanged = new HashSet<>();
        before.forEach((key, version) -> {
            if (Objects.equals(version, after.get(key))) unchanged.add(key);
        });
        return unchanged;
    }

    /** Proofs may be split at task batch boundaries; every point must still be covered. */
    static boolean coversRefreshWindow(List<Map<String, Object>> proofs,
                                       LocalDateTime from, LocalDateTime to) {
        LocalDateTime covered = from;
        for (Map<String, Object> proof : proofs.stream()
            .sorted(Comparator.comparing(row -> ShanghaiTimestampCodec.local(row.get("from_time"))))
            .collect(Collectors.toList())) {
            LocalDateTime start = ShanghaiTimestampCodec.local(proof.get("from_time"));
            LocalDateTime end = ShanghaiTimestampCodec.local(proof.get("to_time"));
            if (start.isAfter(covered)) return false;
            if (end.isAfter(covered)) covered = end;
            if (!covered.isBefore(to)) return true;
        }
        return false;
    }

    private Mono<Boolean> hasRefreshProof(Map<String, Object> config, String layer,
                                          LocalDateTime from, LocalDateTime to,
                                          String rebuildTaskId) {
        String freshForTask = rebuildTaskId == null ? "" :
            " AND refreshed_at >= (SELECT created_at FROM " + table(TASK) + " WHERE id=?)";
        // Overlap predicate below is stored.from < requested.to AND stored.to > requested.from.
        List<Object> parameters = new ArrayList<>(Arrays.asList(
            string(config.get("product_id")), string(config.get("property_id")),
            string(config.get("raw_table")), layer, to, from,
            number(config.get("source_version"))));
        if (rebuildTaskId != null) parameters.add(rebuildTaskId);
        return rows("SELECT from_time,to_time FROM " + table(REFRESH)
            + " WHERE product_id=? AND property_id=? AND raw_table=? AND layer=?"
            + " AND from_time<?::timestamp AND to_time>?::timestamp"
            + " AND source_version>=?" + freshForTask + " ORDER BY from_time",
            parameters.toArray()).collectList()
            .map(proofs -> coversRefreshWindow(proofs, from, to));
    }

    Mono<Boolean> caggReady(String raw, String layer) {
        return ContinuousAggregateManager.counterReady(operations.database(), properties, raw, layer);
    }

    /** One physical CALL publishes proof for every property whose source did not change during it. */
    private Mono<Boolean> refreshAndPublish(Map<String, Object> config, String layer,
                                            LocalDateTime from, LocalDateTime to,
                                            String rebuildTaskId) {
        String raw = string(config.get("raw_table"));
        String view = table(raw + ("MONTHLY".equals(layer) ? "_monthly_agg"
            : "DAILY".equals(layer) ? "_daily_agg" : "_hourly_agg"));
        return caggReady(raw, layer).flatMap(compatible -> !compatible ? Mono.just(false) :
            hasRefreshProof(config, layer, from, to, rebuildTaskId)
                .flatMap(proven -> proven ? Mono.just(true) :
                    rows("SELECT to_regclass(?) AS view_name", view).next()
                    .flatMap(catalog -> catalog.get("view_name") == null ? Mono.just(false) :
                            sourceVersions(raw).flatMap(before -> refreshOutsideTransaction(
                                    view, layer, from, to, rebuildTaskId != null)
                                .then(sourceVersions(raw))
                                .flatMap(after -> publishRefreshProofs(raw, layer, from, to, before, after))
                                .then(hasRefreshProof(config, layer, from, to, rebuildTaskId))))
                        .defaultIfEmpty(false)));
    }

    private Mono<Void> publishRefreshProofs(String raw, String layer, LocalDateTime from,
                                            LocalDateTime to, Map<VersionKey, Long> before,
                                            Map<VersionKey, Long> after) {
        Mono<Set<VersionKey>> sourceReady;
        if ("DAILY".equals(layer) || "MONTHLY".equals(layer)) {
            String sourceLayer = "DAILY".equals(layer) ? "HOURLY" : "DAILY";
            sourceReady = rows("SELECT product_id,property_id,source_version,from_time,to_time FROM "
                + table(REFRESH) + " WHERE raw_table=? AND layer=?"
                + " AND from_time<?::timestamp AND to_time>?::timestamp",
                raw, sourceLayer, to, from)
                .collectList()
                .map(proofs -> before.entrySet().stream()
                    .filter(entry -> coversRefreshWindow(proofs.stream()
                        .filter(row -> entry.getKey().equals(new VersionKey(
                            string(row.get("product_id")), string(row.get("property_id"))))
                            && entry.getValue() == number(row.get("source_version")))
                        .collect(Collectors.toList()), from, to))
                    .map(Map.Entry::getKey).collect(Collectors.toSet()));
        } else {
            sourceReady = Mono.just(before.keySet());
        }
        return sourceReady.flatMap(eligible -> {
            Set<VersionKey> stable = new HashSet<>(eligible);
            stable.retainAll(unchangedVersions(before, after));
            StringJoiner values = new StringJoiner(",");
            List<Object> parameters = new ArrayList<>();
            before.forEach((key, version) -> {
                if (!stable.contains(key)) return;
                values.add("(?,?,?,?,?::timestamp,?::timestamp,?)");
                parameters.add(key.product);
                parameters.add(key.property);
                parameters.add(raw);
                parameters.add(layer);
                parameters.add(from);
                parameters.add(to);
                parameters.add(version);
            });
            if (parameters.isEmpty()) return Mono.empty();
            return execute("INSERT INTO " + table(REFRESH)
                + " (product_id,property_id,raw_table,layer,from_time,to_time,source_version)"
                + " VALUES " + values
                + " ON CONFLICT (product_id,property_id,layer,from_time,to_time)"
                + " DO UPDATE SET source_version=GREATEST(" + table(REFRESH)
                + ".source_version,EXCLUDED.source_version),refreshed_at=now()",
                parameters.toArray());
        });
    }

    private Mono<Void> refreshOutsideTransaction(String view, String layer,
                                                 LocalDateTime from, LocalDateTime to,
                                                 boolean force) {
        long started = System.nanoTime();
        return Mono.usingWhen(Mono.from(operations.connectionFactory().create()),
            connection -> Flux.from(connection.createStatement(
                    "CALL " + ts("refresh_continuous_aggregate")
                        + "($1::regclass,$2::timestamp,$3::timestamp,force=>" + force + ")")
                .bind(0, view).bind(1, from).bind(2, to).execute())
                .concatMap(Result::getRowsUpdated).then(),
            connection -> Mono.from(connection.close()),
            (connection, error) -> Mono.from(connection.close()),
            connection -> Mono.from(connection.close()))
            .doFinally(signal -> {
                if (metrics != null) metrics.timer("gplink.counter.cagg.refresh", "layer", layer)
                    .record(Duration.ofNanos(System.nanoTime() - started));
            });
    }

    private Mono<Void> writeSnapshots(Map<String, Object> config, String product, String property,
                                      Map<String, Map<Long, CounterBoundary>> values) {
        List<CounterRow> hour = new ArrayList<>();
        List<CounterRow> day = new ArrayList<>();
        for (Map.Entry<String, Map<Long, CounterBoundary>> device : values.entrySet()) {
            for (CounterBoundary boundary : device.getValue().values()) {
                CounterRow row = new CounterRow(device.getKey(), boundary);
                hour.add(row);
                if (midnight(boundary.getBoundary())) day.add(row);
            }
        }
        long version = number(config.get("source_version"));
        return upsertSnapshot(HOUR, product, property, version, hour)
            .then(upsertSnapshot(DAY, product, property, version, day));
    }

    private static final class CounterRow {
        final String device;
        final CounterBoundary boundary;
        CounterRow(String device, CounterBoundary boundary) {
            this.device = device;
            this.boundary = boundary;
        }
    }

    private Mono<Void> upsertSnapshot(String name, String product, String property,
                                      long version, List<CounterRow> values) {
        if (values.isEmpty()) return Mono.empty();
        List<Object> params = new ArrayList<>();
        StringJoiner placeholders = new StringJoiner(",");
        for (CounterRow item : values) {
            placeholders.add("(?,?,?,?::timestamp,?,?::timestamp,?,1,?)");
            params.add(product);
            params.add(item.device);
            params.add(property);
            params.add(local(item.boundary.getBoundary()));
            params.add(item.boundary.getValue());
            params.add(item.boundary.getSampledAt() == null ? null : local(item.boundary.getSampledAt()));
            params.add(item.boundary.getState());
            params.add(version);
        }
        String sql = "INSERT INTO " + table(name)
            + " (product_id,device_id,property_id,boundary_time,value_text,sample_time,state,rule_version,source_version)"
            + " VALUES " + placeholders
            + " ON CONFLICT (product_id,device_id,property_id,boundary_time) DO UPDATE SET"
            + " value_text=EXCLUDED.value_text,sample_time=EXCLUDED.sample_time,state=EXCLUDED.state,"
            + " rule_version=EXCLUDED.rule_version,source_version=EXCLUDED.source_version,updated_at=now()";
        return execute(sql, params.toArray());
    }

    private Mono<Void> advanceTask(Map<String, Object> task, Map<String, Object> config,
                                   List<String> devices, List<Long> boundaries) {
        long last = boundaries.get(boundaries.size() - 1);
        long next = nextHour(last);
        String id = string(task.get("id"));
        if (next <= instant(task.get("to_time"))) {
            return execute("UPDATE " + table(TASK)
                + " SET cursor_time=?::timestamp,cursor_device=?,processed=processed+?,"
                + "lease_until=now()+interval '2 minutes',updated_at=now()"
                + " WHERE id=? AND status='RUNNING' AND lease_owner=? AND attempts=?",
                local(next), devices.get(0), (long) devices.size() * boundaries.size(), id, owner,
                number(task.get("attempts")));
        }
        if (task.get("device_id") != null) {
            return completeTask(task, config, (long) devices.size() * boundaries.size());
        }
        return rows("SELECT device_id FROM " + table(DEVICE)
            + " WHERE product_id=? AND property_id=? AND device_id>? ORDER BY device_id LIMIT 1",
            string(task.get("product_id")), string(task.get("property_id")),
            devices.get(devices.size() - 1)).next()
            .flatMap(row -> execute("UPDATE " + table(TASK)
                + " SET cursor_time=from_time,cursor_device=?,processed=processed+?,"
                + "lease_until=now()+interval '2 minutes',updated_at=now()"
                + " WHERE id=? AND status='RUNNING' AND lease_owner=? AND attempts=?",
                string(row.get("device_id")), (long) devices.size() * boundaries.size(), id, owner,
                number(task.get("attempts"))).thenReturn(true))
            .defaultIfEmpty(false)
            .flatMap(hasMore -> hasMore ? Mono.<Void>empty()
                : completeTask(task, config, (long) devices.size() * boundaries.size()));
    }

    private Mono<Void> completeTask(Map<String, Object> task, Map<String, Object> config,
                                    long lastBatchCount) {
        String id = string(task.get("id"));
        String product = string(task.get("product_id"));
        String property = string(task.get("property_id"));
        Mono<Void> clear = "DIRTY".equals(task.get("kind"))
            ? execute("DELETE FROM " + table(CHANGE)
                + " WHERE product_id=? AND property_id=? AND device_id=? AND version=?",
                product, property, string(task.get("device_id")), number(task.get("dirty_version")))
            : Mono.empty();
        return writeRows("UPDATE " + table(TASK)
                + " SET status='SUCCESS',processed=processed+?,lease_owner=NULL,lease_until=NULL,last_success=now()"
                + " AT TIME ZONE 'Asia/Shanghai',updated_at=now()"
                + " WHERE id=? AND status='RUNNING' AND lease_owner=? AND attempts=? RETURNING id",
                lastBatchCount, id, owner, number(task.get("attempts"))).next()
            .flatMap(updated -> clear.then(execute("UPDATE " + table(CONFIG)
                + " SET coverage_from=CASE WHEN ?<>'AUTO' THEN coverage_from"
                + " ELSE LEAST(COALESCE(coverage_from,?::timestamp),?::timestamp) END,"
                + "coverage_to=CASE WHEN ?<>'AUTO' THEN coverage_to"
                + " ELSE GREATEST(COALESCE(coverage_to,?::timestamp),?::timestamp) END,"
                + "published_version=published_version+1,"
                + "status=CASE WHEN task_id=? OR (task_id IS NULL AND ?='AUTO')"
                + " THEN 'IDLE' ELSE status END,"
                + "pending_from=CASE WHEN task_id=? THEN NULL ELSE pending_from END,"
                + "pending_to=CASE WHEN task_id=? THEN NULL ELSE pending_to END,"
                + "task_id=CASE WHEN task_id=? THEN NULL ELSE task_id END,"
                + "last_success=now() AT TIME ZONE 'Asia/Shanghai',"
                + "last_error=NULL,updated_at=now()"
                + " WHERE product_id=? AND property_id=?", string(task.get("kind")),
                task.get("from_time"), task.get("from_time"), string(task.get("kind")),
                task.get("to_time"), task.get("to_time"), id, string(task.get("kind")),
                id, id, id, product, property)));
    }

    private Mono<Void> failTask(String id, long attempt, Throwable error) {
        log.warn("Counter snapshot task {} failed", id, error);
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (message.length() > 1024) message = message.substring(0, 1024);
        String failure = message;
        return writeRows("UPDATE " + table(TASK)
            + " SET status='FAILED',last_error=?,last_failure=now() AT TIME ZONE 'Asia/Shanghai',"
            + "lease_owner=NULL,lease_until=NULL,updated_at=now()"
            + " WHERE id=? AND status='RUNNING' AND lease_owner=? AND attempts=? RETURNING id",
            failure, id, owner, attempt).next()
            .flatMap(updated -> execute("UPDATE " + table(CONFIG)
                + " SET status='FAILED',last_error=?,last_failure=now() AT TIME ZONE 'Asia/Shanghai',"
                + "updated_at=now() WHERE task_id=?", failure, id))
            .as(transaction::transactional);
    }
}
