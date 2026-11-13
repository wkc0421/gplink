package org.jetlinks.community.timescaledb.thing;

import io.r2dbc.spi.*;
import io.r2dbc.pool.*;
import org.hswebframework.ezorm.rdb.executor.*;
import org.hswebframework.ezorm.rdb.executor.reactive.r2dbc.R2dbcReactiveSqlExecutor;
import org.jetlinks.community.timescaledb.TimescaleDBOperations;
import org.jetlinks.community.timescaledb.thing.counter.CounterSnapshotService;
import org.jetlinks.community.timescaledb.thing.simulation.*;
import org.junit.jupiter.api.*;
import org.springframework.r2dbc.connection.ConnectionFactoryUtils;
import reactor.core.publisher.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.jetlinks.community.timescaledb.thing.simulation.SimulationSpec.local;

/** Only an explicitly supplied disposable database is ever used. */
class SimulationIntegrationTest {
    static final Duration WAIT=Duration.ofMinutes(8);
    ConnectionPool pool; TestExecutor executor; CounterSnapshotService counters; SimulationTaskService tasks;
    String raw,product; TimescaleDBThingsDataProperties settings; TimescaleDBOperations operations;

    @BeforeEach void setup() {
        String url=System.getProperty("simulation.test.url");
        Assumptions.assumeTrue(url!=null,"Requires disposable simulation.test.url");
        pool=new ConnectionPool(ConnectionPoolConfiguration.builder(ConnectionFactories.get(url)).initialSize(1).maxSize(8).build());
        executor=new TestExecutor(pool);
        product="sim_it_"+Long.toUnsignedString(System.nanoTime(),36);raw="properties_"+product;
        exec("CREATE TABLE "+raw+" (\"deviceId\" varchar NOT NULL,property varchar NOT NULL,\"timestamp\" timestamp NOT NULL,\"createTime\" bigint,value text,\"numberValue\" double precision)");
        exec("SELECT create_hypertable(?::regclass,'timestamp',chunk_time_interval=>interval '1 month')",raw);
        exec("CREATE UNIQUE INDEX ON "+raw+" (\"deviceId\",property,\"timestamp\")");
        settings=new TimescaleDBThingsDataProperties();settings.getCagg().setEnabled(true);settings.getCagg().setDailyEnabled(true);settings.getCagg().setMonthlyEnabled(true);
        settings.getCounterSnapshot().setPollInterval(Duration.ofDays(1));
        var database=CaggTestSupport.database(executor,raw);
        operations=mock(TimescaleDBOperations.class);when(operations.database()).thenReturn(database);when(operations.connectionFactory()).thenReturn(pool);
        new ContinuousAggregateManager(database,settings,"deviceId").ensure(raw).block(WAIT);
        counters=new CounterSnapshotService(operations,settings);counters.init();
        tasks=new SimulationTaskService(operations,settings,counters);tasks.initialize().block(WAIT);tasks.init();
    }
    @AfterEach void cleanup() {
        if(tasks!=null)tasks.shutdown();if(counters!=null)counters.shutdown();
        if(executor!=null&&raw!=null) {
            exec("DELETE FROM device_data_simulation_task WHERE product_id=?",product);
            for(String table:List.of("counter_snapshot_hour","counter_snapshot_day","counter_snapshot_task","counter_snapshot_change","counter_snapshot_device","counter_cagg_refresh","counter_snapshot_config")) exec("DELETE FROM "+table+" WHERE product_id=?",product);
            for(String layer:List.of("monthly","daily","hourly")) exec("DROP MATERIALIZED VIEW IF EXISTS "+raw+"_"+layer+"_agg CASCADE");
            exec("DROP TABLE "+raw+" CASCADE");
        }
        if(pool!=null)pool.disposeLater().block(WAIT);
    }
    SimulationSpec spec(boolean snapshots,int days) {
        SimulationSpec s=new SimulationSpec();s.deviceId="test-device";s.propertyId="E";s.productId=product;s.rawTable=raw;s.type="double";s.scale=2;
        s.startTime=LocalDate.of(2025,1,1).atStartOfDay(SimulationSpec.ZONE).toInstant().toEpochMilli();s.endTime=s.startTime+days*86400000L;
        s.intervalMinutes=5;s.mode="INCREMENT";s.initial="100";s.increment="0.25";s.snapshots=snapshots;s.snapshotEligible=true;s.allowEnableSnapshots=true;
        s.requestId=UUID.randomUUID().toString();return s;
    }
    Map<String,Object> await(String id) throws Exception {
        long deadline=System.nanoTime()+WAIT.toNanos();
        while(System.nanoTime()<deadline) {
            Map<String,Object> task=tasks.task(id).block(WAIT);
            if(Set.of("SUCCESS","FAILED").contains(task.get("status"))) return task;
            Thread.sleep(200);
        }
        throw new AssertionError("Simulation timed out: "+tasks.task(id).block(WAIT));
    }
    @Test void historyAllAggregatesAndSelectedDeviceSnapshotsAreActuallyGenerated() throws Exception {
        SimulationSpec s=spec(true,2);
        Map<String,Object> result=tasks.submit(s,"tester").block(WAIT);String id=(String)result.get("id");
        Map<String,Object> done=await(id);assertThat(done.get("status")).withFailMessage("%s",done).isEqualTo("SUCCESS");
        assertThat(number("SELECT count(*) AS n FROM "+raw)).isEqualTo(s.count());
        for(String layer:List.of("hourly","daily","monthly")) {
            exec("ALTER MATERIALIZED VIEW "+raw+"_"+layer+"_agg SET (timescaledb.materialized_only=true)");
            assertThat(number("SELECT count(*) AS n FROM "+raw+"_"+layer+"_agg")).isPositive();
            double sum=((Number)executor.select("SELECT sum(sum_value) AS n FROM "+raw+"_"+layer+"_agg").single().block(WAIT).get("n")).doubleValue();
            double expected=(s.value(0).doubleValue()+s.value(s.count()-1).doubleValue())*s.count()/2;
            assertThat(sum).isEqualTo(expected);
        }
        assertThat(number("SELECT count(*) AS n FROM counter_snapshot_hour WHERE product_id=?",product)).isEqualTo(49);
        assertThat(number("SELECT count(*) AS n FROM counter_snapshot_day WHERE product_id=?",product)).isEqualTo(3);
        assertThat(number("SELECT count(*) AS n FROM counter_snapshot_hour WHERE product_id=? AND device_id<>?",product,s.deviceId)).isZero();
        assertThat(counters.readContext(product,"E",s.deviceId).block(WAIT).isEnabled()).isTrue();
        assertThat(counters.readContext(product,"E","unselected-device").block(WAIT).isEnabled()).isFalse();
        assertThat(number("SELECT count(*) AS n FROM counter_snapshot_config WHERE product_id=? AND enabled=true",product)).isZero();
        var boundary=counters.resolveBoundaries(product,"E",List.of(s.deviceId),List.of(s.endTime)).block(WAIT).get(s.deviceId).get(s.endTime);
        assertThat(boundary.getSource()).isEqualTo("DAY_SNAPSHOT");assertThat(boundary.getValue()).isEqualTo(s.value(s.count()-1).toPlainString());
        assertThatThrownBy(()->tasks.submit(s,"tester").block(WAIT)).hasMessageContaining("已有数据");
    }
    @Test void failedTaskResumesWithoutDuplicatesAndForeignOffGridDataIsRejected() throws Exception {
        SimulationSpec s=spec(false,4);
        // Fail raw writes after the first committed batch, then resume that exact task.
        exec("ALTER TABLE "+raw+" ADD CONSTRAINT simulation_injected_failure CHECK (\"timestamp\"<timestamp '"+local(s.time(1000))+"')");
        String id=(String)tasks.submit(s,"tester").block(WAIT).get("id");
        Map<String,Object> failed=await(id);assertThat(failed.get("status")).isEqualTo("FAILED");assertThat(((Number)failed.get("cursor")).longValue()).isEqualTo(1000);
        assertThat(number("SELECT count(*) AS n FROM "+raw)).isEqualTo(1000);
        exec("ALTER TABLE "+raw+" DROP CONSTRAINT simulation_injected_failure");
        tasks.retry(id).block(WAIT);assertThat(await(id).get("status")).isEqualTo("SUCCESS");
        assertThat(number("SELECT count(*) AS n FROM "+raw)).isEqualTo(s.count());
        SimulationSpec other=spec(false,1);other.deviceId="foreign";
        exec("INSERT INTO "+raw+" (\"deviceId\",property,\"timestamp\",value,\"numberValue\") VALUES (?,?,?::timestamp,'2',2)",other.deviceId,other.propertyId,local(other.startTime+1));
        assertThatThrownBy(()->tasks.submit(other,"tester").block(WAIT)).hasMessageContaining("已有数据");
    }
    @Test void expiredLeaseRestoresRandomSequenceAfterWorkerRestart() throws Exception {
        tasks.shutdown();
        SimulationSpec s=spec(false,4);s.mode="RANDOM";s.min="-20";s.max="20";
        String id=(String)tasks.submit(s,"tester").block(WAIT).get("id");
        exec("ALTER TABLE "+raw+" ADD CONSTRAINT simulation_restart_failure CHECK (\"timestamp\"<timestamp '"+local(s.time(1000))+"')");
        tasks.init();assertThat(await(id).get("status")).isEqualTo("FAILED");tasks.shutdown();
        String first=String.valueOf(executor.select("SELECT value FROM "+raw+" ORDER BY \"timestamp\" LIMIT 1").single().block(WAIT).get("value"));
        exec("ALTER TABLE "+raw+" DROP CONSTRAINT simulation_restart_failure");
        exec("UPDATE device_data_simulation_task SET status='RUNNING',owner='dead-process',lease_until=now()-interval '1 second' WHERE id=?",id);
        tasks=new SimulationTaskService(operations,settings,counters);tasks.initialize().block(WAIT);tasks.init();
        assertThat(await(id).get("status")).isEqualTo("SUCCESS");
        assertThat(number("SELECT count(*) AS n FROM "+raw)).isEqualTo(s.count());
        assertThat(String.valueOf(executor.select("SELECT value FROM "+raw+" ORDER BY \"timestamp\" LIMIT 1").single().block(WAIT).get("value"))).isEqualTo(first);
        assertThat(number("SELECT count(*) AS n FROM device_data_simulation_sample WHERE task_id=?",id)).isEqualTo(s.count());
    }
    @Test void currentPartialBucketsAndOtherDeviceDataAreMaterializedTogether() throws Exception {
        SimulationSpec s=spec(false,1);s.endTime=System.currentTimeMillis()-60000;s.startTime=s.endTime-6*3600000+1234;
        exec("INSERT INTO "+raw+" (\"deviceId\",property,\"timestamp\",value,\"numberValue\") VALUES ('other','E',?::timestamp,'7',7)",local(s.startTime));
        String id=(String)tasks.submit(s,"tester").block(WAIT).get("id");
        Map<String,Object> done=await(id);assertThat(done.get("status")).withFailMessage("%s",done).isEqualTo("SUCCESS");
        for(String layer:List.of("hourly","daily","monthly")) {
            exec("ALTER MATERIALIZED VIEW "+raw+"_"+layer+"_agg SET (timescaledb.materialized_only=true)");
            assertThat(number("SELECT sum(numeric_count) AS n FROM "+raw+"_"+layer+"_agg WHERE \"deviceId\"=?",s.deviceId)).isEqualTo(s.count());
            assertThat(number("SELECT sum(sum_value) AS n FROM "+raw+"_"+layer+"_agg WHERE \"deviceId\"='other'")).isEqualTo(7);
        }
        assertThat(number("SELECT count(*) AS n FROM counter_snapshot_config WHERE product_id=?",product)).isZero();
    }
    @Test void fullTwoYearFiveMinuteTaskIsBoundedAndRefreshesEveryLayer() throws Exception {
        SimulationSpec s=spec(false,731);s.startTime=LocalDate.of(2023,1,1).atStartOfDay(SimulationSpec.ZONE).toInstant().toEpochMilli();
        s.endTime=LocalDate.of(2025,1,1).atStartOfDay(SimulationSpec.ZONE).toInstant().toEpochMilli();
        String id=(String)tasks.submit(s,"tester").block(WAIT).get("id");Map<String,Object> done=await(id);
        assertThat(done.get("status")).withFailMessage("%s",done).isEqualTo("SUCCESS");
        assertThat(number("SELECT count(*) AS n FROM "+raw)).isEqualTo(210528);
        assertThat(((Number)done.get("refresh_cursor")).longValue()).isEqualTo(72);
        assertThat(number("SELECT count(*) AS n FROM "+raw+"_monthly_agg")).isEqualTo(24);
    }
    long number(String sql,Object...args) {return ((Number)executor.select(sql,args).single().block(WAIT).get("n")).longValue();}
    void exec(String sql,Object...args) {executor.update(Mono.just(SqlRequests.of(sql,args))).block(WAIT);}
    static class TestExecutor extends org.hswebframework.web.crud.sql.DefaultR2dbcExecutor {
        final ConnectionFactory factory;TestExecutor(ConnectionFactory f){factory=f;setDefaultFactory(f);setBindSymbol("$");setBindCustomSymbol(true);}
        @Override protected Mono<Connection> getConnection(){return ConnectionFactoryUtils.getConnection(factory);}
    }
}
