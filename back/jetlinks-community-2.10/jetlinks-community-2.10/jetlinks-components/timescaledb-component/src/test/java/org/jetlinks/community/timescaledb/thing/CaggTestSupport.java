package org.jetlinks.community.timescaledb.thing;

import org.hswebframework.ezorm.rdb.executor.*;
import org.hswebframework.ezorm.rdb.executor.reactive.ReactiveSqlExecutor;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrapper;
import org.hswebframework.ezorm.rdb.metadata.*;
import org.hswebframework.ezorm.rdb.metadata.dialect.Dialect;
import org.hswebframework.ezorm.rdb.operator.*;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.community.Interval;
import org.jetlinks.community.things.data.*;
import org.jetlinks.community.things.data.operations.*;
import org.jetlinks.community.timescaledb.metadata.TimescaleDBDialectProvider;
import org.jetlinks.community.timeseries.TimeSeriesData;
import org.jetlinks.community.timeseries.query.AggregationData;
import org.jetlinks.core.metadata.PropertyMetadata;
import org.jetlinks.core.metadata.SimplePropertyMetadata;
import org.jetlinks.core.metadata.types.DoubleType;
import org.jetlinks.core.metadata.types.StringType;
import org.jetlinks.core.things.ThingMetadata;
import org.reactivestreams.Publisher;
import reactor.core.publisher.*;

import java.sql.JDBCType;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;

import static org.mockito.Mockito.*;

final class CaggTestSupport {
    static final MetricBuilder METRIC = new MetricBuilder() {
        @Override public String getThingIdProperty() { return "deviceId"; }
    };

    static DatabaseOperator database(ReactiveSqlExecutor executor, String name) {
        RDBDatabaseMetadata metadata = new RDBDatabaseMetadata(Dialect.POSTGRES);
        metadata.addFeature(executor);
        RDBSchemaMetadata schema = TimescaleDBDialectProvider.GLOBAL.createSchema("public");
        metadata.addSchema(schema);
        metadata.setCurrentSchema(schema);
        RDBTableMetadata table = schema.newTable(name);
        for (String id : Arrays.asList("timestamp", "property", "deviceId", "numberValue", "value")) {
            RDBColumnMetadata column = new RDBColumnMetadata();
            column.setName(id);
            column.setAlias(id);
            column.setJdbcType("timestamp".equals(id) ? JDBCType.TIMESTAMP
                              : "numberValue".equals(id) ? JDBCType.DOUBLE : JDBCType.VARCHAR,
                              "timestamp".equals(id) ? LocalDateTime.class : Object.class);
            if ("timestamp".equals(id)) column.setValueCodec(new ShanghaiTimestampCodec());
            table.addColumn(column);
        }
        schema.addTable(table);
        return DefaultDatabaseOperator.of(metadata);
    }

    static AggregationRequest request(String interval, String from, String to, int limit) {
        AggregationRequest request = new AggregationRequest();
        request.setInterval(interval == null ? null : Interval.of(interval));
        request.setFrom(new Date(time(from)));
        request.setTo(new Date(time(to)));
        request.setFormat("yyyy-MM-dd HH:mm:ss");
        request.setLimit(limit);
        return request;
    }

    static long time(String local) {
        return LocalDateTime.parse(local.replace(' ', 'T')).atZone(AggregationWindow.ZONE)
                            .toInstant().toEpochMilli();
    }

    static TimescaleDBThingsDataProperties config() {
        TimescaleDBThingsDataProperties config = new TimescaleDBThingsDataProperties();
        config.setRetentionPolicy(Interval.of("3y"));
        config.getCagg().setEnabled(true);
        config.getCagg().setDailyEnabled(true);
        config.getCagg().setMonthlyEnabled(true);
        return config;
    }

    static class Query extends TimescaleDBRowModeQueryOperations {
        final ThingMetadata metadata;
        Query(DatabaseOperator database, TimescaleDBThingsDataProperties properties) {
            this(database, properties, null);
        }
        Query(DatabaseOperator database, TimescaleDBThingsDataProperties properties, String thingId) {
            super("device", "test", thingId, METRIC, new DataSettings(), null, database, properties);
            metadata = mock(ThingMetadata.class);
            when(metadata.getPropertyOrNull("p")).thenReturn(SimplePropertyMetadata.of("p", "P", new DoubleType()));
            when(metadata.getPropertyOrNull("p2")).thenReturn(SimplePropertyMetadata.of("p2", "P2", new DoubleType()));
            when(metadata.getPropertyOrNull("text")).thenReturn(SimplePropertyMetadata.of("text", "Text", new StringType()));
        }
        Flux<AggregationData> aggregate(String table, AggregationRequest request, PropertyAggregation... columns) {
            return Flux.defer(() -> doAggregation(table, request, new AggregationContext(metadata, columns)));
        }
        Mono<PropertyBoundaryValues> boundaries(String table,
                                                String property,
                                                long startTime,
                                                long endTime,
                                                List<Long> boundaries) {
            return queryPropertyAtBoundaries(
                table, startTime, endTime, boundaries, metadata, metadata.getPropertyOrNull(property));
        }
        Flux<TimeSeriesData> latest(String table, QueryParamEntity parameter) {
            org.hswebframework.ezorm.core.dsl.Query<?, QueryParamEntity> query = parameter.toNestQuery(q -> {
                applyQuery(q);
                q.and("property", "p");
            });
            return doQuery(table, query);
        }
        Flux<TimeSeriesData> regular(String table, QueryParamEntity parameter) {
            org.hswebframework.ezorm.core.dsl.Query<?, QueryParamEntity> query =
                parameter.toNestQuery(this::applyQuery);
            return doQuery(table, query);
        }
        Mono<org.hswebframework.web.api.crud.entity.PagerResult<TimeSeriesData>> page(
            String table, QueryParamEntity parameter) {
            org.hswebframework.ezorm.core.dsl.Query<?, QueryParamEntity> query =
                parameter.toNestQuery(this::applyQuery);
            return doQueryPage(table, query, Function.identity());
        }
        Flux<ThingPropertyDetail> each(String table,
                                       QueryParamEntity parameter,
                                       String... properties) {
            Map<String, PropertyMetadata> requested = new LinkedHashMap<>();
            for (String property : properties) {
                requested.put(property, metadata.getPropertyOrNull(property));
            }
            org.hswebframework.ezorm.core.dsl.Query<?, QueryParamEntity> query =
                parameter.toNestQuery(this::applyQuery);
            return queryEachProperty(table, query, metadata, requested);
        }
    }

    static class Executor implements ReactiveSqlExecutor {
        final List<SqlRequest> requests = new ArrayList<>();
        final List<SqlRequest> extensionSchemaRequests = new ArrayList<>();
        final List<SqlRequest> writes = new ArrayList<>();
        Function<SqlRequest, Flux<Map<String, Object>>> responder = request -> Flux.empty();
        String functionSchema = "public";
        RuntimeException functionSchemaError;

        @Override public Mono<Integer> update(Publisher<SqlRequest> requests) {
            return Mono.error(new UnsupportedOperationException());
        }
        @Override public Mono<Void> execute(Publisher<SqlRequest> publisher) {
            return Flux.from(publisher).concatMap(request -> {
                writes.add(request);
                return responder.apply(request);
            }).then();
        }
        @SuppressWarnings("unchecked")
        @Override public <E> Flux<E> select(Publisher<SqlRequest> publisher, ResultWrapper<E, ?> wrapper) {
            return Flux.from(publisher).concatMap(request -> {
                if (request.getSql().contains("FROM pg_extension")) {
                    extensionSchemaRequests.add(request);
                    return functionSchemaError == null
                        ? Flux.just(Map.of("extension_schema", functionSchema))
                        : Flux.error(functionSchemaError);
                }
                requests.add(request);
                return responder.apply(request);
            }).map(row -> (E) row);
        }
    }
}
