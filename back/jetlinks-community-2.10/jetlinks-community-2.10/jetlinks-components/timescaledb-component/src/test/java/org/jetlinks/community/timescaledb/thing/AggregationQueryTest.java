package org.jetlinks.community.timescaledb.thing;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.community.Interval;
import org.jetlinks.community.things.data.*;
import org.jetlinks.community.timeseries.query.Aggregation;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.jetlinks.community.timescaledb.thing.CaggTestSupport.*;

class AggregationQueryTest {
    @Test void aliasesNeverEnterSqlAndParametersStayBound() {
        Executor executor = new Executor();
        TimescaleDBThingsDataProperties config = config();
        config.getCagg().setEnabled(false);
        Query query = new Query(database(executor, "metrics"), config);
        String alias = "值\", 123 AS \"injected";
        executor.responder = sql -> Flux.just(Map.of("_ts",LocalDateTime.of(2026,9,1,0,0),"_a0",42D));
        List<?> rows = query.aggregate("metrics", request("1h","2026-09-01 00:00:00","2026-09-01 00:59:00",1),
                                      new PropertyAggregation("p",alias,Aggregation.AVG)).collectList().block();
        SqlRequest sql = executor.requests.get(0);
        assertThat(sql.getSql())
            .contains("AS _a0", "LIMIT 1", "\"public\".\"time_bucket\"(")
            .doesNotContain(alias, "injected");
        assertThat(sql.getParameters()).contains("p",LocalDateTime.of(2026,9,1,0,0));
        assertThat(rows).hasSize(1);
        assertThat(sql.getSql().chars().filter(c -> c=='?').count()).isEqualTo(sql.getParameters().length);
    }

    @Test void rejectDuplicateAndReservedAliases() {
        Query query = new Query(database(new Executor(),"metrics"),config());
        AggregationRequest request = request("1h","2026-09-01 00:00:00","2026-09-01 01:00:00",1);
        StepVerifier.create(query.aggregate("metrics", request,
            new PropertyAggregation("p","time",Aggregation.AVG))).expectError(BusinessException.class).verify();
        StepVerifier.create(query.aggregate("metrics", request,
            new PropertyAggregation("p","a",Aggregation.AVG),
            new PropertyAggregation("p","a",Aggregation.MAX))).expectError(BusinessException.class).verify();
    }

    @Test void intervalRoutingAndRawBookends() {
        assertThat(TimescaleDBRowModeQueryOperations.compatibleInterval(Interval.of("90m"),
            ContinuousAggregateManager.Layer.HOURLY)).isFalse();
        assertThat(TimescaleDBRowModeQueryOperations.compatibleInterval(Interval.of("25h"),
            ContinuousAggregateManager.Layer.DAILY)).isFalse();
        assertThat(TimescaleDBRowModeQueryOperations.compatibleInterval(Interval.of("1M"),
            ContinuousAggregateManager.Layer.MONTHLY)).isTrue();
        Query query = new Query(database(new Executor(),"metrics"),config());
        assertThat(query.createAggFunction(Aggregation.FIRST, "timescale_api"))
            .contains("\"timescale_api\".\"first\"(", "deviceId", "COLLATE \"C\"");
        assertThat(query.createAggFunction(Aggregation.LAST, "timescale_api"))
            .contains("\"timescale_api\".\"last\"(");
        assertThat(TimescaleDBRowModeQueryOperations.stateExpression(Aggregation.AVG, "timescale_api"))
            .contains("numeric_count").doesNotContain("sample_count");
        assertThat(TimescaleDBRowModeQueryOperations.stateExpression(Aggregation.FIRST, "timescale_api"))
            .isEqualTo("\"timescale_api\".\"first\"(first_value,first_key)");
        assertThat(AggregationWindow.bucketSql(Interval.of("1h"), "bucket_start", "timescale_api"))
            .startsWith("\"timescale_api\".\"time_bucket\"(");
    }

    @Test void rawAggregationUsesResolvedExtensionSchema() {
        Executor executor = new Executor();
        executor.functionSchema = "timescale_api";
        executor.responder = sql -> Flux.just(Map.of(
            "_ts", LocalDateTime.of(2026, 9, 1, 0, 0), "_a0", 42D));
        TimescaleDBThingsDataProperties properties = config();
        properties.getCagg().setEnabled(false);
        Query query = new Query(database(executor, "metrics"), properties);

        query.aggregate(
            "metrics",
            request("1h", "2026-09-01 00:00:00", "2026-09-01 00:59:00", 1),
            new PropertyAggregation("p", "p", Aggregation.FIRST))
            .collectList()
            .block();

        assertThat(executor.extensionSchemaRequests).hasSize(1);
        assertThat(executor.requests).hasSize(1);
        assertThat(executor.requests.get(0).getSql())
            .contains("\"timescale_api\".\"time_bucket\"(")
            .contains("\"timescale_api\".\"first\"(")
            .doesNotContain("\"public\".\"time_bucket\"(");
    }

    @Test void keepNestedOrAndMandatoryDeviceScope() {
        Query query = new Query(database(new Executor(),"metrics"),config());
        Term or = new Term();
        or.setTerms(Arrays.asList(Term.of("thingId","eq","a"),Term.of("deviceId","eq","b")));
        or.getTerms().get(1).setType(Term.Type.or);
        List<Term> terms = Arrays.asList(or,Term.of("deviceId","eq","allowed"));
        assertThat(query.isSafeForCagg(terms)).isTrue();
        List<Object> params = new ArrayList<>();
        assertThat(query.caggFilter(terms,params)).isEqualTo(
            "(\"deviceId\"=? OR \"deviceId\"=?) AND \"deviceId\"=?");
        assertThat(params).containsExactly("a","b","allowed");
        assertThat(query.isSafeForCagg(List.of(Term.of("numberValue","gt",1)))).isFalse();
        assertThat(query.isSafeForCagg(List.of(Term.of("deviceId","in",Arrays.asList("a",null))))).isFalse();
    }

    @Test void missingCaggFallsBackWithoutWritingAndBoundsRawSql() {
        Executor executor = new Executor();
        Query query = new Query(database(executor,"metrics"),config());
        query.aggregate("metrics",request("1h","2000-01-01 00:00:00","2026-09-01 01:00:00",2),
                        new PropertyAggregation("p","p",Aggregation.COUNT)).collectList().block();
        SqlRequest sql = executor.requests.get(executor.requests.size()-1);
        assertThat(sql.getSql()).contains("raw_data","LIMIT 2").doesNotContain("UNION ALL");
        assertThat(sql.getParameters()).contains(LocalDateTime.of(2026,9,1,0,0));
        assertThat(executor.writes).isEmpty();
    }

    @Test void timeoutCancelsSqlSubscription() {
        Executor executor = new Executor();
        AtomicBoolean cancelled = new AtomicBoolean();
        executor.responder = sql -> Flux.<Map<String,Object>>never().doOnCancel(() -> cancelled.set(true));
        TimescaleDBThingsDataProperties config = config();
        config.getCagg().setEnabled(false);
        config.getAggregation().setTimeout(Duration.ofMillis(50));
        Query query = new Query(database(executor,"metrics"),config);
        StepVerifier.create(query.aggregate("metrics",request("1h","2026-09-01 00:00:00","2026-09-01 01:00:00",2),
            new PropertyAggregation("p","p",Aggregation.AVG))).expectError(java.util.concurrent.TimeoutException.class)
            .verify(Duration.ofSeconds(5));
        assertThat(cancelled).isTrue();
    }

    @Test void queriesOutsideAggregateRetentionUseRawData() {
        Executor executor = new Executor();
        TimescaleDBThingsDataProperties config = config();
        config.setRetentionPolicy(null);
        config.getCagg().setRetention(Interval.of("1M"));
        Query query = new Query(database(executor,"metrics"),config);
        assertThat(query.withinRetention(time("2024-02-29 12:00:00"),time("2024-03-31 12:00:00"))).isTrue();
        assertThat(query.withinRetention(time("2024-02-29 11:59:59"),time("2024-03-31 12:00:00"))).isFalse();
        query.aggregate("metrics",request("1h","2000-01-01 00:00:00","2000-01-02 00:00:00",2),
                        new PropertyAggregation("p","p",Aggregation.COUNT)).collectList().block();
        assertThat(executor.requests).hasSize(1);
        assertThat(executor.requests.get(0).getSql()).contains("raw_data").doesNotContain("_hourly_agg");
        config.getCagg().setRetention(null);
        assertThat(query.withinRetention(time("2000-01-01 00:00:00"),time("2026-09-01 00:00:00"))).isTrue();
    }
}
