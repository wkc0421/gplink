package org.jetlinks.community.timescaledb.thing.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.reactive.ReactiveSqlExecutor;
import org.jetlinks.community.timescaledb.TimescaleDBOperations;
import org.jetlinks.community.timescaledb.thing.*;
import org.jetlinks.community.timescaledb.thing.counter.CounterSnapshotService;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.Disposable;
import reactor.core.publisher.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.jetlinks.community.timescaledb.thing.simulation.SimulationSpec.*;

/** Durable, bounded historical-only importer. Never publishes device messages or latest values. */
@Slf4j
public class SimulationTaskService {
    private final TimescaleDBOperations operations;
    private final TimescaleDBThingsDataProperties properties;
    private final CounterSnapshotService counters;
    private final ReactiveSqlExecutor executor;
    private final TransactionalOperator tx;
    private final ObjectMapper json = new ObjectMapper();
    private final String schema, table, owner = UUID.randomUUID().toString();
    private final AtomicBoolean busy = new AtomicBoolean();
    private Disposable loop;
    private volatile boolean ready;
    private java.util.function.Function<SimulationSpec,Mono<Void>> metadataValidator = ignored -> Mono.empty();
    public void setMetadataValidator(java.util.function.Function<SimulationSpec,Mono<Void>> validator) { metadataValidator=validator; }
    private static final List<String> LAYERS = List.of("HOURLY", "DAILY", "MONTHLY");

    public SimulationTaskService(TimescaleDBOperations operations, TimescaleDBThingsDataProperties properties, CounterSnapshotService counters) {
        this.operations = operations; this.properties = properties; this.counters = counters;
        executor = operations.database().getMetadata().getFeatureNow(ReactiveSqlExecutor.ID);
        tx = TransactionalOperator.create(new R2dbcTransactionManager(operations.connectionFactory()));
        schema = operations.database().getMetadata().getCurrentSchema().getName();
        table = qualified("device_data_simulation_task");
    }

    public void init() {
        loop = Flux.interval(Duration.ofSeconds(2)).onBackpressureDrop().concatMap(tick -> {
            if (!busy.compareAndSet(false, true)) return Mono.empty();
            return initialize().then(claim()).flatMap(task -> run(task)
                .onErrorResume(error -> fail(task, error)))
                .onErrorResume(error -> { log.warn("Simulation worker unavailable", error); return Mono.empty(); })
                .doFinally(signal -> busy.set(false));
        }).subscribe();
    }
    public void shutdown() { if (loop != null) loop.dispose(); }

    public Mono<Void> initialize() {
        if (ready) return Mono.empty();
        return rows("SELECT pg_advisory_xact_lock(762731985)").then(exec("CREATE TABLE IF NOT EXISTS " + table + " (id text PRIMARY KEY,request_id text NOT NULL,creator text NOT NULL,"
            + "device_id text NOT NULL,product_id text NOT NULL,property_id text NOT NULL,spec text NOT NULL,"
            + "status text NOT NULL DEFAULT 'PENDING',stage text NOT NULL DEFAULT 'RAW',cursor bigint NOT NULL DEFAULT 0,"
            + "total bigint NOT NULL,refresh_cursor bigint NOT NULL DEFAULT 0,owner text,lease_until timestamptz,error text,"
            + "created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),"
            + "UNIQUE(creator,request_id))"))
            .then(exec("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS refresh_version text"))
            .then(exec("CREATE UNIQUE INDEX IF NOT EXISTS device_simulation_one_worker ON " + table + " ((1)) WHERE status='RUNNING'"))
            .then(exec("CREATE TABLE IF NOT EXISTS " + qualified("device_data_simulation_sample")
                + " (task_id text NOT NULL REFERENCES " + table + "(id) ON DELETE CASCADE,sample_index bigint NOT NULL,"
                + "sample_id varchar(32) NOT NULL,sample_time timestamp NOT NULL,PRIMARY KEY(task_id,sample_index),UNIQUE(task_id,sample_time))"))
            .as(tx::transactional).doOnSuccess(v -> ready = true);
    }

    public long configuredOldest() {
        var retention=properties.getRetentionPolicy();
        if(retention==null) return 0;
        var now=Instant.now().atZone(ZONE);
        return switch(retention.getUnit()) {
            case YEARS -> now.minusYears(retention.getNumber().longValue()).toInstant().toEpochMilli();
            case MONTHS -> now.minusMonths(retention.getNumber().longValue()).toInstant().toEpochMilli();
            case QUARTER -> now.minusMonths(retention.getNumber().longValue()*3).toInstant().toEpochMilli();
            default -> System.currentTimeMillis()-retention.toMillis();
        };
    }

    public Mono<Map<String,Object>> preview(SimulationSpec s) {
        require(properties.getCagg().isEnabled() && properties.getCagg().isDailyEnabled() && properties.getCagg().isMonthlyEnabled(),
            "必须开启小时、日、月三层聚合配置");
        s.validate(System.currentTimeMillis(), configuredOldest());
        require(!s.snapshots || s.startTime >= LocalDate.now(ZONE).minusYears(5).atStartOfDay(ZONE).toInstant().toEpochMilli(),
            "所选时间超出累计快照保留范围");
        return rows("SELECT to_regclass(?) AS relation", qualified(s.rawTable)).single().flatMap(raw -> {
            boolean rawExists = raw.get("relation") != null;
            Mono<Long> actualOldest = rawExists ? rows("SELECT config->>'drop_after' AS retention FROM timescaledb_information.jobs"
                + " WHERE proc_name='policy_retention' AND hypertable_schema=? AND hypertable_name=?", schema, s.rawTable)
                .concatMap(row -> rows("SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai') - ?::interval AS oldest", row.get("retention")).single())
                .map(row -> ShanghaiTimestampCodec.local(row.get("oldest")).atZone(ZONE).toInstant().toEpochMilli())
                .reduce(configuredOldest(), Math::max) : Mono.just(configuredOldest());
            return (rawExists ? validateRawStructure(s) : Mono.<Void>empty()).then(actualOldest).flatMap(oldest -> {
                s.validate(System.currentTimeMillis(), oldest);
                Mono<Long> count = rawExists ? count(s) : Mono.just(0L);
                return count.flatMap(existing -> Flux.fromIterable(LAYERS).concatMap(layer -> {
                    String suffix = "_" + layer.toLowerCase(Locale.ROOT) + "_agg";
                    return rows("SELECT to_regclass(?) AS relation", qualified(s.rawTable + suffix)).single()
                        .flatMap(r -> r.get("relation") == null ? Mono.just(Map.entry(layer, "MISSING"))
                            : ContinuousAggregateManager.counterReady(operations.database(), properties, s.rawTable, layer)
                                .map(ok -> Map.entry(layer, ok ? "READY" : "INCOMPATIBLE")));
                }).collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new).flatMap(layers ->
                    counters.readContext(s.productId, s.propertyId, s.deviceId).map(context -> {
                        Map<String,Object> result = new LinkedHashMap<>();
                        result.put("count", s.count()); result.put("existing", existing); result.put("oldestTime", oldest);
                        result.put("rawExists", rawExists); result.put("layers", layers);
                        result.put("storageReady", rawExists && layers.values().stream().allMatch("READY"::equals));
                        result.put("canInitialize", layers.values().stream().noneMatch("INCOMPATIBLE"::equals));
                        result.put("snapshotEnabled", context.isEnabled()); result.put("productId", s.productId);
                        result.put("firstValue", "INCREMENT".equals(s.mode) ? s.value(0).toPlainString() : s.min);
                        result.put("lastValue", "INCREMENT".equals(s.mode) ? s.value(s.count()-1).toPlainString() : s.max);
                        return result;
                    })));
            });
        });
    }

    private Mono<Void> validateRawStructure(SimulationSpec s) {
        Map<String,String> expected=Map.of("deviceId","character varying","property","character varying",
            "timestamp","timestamp without time zone","createTime","bigint","value","text","numberValue","double precision");
        return rows("SELECT column_name,data_type FROM information_schema.columns WHERE table_schema=? AND table_name=?",schema,s.rawTable)
            .collectMap(r -> String.valueOf(r.get("column_name")),r -> String.valueOf(r.get("data_type")))
            .doOnNext(columns -> expected.forEach((name,type) -> require(type.equals(columns.get(name))
                || Set.of("deviceId","property","value").contains(name)&&Set.of("text","character varying").contains(columns.getOrDefault(name,"")),
                "历史表字段不兼容: "+name))).then();
    }

    public Mono<Map<String,Object>> existing(String creator, String requestId) {
        return initialize().thenMany(rows("SELECT * FROM " + table + " WHERE creator=? AND request_id=?", creator, requestId)).next().map(this::view);
    }

    public Mono<Map<String,Object>> submit(SimulationSpec s, String creator) {
        require(s.requestId != null && s.requestId.matches("[A-Za-z0-9_-]{8,80}"), "缺少有效幂等请求ID");
        return initialize().then(preview(s)).flatMap(check -> {
            require(((Number) check.get("existing")).longValue() == 0, "所选区间已有数据，不能补录");
            require(Boolean.TRUE.equals(check.get("storageReady")), "历史表或聚合视图尚未准备完成");
            String id = UUID.randomUUID().toString();
            s.seed = new java.security.SecureRandom().nextLong();
            return exec("INSERT INTO " + table + " (id,request_id,creator,device_id,product_id,property_id,spec,total) VALUES (?,?,?,?,?,?,?,?)"
                + " ON CONFLICT (creator,request_id) DO NOTHING", id, s.requestId, creator, s.deviceId, s.productId, s.propertyId, encode(s), s.count())
                .then(Mono.defer(() -> existing(creator, s.requestId))).doOnNext(task -> require(s.sameRequest((SimulationSpec)task.get("request")),"幂等请求ID已被其他参数使用"));
        });
    }
    public Mono<Map<String,Object>> task(String id) {
        return ready ? rows("SELECT * FROM " + table + " WHERE id=?", id).next().map(this::view) : Mono.empty();
    }
    public Flux<Map<String,Object>> list(String creator, int page, int size) {
        require(page >= 0 && page <= 100000 && size > 0 && size <= 50, "分页参数无效");
        return ready ? rows("SELECT * FROM " + table + " WHERE creator=? ORDER BY created_at DESC LIMIT ? OFFSET ?",
            creator, size, page * size).map(this::view) : Flux.empty();
    }
    public Mono<Map<String,Object>> retry(String id) {
        return update("UPDATE " + table + " SET status='PENDING',error=NULL,owner=NULL,lease_until=NULL,updated_at=now() WHERE id=? AND status='FAILED'", id)
            .flatMap(n -> n == 1 ? task(id) : Mono.error(new IllegalArgumentException("仅失败任务可重试")));
    }

    private Mono<Map<String,Object>> claim() {
        return rows("SELECT pg_advisory_xact_lock(762731984)").thenMany(rows("SELECT * FROM " + table
            + " WHERE status='RUNNING' OR status='PENDING' ORDER BY CASE WHEN status='RUNNING' THEN 0 ELSE 1 END,created_at LIMIT 1 FOR UPDATE"))
            .collectList().flatMap(found -> {
                if (found.isEmpty()) return Mono.empty();
                Map<String,Object> task = found.get(0);
                return rows("UPDATE " + table + " SET status='RUNNING',owner=?,lease_until=now()+interval '15 minutes',updated_at=now()"
                    + " WHERE id=? AND (status='PENDING' OR lease_until<now()) RETURNING *", owner, task.get("id"))
                    .collectList().flatMap(updated -> updated.isEmpty() ? Mono.empty() : Mono.just(updated.get(0)));
            }).as(tx::transactional);
    }

    private Mono<Void> run(Map<String,Object> task) {
        SimulationSpec s = spec(task);
        String id = String.valueOf(task.get("id"));
        return Mono.defer(() -> {
            s.validate(System.currentTimeMillis(), configuredOldest());
            return preview(s).doOnNext(check -> require(Boolean.TRUE.equals(check.get("storageReady")), "存储结构发生变化"));
        }).then(Mono.defer(() -> metadataValidator.apply(s)))
            .then(verifyPrefix(s,id,((Number)task.get("cursor")).longValue()))
            .then(prepareSnapshots(s)).then(writeRemaining(task, s))
            .then(stage(id, "VERIFY_RAW")).then(verify(s, id))
            .then(refresh(task, s))
            .then(s.snapshots ? stage(id, "SNAPSHOTS").then(counters.generateSimulationSnapshots(s.productId, s.propertyId, s.deviceId, s.startTime, s.endTime)
                .timeout(Duration.ofMinutes(8))) : Mono.empty())
            .then(stage(id, "VERIFY")).then(verify(s, id))
            .then(ownedUpdate(id, "status='SUCCESS',stage='DONE',error=NULL,lease_until=NULL"));
    }

    private Mono<Void> prepareSnapshots(SimulationSpec s) {
        if (!s.snapshots) return Mono.empty();
        return counters.readContext(s.productId, s.propertyId, s.deviceId).flatMap(context -> context.isEnabled() ? Mono.empty()
            : !s.allowEnableSnapshots ? Mono.error(new IllegalStateException("快照配置已停用，当前任务无权自动启用"))
            : counters.upsertDevice(s.productId, s.propertyId, s.deviceId, true, s.rawTable, "simulation").then())
            .then(counters.registerDevices(s.productId, List.of(s.deviceId)));
    }

    private Mono<Void> writeRemaining(Map<String,Object> task, SimulationSpec s) {
        long cursor = ((Number)task.get("cursor")).longValue();
        String id = String.valueOf(task.get("id"));
        // Only an original task may recognize its own prefix; a changed prefix is never overwritten.
        return verifyPrefix(s, id, cursor).thenMany(Flux.range(0, (int)((s.count()-cursor+999)/1000)))
            .concatMap(batch -> writeBatch(id, s, cursor + batch * 1000L)).then();
    }

    private Mono<Void> writeBatch(String id, SimulationSpec s, long offset) {
        long stop = Math.min(offset + 1000, s.count());
        List<Map<String,Object>> data = new ArrayList<>();
        List<Object> params = new ArrayList<>(), ownership = new ArrayList<>();
        StringJoiner values = new StringJoiner(","), ownerValues = new StringJoiner(",");
        for (long i = offset; i < stop; i++) {
            BigDecimal v = s.value(i);
            values.add("(?,?,?::timestamp,?,?,?)");
            Collections.addAll(params, s.deviceId, s.propertyId, local(s.time(i)), System.currentTimeMillis(), v.toPlainString(), v.doubleValue());
            ownerValues.add("(?,?,?,?::timestamp)");
            Collections.addAll(ownership,id,i,rowId(id,i),local(s.time(i)));
            data.add(Map.of("deviceId",s.deviceId,"property",s.propertyId,"timestamp",s.time(i)));
        }
        return fence(id).then(checkForeign(s,id,offset))
            .then(exec("INSERT INTO " + qualified(s.rawTable) + " (\"deviceId\",property,\"timestamp\",\"createTime\",value,\"numberValue\") VALUES " + values, params.toArray()))
            .then(exec("INSERT INTO " + qualified("device_data_simulation_sample") + " (task_id,sample_index,sample_id,sample_time) VALUES " + ownerValues,ownership.toArray()))
            .then(counters.markRawBatch(s.rawTable, data))
            .then(ownedUpdate(id,"cursor=" + stop + ",stage='RAW'"))
            .as(tx::transactional).timeout(Duration.ofMinutes(2))
            .retryWhen(reactor.util.retry.Retry.backoff(2,Duration.ofSeconds(1)).filter(SimulationTaskService::transientFailure));
    }

    private Mono<Void> checkForeign(SimulationSpec s, String id, long cursor) {
        // Count beyond the committed prefix catches samples at off-grid timestamps too.
        return rows("SELECT count(*) AS n FROM " + qualified(s.rawTable)
            + " WHERE \"deviceId\"=? AND property=? AND \"timestamp\">=?::timestamp AND \"timestamp\"<?::timestamp",
            s.deviceId,s.propertyId,local(s.time(cursor)),local(s.endTime)).single()
            .doOnNext(r -> require(((Number)r.get("n")).longValue()==0, "补录期间发现外来历史数据，已停止且未覆盖")).then();
    }

    private Mono<Void> verifyPrefix(SimulationSpec s,String id,long cursor) {
        return samples(s,id).index().doOnNext(pair -> {
            long i=pair.getT1(); Map<String,Object> row=pair.getT2();
            require(i < cursor, "区间中存在非本任务数据"); verifyRow(s,id,i,row);
        }).count().doOnNext(n -> require(n==cursor,"已提交样本缺失，请检查外部修改")).then();
    }
    private Mono<Void> verify(SimulationSpec s,String id) {
        return samples(s,id).index().doOnNext(pair -> verifyRow(s,id,pair.getT1(),pair.getT2()))
            .count().doOnNext(n -> require(n==s.count(),"样本数量校验失败")).then();
    }
    private void verifyRow(SimulationSpec s,String id,long i,Map<String,Object> row) {
        require(i<s.count() && rowId(id,i).equals(row.get("id")),"样本归属不一致，可能存在并发写入");
        require(local(s.time(i)).equals(ShanghaiTimestampCodec.local(row.get("timestamp"))),"样本时间不一致");
        require(new BigDecimal(String.valueOf(row.get("value"))).compareTo(s.value(i))==0
            && Double.compare(((Number)row.get("numberValue")).doubleValue(),s.value(i).doubleValue())==0,"样本值已被外部修改");
    }
    private Flux<Map<String,Object>> samples(SimulationSpec s,String taskId) {
        return rows("SELECT o.sample_id AS id,r.\"timestamp\",r.value,r.\"numberValue\" FROM " + qualified(s.rawTable) + " r LEFT JOIN "
            + qualified("device_data_simulation_sample") + " o ON o.task_id=? AND o.sample_time=r.\"timestamp\""
            + " WHERE r.\"deviceId\"=? AND r.property=? AND r.\"timestamp\">=?::timestamp AND r.\"timestamp\"<?::timestamp ORDER BY r.\"timestamp\"",
            taskId,s.deviceId,s.propertyId,local(s.startTime),local(s.endTime));
    }

    private Mono<Void> refresh(Map<String,Object> task,SimulationSpec s) {
        String id=String.valueOf(task.get("id"));
        List<LocalDateTime> months=new ArrayList<>();
        LocalDateTime first=local(s.startTime).toLocalDate().withDayOfMonth(1).atStartOfDay();
        for(LocalDateTime m=first;m.isBefore(local(s.endTime));m=m.plusMonths(1)) months.add(m);
        return refreshVersion(s).flatMap(version -> {
            long cursor=version.equals(task.get("refresh_version")) ? ((Number)task.get("refresh_cursor")).longValue() : 0;
            return update("UPDATE " + table + " SET refresh_version=?,refresh_cursor=? WHERE id=? AND owner=? AND status='RUNNING'",version,cursor,id,owner)
                .thenMany(Flux.range(0,months.size()*LAYERS.size()).skip(cursor))
                .concatMap(index -> {
                    String layer=LAYERS.get(index/months.size());LocalDateTime month=months.get(index%months.size());
                    return stage(id,layer).then(counters.refreshSimulationLayer(s.rawTable,layer,month,month.plusMonths(1),s.endTime)
                        .timeout(Duration.ofMinutes(8))).then(verifyMaterialization(s,layer,month,month.plusMonths(1)))
                        .then(ownedUpdate(id,"refresh_cursor="+(index+1)));
                }).then(refreshVersion(s)).doOnNext(current -> require(version.equals(current),"聚合刷新期间数据来源版本发生变化，请重试任务")).then();
        });
    }
    // Read the actual materialization, so realtime fallback cannot mask an unrefreshed layer.
    private Mono<Void> verifyMaterialization(SimulationSpec s,String layer,LocalDateTime from,LocalDateTime to) {
        return rows("SELECT materialization_hypertable_schema AS schema,materialization_hypertable_name AS name FROM timescaledb_information.continuous_aggregates WHERE view_schema=? AND view_name=?",
            schema,s.rawTable+"_"+layer.toLowerCase(Locale.ROOT)+"_agg").single().flatMap(info -> {
            String materialized=quote(String.valueOf(info.get("schema")))+"."+quote(String.valueOf(info.get("name")));
            String where=" WHERE \"deviceId\"=? AND property=? AND ";
            Mono<Map<String,Object>> original=rows("SELECT count(*) AS n,sum(\"numberValue\") AS sum,min(\"numberValue\") AS min,max(\"numberValue\") AS max FROM "+qualified(s.rawTable)+where+"\"timestamp\">=?::timestamp AND \"timestamp\"<?::timestamp",s.deviceId,s.propertyId,from,to).single();
            Mono<Map<String,Object>> aggregate=rows("SELECT coalesce(sum(numeric_count),0) AS n,sum(sum_value) AS sum,min(min_value) AS min,max(max_value) AS max FROM "+materialized+where+"bucket_start>=?::timestamp AND bucket_start<?::timestamp",s.deviceId,s.propertyId,from,to).single();
            return original.zipWith(aggregate).doOnNext(pair -> {
                Map<String,Object> expected=pair.getT1(),actual=pair.getT2();
                require(((Number)expected.get("n")).longValue()==((Number)actual.get("n")).longValue(),layer+"物化采样数不一致");
                for(String metric:List.of("sum","min","max")) {
                    Object a=expected.get(metric),b=actual.get(metric);
                    require(a==null?b==null:b instanceof Number && Math.abs(((Number)a).doubleValue()-((Number)b).doubleValue())<=Math.max(1,Math.abs(((Number)a).doubleValue()))*1e-10,
                        layer+"物化聚合值不一致: "+metric);
                }
            }).then();
        });
    }

    private Mono<String> refreshVersion(SimulationSpec s) {
        return rows("SELECT product_id,property_id,source_version FROM " + qualified("counter_snapshot_config")
            + " WHERE raw_table=? ORDER BY product_id,property_id",s.rawTable)
            .map(r -> r.get("product_id")+":"+r.get("property_id")+":"+r.get("source_version")).collectList().map(Object::toString);
    }
    private static boolean transientFailure(Throwable error) {
        for(Throwable e=error;e!=null;e=e.getCause()) if(e instanceof io.r2dbc.spi.R2dbcException sql) {
            String state=sql.getSqlState();return state!=null && (state.startsWith("08") || state.equals("40001") || state.equals("40P01"));
        }
        return false;
    }
    private Mono<Long> count(SimulationSpec s) {
        return rows("SELECT count(*) AS n FROM " + qualified(s.rawTable)
            + " WHERE \"deviceId\"=? AND property=? AND \"timestamp\">=?::timestamp AND \"timestamp\"<?::timestamp",
            s.deviceId,s.propertyId,local(s.startTime),local(s.endTime)).single().map(r -> ((Number)r.get("n")).longValue());
    }
    private Mono<Void> stage(String id,String stage) { return ownedUpdate(id,"stage='"+stage+"'"); }
    private Mono<Void> fence(String id) {
        return rows("SELECT id FROM " + table + " WHERE id=? AND owner=? AND status='RUNNING' AND lease_until>now() FOR UPDATE",id,owner)
            .single().then();
    }
    private Mono<Void> ownedUpdate(String id,String assignments) {
        return update("UPDATE " + table + " SET " + (assignments.contains("lease_until=") ? "" : "lease_until=now()+interval '15 minutes',") + "updated_at=now()," + assignments
            + " WHERE id=? AND owner=? AND status='RUNNING' AND lease_until>now()",id,owner)
            .doOnNext(n -> { if(n!=1) throw new IllegalStateException("Simulation task lease lost"); }).then();
    }
    private Mono<Void> fail(Map<String,Object> task,Throwable error) {
        log.warn("Simulation task {} failed",task.get("id"),error);
        String message=error.getMessage()==null ? error.getClass().getSimpleName() : error.getMessage();
        return exec("UPDATE " + table + " SET status='FAILED',error=?,owner=NULL,lease_until=NULL,updated_at=now() WHERE id=? AND owner=?",
            message.substring(0,Math.min(1500,message.length())),task.get("id"),owner);
    }
    private String qualified(String name) { return quote(schema)+"."+quote(name); }
    private static String quote(String name) { return "\""+name.replace("\"","\"\"")+"\""; }
    private Mono<Integer> update(String sql,Object... args) { return executor.update(Mono.just(SqlRequests.of(sql,args))); }
    private Mono<Void> exec(String sql,Object... args) { return update(sql,args).then(); }
    private Flux<Map<String,Object>> rows(String sql,Object... args) { return executor.select(sql,args); }
    private SimulationSpec spec(Map<String,Object> task) {
        try { return json.readValue(String.valueOf(task.get("spec")),SimulationSpec.class); }
        catch(Exception e) { throw new IllegalStateException("Invalid stored simulation specification",e); }
    }
    private String encode(SimulationSpec spec) {
        try { return json.writeValueAsString(spec); } catch(Exception e) { throw new IllegalArgumentException(e); }
    }
    private Map<String,Object> view(Map<String,Object> task) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("id","creator","status","stage","cursor","total","refresh_cursor","error")) result.put(key,task.get(key));
        SimulationSpec s=spec(task);
        result.put("request",s);
        LocalDateTime month=local(s.startTime).toLocalDate().withDayOfMonth(1).atStartOfDay();
        long months=0;for(;month.isBefore(local(s.endTime));month=month.plusMonths(1))months++;
        long progress=((Number)task.get("refresh_cursor")).longValue();
        Map<String,String> layers=new LinkedHashMap<>();
        for(int i=0;i<LAYERS.size();i++) layers.put(LAYERS.get(i),progress>=(i+1)*months?"DONE":progress>i*months?"RUNNING":"PENDING");
        result.put("layers",layers);
        result.put("snapshotStatus",!s.snapshots?"NOT_REQUESTED":"SUCCESS".equals(task.get("status"))?"DONE":"SNAPSHOTS".equals(task.get("stage"))?"RUNNING":"PENDING");
        result.put("createdAt",String.valueOf(task.get("created_at"))); result.put("updatedAt",String.valueOf(task.get("updated_at")));
        return result;
    }
}
