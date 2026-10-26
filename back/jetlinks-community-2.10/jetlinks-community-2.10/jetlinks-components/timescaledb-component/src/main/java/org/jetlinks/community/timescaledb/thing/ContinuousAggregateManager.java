package org.jetlinks.community.timescaledb.thing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.reactive.ReactiveSqlExecutor;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.jetlinks.community.Interval;
import org.jetlinks.community.timescaledb.TimescaleDBUtils;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Owns cagg DDL for both new and already-existing raw tables. Never drops user data. */
@Slf4j
public final class ContinuousAggregateManager {
    private static final Cache<ReadyKey, Mono<Boolean>> READY_CACHE = Caffeine
        .newBuilder()
        .expireAfterWrite(60, TimeUnit.SECONDS)
        .maximumSize(10_000)
        .build();
    private static final Cache<ExtensionSchemaKey, Mono<String>> EXTENSION_SCHEMA_CACHE = Caffeine
        .newBuilder()
        .expireAfterWrite(60, TimeUnit.SECONDS)
        .maximumSize(256)
        .build();

    enum Layer {
        HOURLY("_hourly_agg", Interval.ofHours(1), "1 hour"),
        DAILY("_daily_agg", Interval.ofDays(1), "1 day"),
        MONTHLY("_monthly_agg", Interval.of(1, Interval.month), "1 month");

        final String suffix;
        final Interval width;
        final String endOffset;

        Layer(String suffix, Interval width, String endOffset) {
            this.suffix = suffix;
            this.width = width;
            this.endOffset = endOffset;
        }
    }

    private final String schema;
    private final ReactiveSqlExecutor executor;
    private final TimescaleDBThingsDataProperties properties;
    private final String thingColumn;

    ContinuousAggregateManager(DatabaseOperator database, TimescaleDBThingsDataProperties properties,
                               String thingColumn) {
        this(database.getMetadata().getCurrentSchema().getName(),
             database.getMetadata().getFeatureNow(ReactiveSqlExecutor.ID), properties, thingColumn);
    }

    ContinuousAggregateManager(String schema, ReactiveSqlExecutor executor,
                               TimescaleDBThingsDataProperties properties, String thingColumn) {
        this.schema = schema;
        this.executor = executor;
        this.properties = properties;
        this.thingColumn = thingColumn;
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    String qualified(String table) {
        return quote(schema) + "." + quote(table);
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    List<Layer> layers() {
        if (!properties.getCagg().isEnabled()) {
            return Collections.emptyList();
        }
        List<Layer> layers = new ArrayList<>();
        layers.add(Layer.HOURLY);
        if (properties.getCagg().isDailyEnabled()) {
            layers.add(Layer.DAILY);
            if (properties.getCagg().isMonthlyEnabled()) {
                layers.add(Layer.MONTHLY);
            }
        }
        return layers;
    }

    Mono<Void> ensure(String raw) {
        List<Layer> configured = layers();
        return functionSchema()
            .flatMap(functionSchema -> ensureRawRetention(raw, functionSchema)
                .then(Flux.fromIterable(configured)
                    .concatMap(layer -> ensureLayer(raw, layer, functionSchema))
                    .then()
                    .onErrorResume(error -> {
                        // Raw writes remain available. Query routing checks the catalog before using any layer.
                        log.warn("Continuous aggregate unavailable for {}.{}; using raw queries until repaired",
                                 schema, raw, error);
                        return Mono.empty();
                    })));
    }

    /** Reconcile dormant product property tables at startup, not only tables registered this run. */
    Mono<Long> reconcileExistingRawRetention(String configuredPropertyPrefix) {
        if (!StringUtils.hasText(configuredPropertyPrefix)) {
            return Mono.error(new IllegalArgumentException("Property table prefix is empty"));
        }
        String prefix = TimescaleDBUtils.getTableName(configuredPropertyPrefix);
        String rawTables = "SELECT h.hypertable_name AS raw_table"
            + " FROM timescaledb_information.hypertables h"
            + " WHERE h.hypertable_schema=?"
            + " AND (SELECT count(*) FROM information_schema.columns c"
            + " WHERE c.table_schema=h.hypertable_schema AND c.table_name=h.hypertable_name"
            + " AND c.column_name IN ('property','value','numberValue','timestamp'))=4"
            + " AND EXISTS (SELECT 1 FROM information_schema.columns c"
            + " WHERE c.table_schema=h.hypertable_schema AND c.table_name=h.hypertable_name"
            + " AND c.column_name IN ('deviceId','thingId'))"
            + " ORDER BY h.hypertable_name";
        return functionSchema().flatMapMany(functionSchema -> executor.select(rawTables, schema)
            .map(row -> Objects.toString(row.get("raw_table"), ""))
            .filter(raw -> rawPropertyTableCandidate(raw, prefix))
            .concatMap(raw -> ensureRawRetention(raw, functionSchema).thenReturn(raw)))
            .count();
    }

    static boolean rawPropertyTableCandidate(String table, String normalizedPrefix) {
        return StringUtils.hasText(table) && StringUtils.hasText(normalizedPrefix)
            && (table.startsWith(normalizedPrefix) || table.contains("_properties_"));
    }

    /** Existing raw hypertables must not retain an older, shorter policy after configuration changes. */
    private Mono<Void> ensureRawRetention(String raw, String functionSchema) {
        String find = "SELECT job_id FROM timescaledb_information.jobs"
            + " WHERE proc_name='policy_retention' AND hypertable_schema=? AND hypertable_name=?";
        return executor.select(find, schema, raw).collectList().flatMap(jobs -> {
            if (jobs.size() > 1) {
                return Mono.error(new IllegalStateException(
                    "Multiple raw retention policies require manual reconciliation: " + qualified(raw)));
            }
            if (properties.getRetentionPolicy() == null) {
                return jobs.isEmpty() ? Mono.empty() : execute("SELECT "
                    + function(functionSchema, "alter_job") + "(j.job_id,scheduled=>false)"
                    + " FROM timescaledb_information.jobs j"
                    + " WHERE j.proc_name='policy_retention'"
                    + " AND j.hypertable_schema=? AND j.hypertable_name=?", schema, raw);
            }
            String retention = AggregationWindow.sqlInterval(properties.getRetentionPolicy());
            Mono<Void> create = jobs.isEmpty()
                ? execute("SELECT " + function(functionSchema, "add_retention_policy")
                    + "(?::regclass,drop_after=>?::interval,"
                    + "schedule_interval=>interval '1 day',if_not_exists=>true)",
                    qualified(raw), retention)
                : Mono.empty();
            return create.then(execute("SELECT " + function(functionSchema, "alter_job")
                    + "(j.job_id,scheduled=>true,schedule_interval=>interval '1 day',"
                    + "config=>j.config || jsonb_build_object('drop_after',?::text))"
                    + " FROM timescaledb_information.jobs j"
                    + " WHERE j.proc_name='policy_retention'"
                    + " AND j.hypertable_schema=? AND j.hypertable_name=?",
                    retention, schema, raw))
                .then(executor.select("SELECT scheduled AND schedule_interval=interval '1 day'"
                        + " AND (config->>'drop_after')::interval=?::interval AS ready"
                        + " FROM timescaledb_information.jobs"
                        + " WHERE proc_name='policy_retention'"
                        + " AND hypertable_schema=? AND hypertable_name=?",
                        retention, schema, raw).singleOrEmpty()
                    .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Raw retention policy is missing: " + qualified(raw)))))
                .flatMap(job -> Boolean.TRUE.equals(job.get("ready"))
                    ? Mono.empty() : Mono.error(new IllegalStateException(
                        "Raw retention policy does not match configuration: " + qualified(raw))));
            });
    }

    private Mono<Void> ensureLayer(String raw, Layer layer, String functionSchema) {
        String view = raw + layer.suffix;
        String ddl = createSql(raw, layer, functionSchema);
        // Serialize concurrent registrations, and mark only objects created by this implementation.
        String create =
            "DO $cagg$ BEGIN PERFORM pg_advisory_xact_lock(hashtextextended("
                + literal(qualified(view)) + ",0)); IF to_regclass(" + literal(qualified(view))
                + ") IS NULL THEN EXECUTE " + literal(ddl) + "; EXECUTE "
                + literal("COMMENT ON VIEW " + qualified(view) + " IS " + literal(marker(raw, layer)))
                + "; END IF; END $cagg$";
        return execute(create)
            .then(compatible(raw, layer))
            .flatMap(valid -> valid ? Mono.empty() : Mono.error(new IllegalStateException(
                "Incompatible continuous aggregate (not rebuilt automatically): " + qualified(view))))
            .then(execute("ALTER MATERIALIZED VIEW " + qualified(view)
                          + " SET (timescaledb.materialized_only=false)"))
            .then(execute("CREATE INDEX IF NOT EXISTS " + quote("idx_cagg_" + hash(qualified(view)))
                          + " ON " + qualified(view) + " (" + quote(thingColumn) + ",property,bucket_start DESC)"))
            .then(ensurePolicies(view, layer, functionSchema))
            .then(Mono.defer(() -> {
                invalidate(raw);
                return ready(raw, layer);
            }))
            .flatMap(valid -> valid ? Mono.empty() : Mono.error(new IllegalStateException(
                "Continuous aggregate verification failed: " + qualified(view))));
    }

    String createSql(String raw, Layer layer, String functionSchema) {
        String source = qualified(raw + (layer == Layer.HOURLY ? "" :
                                        layer == Layer.DAILY ? Layer.HOURLY.suffix : Layer.DAILY.suffix));
        boolean hourly = layer == Layer.HOURLY;
        String time = hourly ? "\"timestamp\"" : "bucket_start";
        String first = function(functionSchema, "first");
        String last = function(functionSchema, "last");
        String values = hourly
            ? first + "(\"numberValue\",\"timestamp\") AS first_value,"
                + last + "(\"numberValue\",\"timestamp\") AS last_value,"
                + "min(\"timestamp\") AS first_timestamp,max(\"timestamp\") AS last_timestamp,"
                + "sum(\"numberValue\") AS sum_value,min(\"numberValue\") AS min_value,"
                + "max(\"numberValue\") AS max_value,count(*) AS sample_count,"
                + "count(\"numberValue\") AS numeric_count"
            : first + "(first_value,first_timestamp) AS first_value,"
                + last + "(last_value,last_timestamp) AS last_value,"
                + "min(first_timestamp) AS first_timestamp,max(last_timestamp) AS last_timestamp,"
                + "sum(sum_value) AS sum_value,min(min_value) AS min_value,max(max_value) AS max_value,"
                + "sum(sample_count) AS sample_count,sum(numeric_count) AS numeric_count";
        return "CREATE MATERIALIZED VIEW " + qualified(raw + layer.suffix)
            + " WITH (timescaledb.continuous,timescaledb.materialized_only=false) AS SELECT "
            // All hierarchical layers must declare the same origin. For 1h/1d this
            // is the same grid as Jan 3; Jan 1 also satisfies calendar-month bucketing.
            + function(functionSchema, "time_bucket")
            + "(INTERVAL '" + AggregationWindow.sqlInterval(layer.width) + "'," + time
            + ", TIMESTAMP '2000-01-01 00:00:00') AS bucket_start,"
            + quote(thingColumn) + ",property," + values + " FROM " + source
            + " GROUP BY 1," + quote(thingColumn) + ",property WITH NO DATA";
    }

    static String function(String schema, String name) {
        return quote(schema) + "." + quote(name);
    }

    Mono<String> functionSchema() {
        ExtensionSchemaKey key = new ExtensionSchemaKey(executor);
        Mono<String> value = EXTENSION_SCHEMA_CACHE
            .asMap()
            .computeIfAbsent(key, ignore -> resolveFunctionSchema().cache());
        return value.doOnError(error -> EXTENSION_SCHEMA_CACHE.asMap().remove(key, value));
    }

    private Mono<String> resolveFunctionSchema() {
        return executor
            .select("SELECT n.nspname AS extension_schema FROM pg_extension e "
                + "JOIN pg_namespace n ON n.oid=e.extnamespace WHERE e.extname='timescaledb'")
            .singleOrEmpty()
            .map(row -> Objects.toString(row.get("extension_schema"), ""))
            .filter(StringUtils::hasText)
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "TimescaleDB extension schema cannot be resolved")));
    }

    private String marker(String raw, Layer layer) {
        // The extension can be installed in a schema different from the data
        // schema. Keep the compatibility marker independent of that location.
        return "gplink:cagg:v3:shanghai:"
            + hash(createSql(raw, layer, "__timescaledb_extension__"));
    }

    private static String hash(String value) {
        return DigestUtils.md5DigestAsHex(value.getBytes(StandardCharsets.UTF_8));
    }

    private Mono<Boolean> compatible(String raw, Layer layer) {
        return executor.select(
            "SELECT c.materialized_only,"
                + "obj_description(to_regclass(?),'pg_class') AS version,"
                + "(SELECT count(*) FROM pg_attribute a WHERE a.attrelid=to_regclass(?)"
                + " AND NOT a.attisdropped AND a.attname IN"
                + " ('bucket_start','property','first_value','last_value','first_timestamp','last_timestamp',"
                + "'sum_value','min_value','max_value','sample_count','numeric_count',?)) AS columns "
                + "FROM timescaledb_information.continuous_aggregates c WHERE view_schema=? AND view_name=?",
            qualified(raw + layer.suffix), qualified(raw + layer.suffix), thingColumn, schema, raw + layer.suffix)
            .singleOrEmpty()
            .map(row -> marker(raw, layer).equals(row.get("version"))
                && ((Number) row.get("columns")).intValue() == 12)
            .defaultIfEmpty(false);
    }

    Mono<Boolean> ready(String raw, Layer layer) {
        ReadyKey key = new ReadyKey(executor, schema, raw, layer);
        Mono<Boolean> value = READY_CACHE
            .asMap()
            .computeIfAbsent(key, ignore -> readyUncached(raw, layer).cache());
        return value.doOnError(error -> READY_CACHE.asMap().remove(key, value));
    }

    /** Counter workers use the same structural and policy check as aggregate queries. */
    public static Mono<Boolean> counterReady(DatabaseOperator database,
                                             TimescaleDBThingsDataProperties properties,
                                             String raw, String layer) {
        Layer requested;
        try {
            requested = Layer.valueOf(layer);
        } catch (IllegalArgumentException invalid) {
            return Mono.just(false);
        }
        ContinuousAggregateManager manager = new ContinuousAggregateManager(
            database, properties, "deviceId");
        return manager.layers().contains(requested)
            ? manager.ready(raw, requested) : Mono.just(false);
    }

    private Mono<Boolean> readyUncached(String raw, Layer layer) {
        // On a cache miss, verify this layer and every hierarchical source layer.
        return Flux.fromArray(Layer.values())
            .take(layer.ordinal() + 1L)
            .concatMap(source -> compatible(raw, source)
                .flatMap(valid -> !valid ? Mono.just(false) : policiesMatch(raw + source.suffix, source)))
            .all(Boolean.TRUE::equals);
    }

    void invalidate(String raw) {
        // DDL and read paths can use different executor wrappers for the same
        // datasource, so invalidate every cached view of this schema/table.
        READY_CACHE.asMap().keySet().removeIf(key -> key.schema.equals(schema)
            && key.raw.equals(raw));
    }

    static void clearReadyCache() {
        READY_CACHE.invalidateAll();
        EXTENSION_SCHEMA_CACHE.invalidateAll();
    }

    private static final class ExtensionSchemaKey {
        private final ReactiveSqlExecutor executor;

        private ExtensionSchemaKey(ReactiveSqlExecutor executor) {
            this.executor = executor;
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj
                || (obj instanceof ExtensionSchemaKey
                    && executor == ((ExtensionSchemaKey) obj).executor);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(executor);
        }
    }

    private static final class ReadyKey {
        private final ReactiveSqlExecutor executor;
        private final String schema;
        private final String raw;
        private final Layer layer;

        private ReadyKey(ReactiveSqlExecutor executor, String schema, String raw, Layer layer) {
            this.executor = executor;
            this.schema = schema;
            this.raw = raw;
            this.layer = layer;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ReadyKey)) {
                return false;
            }
            ReadyKey that = (ReadyKey) obj;
            return executor == that.executor
                && schema.equals(that.schema)
                && raw.equals(that.raw)
                && layer == that.layer;
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(executor);
            result = 31 * result + schema.hashCode();
            result = 31 * result + raw.hashCode();
            result = 31 * result + layer.hashCode();
            return result;
        }
    }

    private Mono<Boolean> policiesMatch(String view, Layer layer) {
        String start = properties.getRetentionPolicy() == null ? null :
            AggregationWindow.sqlInterval(properties.getRetentionPolicy());
        String end = layer == Layer.HOURLY
            ? AggregationWindow.sqlInterval(properties.getCagg().getEndOffset()) : layer.endOffset;
        String schedule = layer == Layer.HOURLY
            ? AggregationWindow.sqlInterval(properties.getCagg().getRefreshInterval()) : "1 day";
        return executor.select("SELECT NOT c.materialized_only AND j.scheduled"
            + " AND j.schedule_interval=?::interval"
            + " AND (j.config->>'start_offset')::interval IS NOT DISTINCT FROM ?::interval"
            + " AND (j.config->>'end_offset')::interval=?::interval AS ready "
            + jobFrom("policy_refresh_continuous_aggregate"), schedule, start, end, schema, view)
            .collectList().map(rows -> rows.size() == 1 && Boolean.TRUE.equals(rows.get(0).get("ready")));
    }

    private Mono<Void> ensurePolicies(String view, Layer layer, String functionSchema) {
        String start = properties.getRetentionPolicy() == null ? null :
            AggregationWindow.sqlInterval(properties.getRetentionPolicy());
        String end = layer == Layer.HOURLY
            ? AggregationWindow.sqlInterval(properties.getCagg().getEndOffset()) : layer.endOffset;
        String schedule = layer == Layer.HOURLY
            ? AggregationWindow.sqlInterval(properties.getCagg().getRefreshInterval()) : "1 day";
        Mono<Void> create = execute("SELECT " + function(functionSchema, "add_continuous_aggregate_policy")
                       + "(?::regclass,"
                       + "start_offset=>?::interval,end_offset=>?::interval,"
                       + "schedule_interval=>?::interval,initial_start=>now()+?::interval,"
                       + "timezone=>'Asia/Shanghai',if_not_exists=>true)",
                       qualified(view), start, end, schedule, schedule);
        // if_not_exists does not reconcile changed offsets: newer TimescaleDB rejects
        // the new overlapping window before returning the existing job.
        return executor.select("SELECT j.job_id " + jobFrom("policy_refresh_continuous_aggregate"),schema,view)
            .collectList()
            .flatMap(jobs -> {
                if (jobs.size() > 1) {
                    return Mono.error(new IllegalStateException("Multiple refresh policies require explicit reconciliation: " + view));
                }
                return jobs.isEmpty() ? create : Mono.<Void>empty();
            })
            .then(execute("SELECT " + function(functionSchema, "alter_job")
                          + "(j.job_id,scheduled=>true,"
                          + "schedule_interval=>?::interval,config=>j.config || "
                          + "jsonb_build_object('start_offset',?::text,'end_offset',?::text)) "
                          + jobFrom("policy_refresh_continuous_aggregate"),
                          schedule, start, end, schema, view))
            .then(properties.getCagg().getRetention() == null
                  ? execute("SELECT " + function(functionSchema, "alter_job")
                            + "(j.job_id,scheduled=>false) "
                            + jobFrom("policy_retention"),schema,view) :
                  ensureRetention(view, AggregationWindow.sqlInterval(properties.getCagg().getRetention()),
                                  functionSchema));
    }

    private Mono<Void> ensureRetention(String view, String retention, String functionSchema) {
        return execute("SELECT " + function(functionSchema, "add_retention_policy") + "(?::regclass,"
                       + "drop_after=>?::interval,if_not_exists=>true)", qualified(view), retention)
            .then(execute("SELECT " + function(functionSchema, "alter_job")
                          + "(j.job_id,scheduled=>true,"
                          + "config=>j.config || jsonb_build_object('drop_after',?::text)) "
                          + jobFrom("policy_retention"), retention, schema, view));
    }

    private String jobFrom(String procedure) {
        return "FROM timescaledb_information.jobs j JOIN timescaledb_information.continuous_aggregates c"
            + " ON j.hypertable_schema=c.view_schema"
            + " AND j.hypertable_name=c.view_name"
            + " WHERE j.proc_name='" + procedure + "' AND c.view_schema=? AND c.view_name=?";
    }

    private Mono<Void> execute(String sql, Object... parameters) {
        // Unlike select(), execute() uses a writable transaction on the shared Spring datasource.
        return executor.execute(Mono.just(SqlRequests.of(sql, parameters)));
    }
}
