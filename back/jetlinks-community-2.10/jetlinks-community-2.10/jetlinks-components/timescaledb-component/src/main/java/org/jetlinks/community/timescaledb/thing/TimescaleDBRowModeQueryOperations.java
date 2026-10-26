/*
 * Copyright 2025 JetLinks https://www.jetlinks.cn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetlinks.community.timescaledb.thing;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.core.dsl.Query;
import org.hswebframework.ezorm.core.param.Sort;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.reactive.ReactiveSqlExecutor;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers;
import org.hswebframework.ezorm.rdb.mapping.defaults.record.Record;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.core.metadata.PropertyMetadata;
import org.jetlinks.core.metadata.types.NumberType;
import org.jetlinks.core.things.ThingMetadata;
import org.jetlinks.core.things.ThingsRegistry;
import org.jetlinks.community.Interval;
import org.jetlinks.community.things.data.AggregationRequest;
import org.jetlinks.community.things.data.PropertyBoundaryValues;
import org.jetlinks.community.things.data.PropertyAggregation;
import org.jetlinks.community.things.data.ThingPropertyDetail;
import org.jetlinks.community.things.data.ThingsDataConstants;
import org.jetlinks.community.things.data.operations.DataSettings;
import org.jetlinks.community.things.data.operations.MetricBuilder;
import org.jetlinks.community.things.data.operations.RowModeQueryOperationsBase;
import org.jetlinks.community.timescaledb.TimescaleDBUtils;
import org.jetlinks.community.timeseries.TimeSeriesData;
import org.jetlinks.community.timeseries.query.Aggregation;
import org.jetlinks.community.timeseries.query.AggregationData;
import org.jetlinks.reactor.ql.utils.CastUtils;
import org.slf4j.Logger;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.jetlinks.community.timescaledb.thing.ContinuousAggregateManager.quote;

@Slf4j
public class TimescaleDBRowModeQueryOperations extends RowModeQueryOperationsBase {
    private final DatabaseOperator database;
    private final TimescaleDBThingsDataProperties properties;
    private final String thingColumn;
    private final Set<String> thingIdColumns;

    public TimescaleDBRowModeQueryOperations(String thingType, String thingTemplateId, String thingId,
                                            MetricBuilder metricBuilder, DataSettings settings,
                                            ThingsRegistry registry, DatabaseOperator database,
                                            TimescaleDBThingsDataProperties properties) {
        super(thingType, thingTemplateId, thingId, metricBuilder, settings, registry);
        this.database = database;
        this.properties = properties;
        this.thingColumn = metricBuilder.getThingIdProperty();
        this.thingIdColumns = new HashSet<>(Arrays.asList(ThingsDataConstants.COLUMN_THING_ID,
                                                        "thing_id", thingColumn));
    }

    @Override
    protected Flux<TimeSeriesData> doQuery(String metric, Query<?, QueryParamEntity> query) {
        if (StringUtils.hasText(thingId) && isLatestPointQuery(query.getParam())) {
            QueryParamEntity filter = withoutPagingAndSort(query.getParam());
            SqlRequest source = database
                .dml()
                .query(TimescaleDBUtils.getTableName(metric))
                .setParam(filter)
                .getSql();
            return pointQuery(executor()
                .select(latestPropertySql(source), ResultWrappers.map())
                .map(this::convertToTimeSeriesData));
        }
        SqlRequest statement = query.execute(param -> database
            .dml()
            .query(TimescaleDBUtils.getTableName(metric))
            .setParam(param)
            .getSql());
        return timedQuery(executor()
            .select(statement, ResultWrappers.map())
            .map(this::convertToTimeSeriesData));
    }

    @Override
    protected <T> Mono<PagerResult<T>> doQueryPage(String metric, Query<?, QueryParamEntity> query,
                                                  Function<TimeSeriesData, T> mapper) {
        String table = TimescaleDBUtils.getTableName(metric);
        QueryParamEntity parameter = query.getParam();
        Mono<PagerResult<T>> result;
        if (parameter.getTotal() != null) {
            int total = parameter.getTotal();
            QueryParamEntity page = parameter.rePaging(total);
            result = selectPage(table, page, mapper)
                .map(rows -> PagerResult.of(total, rows, page));
        } else if (parameter.isParallelPager()) {
            Mono<Integer> total = count(table, parameter.clone());
            Mono<List<T>> rows = selectPage(table, parameter.clone(), mapper);
            result = Mono.zip(total, rows,
                (size, data) -> PagerResult.of(size, data, parameter));
        } else {
            result = count(table, parameter.clone()).flatMap(total -> {
                if (total == 0) {
                    return Mono.just(PagerResult.of(0, new ArrayList<>(), parameter));
                }
                QueryParamEntity page = parameter.clone().rePaging(total);
                return selectPage(table, page, mapper)
                    .map(rows -> PagerResult.of(total, rows, page));
            });
        }
        // One deadline covers count + page selection, including sequential mode.
        return timedQuery(result);
    }

    private <T> Mono<List<T>> selectPage(String table,
                                         QueryParamEntity parameter,
                                         Function<TimeSeriesData, T> mapper) {
        SqlRequest sql = database.dml().query(table).setParam(parameter).getSql();
        return executor()
            .select(sql, ResultWrappers.map())
            .map(this::convertToTimeSeriesData)
            .map(mapper)
            .collectList();
    }

    private Mono<Integer> count(String table, QueryParamEntity parameter) {
        parameter.setPaging(false);
        parameter.setSorts(new ArrayList<>());
        parameter.setForUpdate(false);
        SqlRequest source = database.dml().query(table).setParam(parameter).getSql();
        return executor()
            .select(countSql(source), ResultWrappers.map())
            .next()
            .map(row -> ((Number) row.get("_total")).intValue())
            .defaultIfEmpty(0);
    }

    static SqlRequest countSql(SqlRequest source) {
        return SqlRequests.of(
            "SELECT COUNT(1) AS _total FROM (" + source.getSql() + ") count_source",
            source.getParameters());
    }

    @Override
    protected Flux<ThingPropertyDetail> queryEachProperty(String metric,
                                                          Query<?, QueryParamEntity> query,
                                                          ThingMetadata metadata,
                                                          Map<String, PropertyMetadata> requested) {
        if (!StringUtils.hasText(thingId)
            || requested.size() < 2
            || !isLatestPointQuery(query.getParam())) {
            return super.queryEachProperty(metric, query, metadata, requested);
        }
        return Flux.defer(() -> {
            QueryParamEntity filter = withoutPagingAndSort(query.getParam());
            // Keep the mandatory thing scope in the same nested predicate as
            // caller filters so an OR term can never escape it.
            filter.toNestQuery(q -> q.in(ThingsDataConstants.COLUMN_PROPERTY_ID, requested.keySet()));
            SqlRequest source = database
                .dml()
                .query(TimescaleDBUtils.getTableName(metric))
                .setParam(filter)
                .getSql();
            Flux<ThingPropertyDetail> latest = executor()
                .select(latestPropertiesSql(source), ResultWrappers.map())
                .map(this::convertToTimeSeriesData)
                .mapNotNull(data -> applyProperty(
                    ThingPropertyDetail.of(
                        data,
                        requested.get(data.getString(ThingsDataConstants.COLUMN_PROPERTY_ID, null))),
                    data.getData()));
            return pointQuery(latest);
        });
    }

    static boolean isLatestPointQuery(QueryParamEntity param) {
        if (!param.isPaging() || param.getPageIndex() != 0 || param.getPageSize() != 1) {
            return false;
        }
        List<Sort> sorts = param.getSorts();
        if (sorts == null || sorts.isEmpty()) {
            return true;
        }
        // The DESC shortcut must not replace an earliest-reading or custom-order query.
        if (sorts.size() != 1) {
            return false;
        }
        Sort sort = sorts.get(0);
        return sort != null && ThingsDataConstants.COLUMN_TIMESTAMP.equals(sort.getName())
            && "desc".equalsIgnoreCase(sort.getOrder())
            && sort.getType() == null && sort.getValue() == null
            && (sort.getOpts() == null || sort.getOpts().isEmpty());
    }

    private static QueryParamEntity withoutPagingAndSort(QueryParamEntity source) {
        QueryParamEntity filter = source.clone();
        filter.setPaging(false);
        filter.setSorts(new ArrayList<>());
        return filter;
    }

    static SqlRequest latestPropertySql(SqlRequest source) {
        return SqlRequests.of(
            "SELECT latest_source.* FROM (" + source.getSql() + ") latest_source "
                + "ORDER BY \"timestamp\" DESC LIMIT 1",
            source.getParameters());
    }

    static SqlRequest latestPropertiesSql(SqlRequest source) {
        return SqlRequests.of(
            "SELECT DISTINCT ON (\"property\") latest_source.* FROM (" + source.getSql() + ") latest_source "
                + "ORDER BY \"property\",\"timestamp\" DESC",
            source.getParameters());
    }

    @Override
    protected Mono<PropertyBoundaryValues> queryPropertyAtBoundaries(String metric,
                                                                      long startTime,
                                                                      long endTime,
                                                                      List<Long> boundaries,
                                                                      ThingMetadata metadata,
                                                                      PropertyMetadata property) {
        if (!StringUtils.hasText(thingId)) {
            return super.queryPropertyAtBoundaries(
                metric, startTime, endTime, boundaries, metadata, property);
        }
        SqlRequest sql = boundaryValuesSql(
            TimescaleDBUtils.getTableName(metric), property.getId(), startTime, endTime, boundaries);
        return pointQueryList(executor()
            .select(sql, ResultWrappers.map()))
            .map(rows -> toBoundaryValues(property, startTime, endTime, boundaries, rows));
    }

    SqlRequest boundaryValuesSql(String table,
                                 String property,
                                 long startTime,
                                 long endTime,
                                 List<Long> boundaries) {
        String qualified = quote(database.getMetadata().getCurrentSchema().getName())
            + "." + quote(table);
        String requested = boundaries
            .stream()
            .map(ignore -> "?::timestamp")
            .collect(Collectors.joining(","));
        String sql = "WITH baseline AS ("
            + "SELECT \"value\" FROM " + qualified
            + " WHERE " + quote(thingColumn) + "=? AND \"property\"=?"
            + " AND \"timestamp\"<=?::timestamp AND \"value\" IS NOT NULL"
            + " ORDER BY \"timestamp\" DESC LIMIT 1),"
            + "first_in_range AS (SELECT \"value\" FROM " + qualified
            + " WHERE " + quote(thingColumn) + "=? AND \"property\"=?"
            + " AND \"timestamp\">=?::timestamp AND \"timestamp\"<=?::timestamp"
            + " AND \"value\" IS NOT NULL ORDER BY \"timestamp\" ASC LIMIT 1),"
            + "first_after_range AS (SELECT \"value\" FROM " + qualified
            + " WHERE " + quote(thingColumn) + "=? AND \"property\"=?"
            + " AND \"timestamp\">?::timestamp AND \"timestamp\"<=?::timestamp"
            + " AND \"value\" IS NOT NULL ORDER BY \"timestamp\" ASC LIMIT 1),"
            + "initial_value AS (SELECT \"value\" FROM baseline UNION ALL "
            + "SELECT \"value\" FROM first_in_range WHERE NOT EXISTS (SELECT 1 FROM baseline) LIMIT 1),"
            + "requested AS (SELECT boundary,ordinality FROM unnest(ARRAY[" + requested + "])"
            + " WITH ORDINALITY AS input(boundary,ordinality)) "
            + "SELECT requested.ordinality AS _ordinal,"
            + "CASE WHEN requested.boundary=?::timestamp AND (point.\"timestamp\" IS NULL"
            + " OR point.\"timestamp\"<?::timestamp) THEN first_after_range.\"value\""
            + " ELSE point.\"value\" END AS _boundary_value,"
            + "initial_value.\"value\" AS _initial_value FROM requested "
            + "LEFT JOIN LATERAL (SELECT \"value\",\"timestamp\" FROM " + qualified
            + " WHERE " + quote(thingColumn) + "=? AND \"property\"=?"
            + " AND \"timestamp\"<=requested.boundary AND \"value\" IS NOT NULL"
            + " ORDER BY \"timestamp\" DESC LIMIT 1) point ON TRUE "
            + "LEFT JOIN initial_value ON TRUE LEFT JOIN first_after_range ON TRUE"
            + " ORDER BY requested.ordinality";

        List<Object> parameters = new ArrayList<>();
        parameters.add(thingId);
        parameters.add(property);
        parameters.add(AggregationWindow.local(startTime));
        parameters.add(thingId);
        parameters.add(property);
        parameters.add(AggregationWindow.local(startTime));
        parameters.add(AggregationWindow.local(endTime));
        parameters.add(thingId);
        parameters.add(property);
        parameters.add(AggregationWindow.local(endTime));
        parameters.add(AggregationWindow.local(System.currentTimeMillis()));
        boundaries.stream().map(AggregationWindow::local).forEach(parameters::add);
        parameters.add(AggregationWindow.local(endTime));
        parameters.add(AggregationWindow.local(startTime));
        parameters.add(thingId);
        parameters.add(property);
        return SqlRequests.of(sql, parameters.toArray());
    }

    private PropertyBoundaryValues toBoundaryValues(PropertyMetadata property,
                                                     long startTime,
                                                     long endTime,
                                                     List<Long> boundaries,
                                                     List<Map<String, Object>> rows) {
        Object initial = rows.isEmpty() ? null : convertPropertyValue(rows.get(0).get("_initial_value"), property);
        Object[] values = new Object[boundaries.size()];
        for (Map<String, Object> row : rows) {
            Object ordinal = row.get("_ordinal");
            if (ordinal instanceof Number) {
                int index = ((Number) ordinal).intValue() - 1;
                if (index >= 0 && index < values.length) {
                    values[index] = convertPropertyValue(row.get("_boundary_value"), property);
                }
            }
        }
        List<PropertyBoundaryValues.BoundaryValue> result = new ArrayList<>(boundaries.size());
        for (int i = 0; i < boundaries.size(); i++) {
            result.add(new PropertyBoundaryValues.BoundaryValue(boundaries.get(i), values[i]));
        }
        return new PropertyBoundaryValues(property.getId(), startTime, endTime, initial, result);
    }

    private Object convertPropertyValue(Object value, PropertyMetadata property) {
        return value == null ? null : ThingPropertyDetail.of(value, property).getValue();
    }

    private ReactiveSqlExecutor executor() {
        return database.getMetadata().getFeature(CancellableAggregationExecutor.ID)
            .<ReactiveSqlExecutor>map(value -> value)
            .orElseGet(() -> database.getMetadata().getFeatureNow(ReactiveSqlExecutor.ID));
    }

    private <T> Flux<T> pointQuery(Flux<T> source) {
        return pointQueryList(source).flatMapMany(Flux::fromIterable);
    }

    /**
     * Stream rows immediately while enforcing one deadline for the whole SQL
     * subscription. The timeout context is also consumed by
     * {@link CancellableAggregationExecutor} to install PostgreSQL's
     * {@code statement_timeout}; an expired deadline cancels the upstream so
     * the executor can issue a protocol-level cancel request.
     */
    private <T> Flux<T> timedQuery(Flux<T> source) {
        Duration timeout = properties.getAggregation().getTimeout();
        // Cache one timer so the per-row timeout publisher keeps the original
        // absolute deadline instead of restarting the clock for every row.
        Mono<Long> deadline = Mono.delay(timeout).cache();
        Flux<T> timeoutError = Flux.error(new TimeoutException(
            "TimescaleDB query exceeded " + timeout.toMillis() + "ms"));
        return source
            .timeout(deadline, ignored -> deadline, timeoutError)
            .contextWrite(ctx -> ctx
                .put(Logger.class, log)
                .put(CancellableAggregationExecutor.TIMEOUT_CONTEXT, timeout));
    }

    private <T> Mono<T> timedQuery(Mono<T> source) {
        return timedQuery(source.flux()).single();
    }

    private <T> Mono<List<T>> pointQueryList(Flux<T> source) {
        Duration timeout = properties.getAggregation().getTimeout();
        return source
            .collectList()
            // Bound total execution time, not merely the pause between two rows.
            .timeout(timeout)
            .contextWrite(ctx -> ctx
                .put(Logger.class, log)
                .put(CancellableAggregationExecutor.TIMEOUT_CONTEXT, timeout));
    }

    static final String timestampAlias = "_ts";

    @Override
    protected Flux<AggregationData> doAggregation(String metric, AggregationRequest request,
                                                   AggregationContext context) {
        return Flux.defer(() -> {
            String table = TimescaleDBUtils.getTableName(metric);
            PropertyAggregation[] columns = context.getProperties();
            AggregationWindow window = AggregationWindow.create(request, columns, properties.getAggregation());
            for (PropertyAggregation column : columns) {
                if (context.getMetadata().getPropertyOrNull(column.getProperty()) == null) {
                    // Keep the legacy bucket shape without querying storage for an undeclared property.
                    return Flux.fromIterable(window.rows.values()).map(row -> {
                        for (PropertyAggregation requested : columns) {
                            row.put(requested.getAlias(), null);
                        }
                        return AggregationData.of(row);
                    });
                }
            }
            ReactiveSqlExecutor executor = executor();
            ContinuousAggregateManager manager = new ContinuousAggregateManager(
                database.getMetadata().getCurrentSchema().getName(), executor, properties, thingColumn);
            return manager.functionSchema().flatMapMany(functionSchema -> {
                Mono<SqlRequest> sql = chooseLayer(manager, table, request, context, window)
                    .map(layer -> aggregateSql(
                        table, request, context, window, layer, manager, functionSchema))
                    .switchIfEmpty(Mono.fromSupplier(() -> rawSql(
                        table, request, context, window, functionSchema)));
                return sql.flatMapMany(statement -> executor.select(statement, ResultWrappers.map()))
                    // A dropped/changed view between catalog inspection and SELECT may safely retry on raw data.
                    .onErrorResume(this::missingRelation, error -> {
                        log.warn("Cagg schema changed during query for {}; retrying raw", table);
                        manager.invalidate(table);
                        return executor.select(
                            rawSql(table, request, context, window, functionSchema), ResultWrappers.map());
                    });
            })
                .doOnNext(row -> {
                    Object time = row.get(timestampAlias);
                    long timestamp = request.getInterval() == null ? 0L
                        : ((Number) new ShanghaiTimestampCodec().decode(time)).longValue();
                    Map<String, Object> target = window.rows.get(timestamp);
                    if (target == null) {
                        throw new IllegalStateException("SQL and Java bucket grids disagree: " + time);
                    }
                    for (int i = 0; i < columns.length; i++) {
                        Object value = row.get("_a" + i);
                        if (value != null) {
                            target.put(columns[i].getAlias(), value);
                        }
                    }
                })
                .then()
                // Timeout covers the entire catalog + SQL operation, not just inactivity between rows.
                .timeout(properties.getAggregation().getTimeout())
                .thenMany(Flux.fromIterable(window.rows.values()).map(AggregationData::of))
                .contextWrite(ctx -> ctx.put(Logger.class, log)
                    .put(CancellableAggregationExecutor.TIMEOUT_CONTEXT, properties.getAggregation().getTimeout()));
        });
    }

    private boolean missingRelation(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof io.r2dbc.spi.R2dbcException) {
                String state = ((io.r2dbc.spi.R2dbcException) current).getSqlState();
                return "42P01".equals(state) || "42703".equals(state);
            }
        }
        return false;
    }

    private Mono<ContinuousAggregateManager.Layer> chooseLayer(ContinuousAggregateManager manager, String table,
                                                               AggregationRequest request,
                                                               AggregationContext context, AggregationWindow window) {
        Interval interval = request.getInterval();
        if (interval == null || !withinRetention(window.from, System.currentTimeMillis())
            || !isSafeForCagg(request.getFilter().getTerms())
            || Arrays.stream(context.getProperties()).anyMatch(p ->
                p.getAgg() == Aggregation.DISTINCT_COUNT || (bookend(p.getAgg()) && !numeric(context, p)))) {
            return Mono.empty();
        }
        List<ContinuousAggregateManager.Layer> layers = manager.layers();
        layers.removeIf(layer -> !compatibleInterval(interval, layer));
        if (layers.isEmpty()) return Mono.empty();
        Collections.reverse(layers);
        return Flux.fromIterable(layers)
            .concatMap(layer -> manager.ready(table, layer).filter(Boolean.TRUE::equals).map(ignore -> layer))
            .next()
            .switchIfEmpty(Mono.defer(() -> {
                log.warn("No compatible and ready continuous aggregate for {}; falling back to raw data", table);
                return Mono.empty();
            }))
            .flatMap(layer -> configuredCaggFresh(table, context, window, manager, layer)
                .doOnNext(fresh -> {
                    if (!fresh) log.debug("Counter CAGG {} lacks current proof; using raw data", table);
                })
                .filter(Boolean.TRUE::equals).map(ignore -> layer));
    }

    /** Full materialized buckets only; aggregateSql already reads both partial edges from raw. */
    static long[] fullCaggWindow(AggregationWindow window, ContinuousAggregateManager.Layer layer) {
        long start = AggregationWindow.floor(window.from, layer.width);
        if (start < window.from) start = AggregationWindow.advance(start, layer.width, 1);
        return new long[]{start, AggregationWindow.floor(window.toExclusive, layer.width)};
    }

    /**
     * A structurally ready CAGG can still be stale after historical raw writes.
     * One SQL statement checks every enabled counter property in this projection;
     * a dirty/pending range or any gap in current-version proofs sends all columns to raw.
     * Unconfigured properties produce no rows and retain the existing CAGG route.
     */
    Mono<Boolean> configuredCaggFresh(String table, AggregationContext context,
                                      AggregationWindow window, ContinuousAggregateManager manager,
                                      ContinuousAggregateManager.Layer layer) {
        long[] full = fullCaggWindow(window, layer);
        if (full[0] >= full[1]) return Mono.just(true);
        Set<String> properties = propertyIds(context);
        if (properties.isEmpty()) return Mono.just(true);
        LocalDateTime from = AggregationWindow.local(full[0]);
        LocalDateTime to = AggregationWindow.local(full[1]);
        List<Object> params = new ArrayList<>();
        // c.pending, d.dirty, then p.proof; SQL ranges are [from,to).
        Collections.addAll(params, to, from, to, from, from, to, to, from, table);
        params.addAll(properties);
        String sql = "SELECT c.property_id,"
            + " (c.pending_from IS NOT NULL AND c.pending_to IS NOT NULL"
            + " AND c.pending_from<?::timestamp AND c.pending_to>=?::timestamp"
            + " OR EXISTS (SELECT 1 FROM " + manager.qualified("counter_snapshot_change")
            + " d WHERE d.product_id=c.product_id AND d.property_id=c.property_id"
            + " AND d.dirty_from<?::timestamp"
            + " AND (d.dirty_to IS NULL OR d.dirty_to>=?::timestamp))) AS pending,"
            + " COALESCE((SELECT range_agg(tsrange(p.from_time,p.to_time,'[)'))"
            + " @> tsrange(?::timestamp,?::timestamp,'[)') FROM "
            + manager.qualified("counter_cagg_refresh") + " p"
            + " WHERE p.product_id=c.product_id AND p.property_id=c.property_id"
            + " AND p.raw_table=c.raw_table AND p.layer=?"
            + " AND p.source_version>=c.source_version"
            + " AND p.from_time<?::timestamp AND p.to_time>?::timestamp),false) AS proven"
            + " FROM " + manager.qualified("counter_snapshot_config") + " c"
            + " WHERE c.raw_table=? AND c.enabled=true AND c.property_id IN ("
            + String.join(",", Collections.nCopies(properties.size(), "?")) + ")";
        // Insert layer before proof overlap bounds, preserving all bound parameters.
        params.add(6, layer.name());
        return executor().select(sql, params.toArray())
            .collectList()
            .map(rows -> rows.stream().allMatch(row -> Boolean.FALSE.equals(row.get("pending"))
                && Boolean.TRUE.equals(row.get("proven"))))
            .onErrorResume(this::missingRelation, error -> executor()
                .select("SELECT to_regclass(?) AS config_table",
                    manager.qualified("counter_snapshot_config"))
                .next().map(row -> row.get("config_table") == null)
                .defaultIfEmpty(false));
    }

    boolean withinRetention(long from, long now) {
        Interval retention = properties.getCagg().getRetention();
        if (retention == null) return true;
        AggregationWindow.validateInterval(retention);
        // A materialized watermark can survive removal of old aggregate chunks.
        // Real-time aggregation cannot recover those rows, so use raw data there.
        return from >= AggregationWindow.advance(now, retention, -1);
    }

    static boolean compatibleInterval(Interval interval, ContinuousAggregateManager.Layer layer) {
        AggregationWindow.validateInterval(interval);
        if (AggregationWindow.calendar(interval)) {
            return true;
        }
        return layer != ContinuousAggregateManager.Layer.MONTHLY
            && interval.toMillis() >= layer.width.toMillis()
            && interval.toMillis() % layer.width.toMillis() == 0;
    }

    private boolean numeric(AggregationContext context, PropertyAggregation property) {
        return context.getMetadata().getPropertyOrNull(property.getProperty()).getValueType() instanceof NumberType;
    }

    private static boolean bookend(Aggregation aggregation) {
        return aggregation == Aggregation.FIRST || aggregation == Aggregation.LAST || aggregation == Aggregation.TOP;
    }

    private Set<String> propertyIds(AggregationContext context) {
        return Arrays.stream(context.getProperties()).map(PropertyAggregation::getProperty)
                     .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private SqlRequest rawSource(String table, AggregationRequest request, AggregationContext context,
                                 long from, long to) {
        QueryParamEntity filter = request.getFilter().clone();
        normalizeThingColumns(filter.getTerms());
        filter.setSorts(new ArrayList<>());
        filter.setPaging(false);
        // A separate outer nest prevents OR filters from escaping time/property constraints.
        filter.toNestQuery(q -> q.gte("timestamp", AggregationWindow.local(from))
                                .lt("timestamp", AggregationWindow.local(to))
                                .in("property", propertyIds(context)));
        Set<String> selected = new LinkedHashSet<>();
        selected.add("timestamp");
        selected.add("property");
        for (PropertyAggregation aggregation : context.getProperties()) {
            switch (aggregation.getAgg()) {
                case COUNT:
                    break;
                case FIRST:
                case LAST:
                case TOP:
                    selected.add(numeric(context, aggregation) ? "numberValue" : "value");
                    selected.add(thingColumn);
                    break;
                default:
                    selected.add("numberValue");
                    break;
            }
        }
        return database.dml().query(table).setParam(filter)
            .select(selected.toArray(new String[0])).getSql();
    }

    private void normalizeThingColumns(List<Term> terms) {
        if (terms == null) return;
        for (Term term : terms) {
            if (thingIdColumns.contains(term.getColumn())) {
                term.setColumn(thingColumn);
            }
            normalizeThingColumns(term.getTerms());
        }
    }

    private SqlRequest rawSql(String table, AggregationRequest request, AggregationContext context,
                              AggregationWindow window, String functionSchema) {
        SqlRequest source = rawSource(table, request, context, window.from, window.toExclusive);
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ")
            .append(AggregationWindow.bucketSql(
                request.getInterval(), "\"timestamp\"", functionSchema))
            .append(" AS _ts,property");
        PropertyAggregation[] columns = context.getProperties();
        for (int i = 0; i < columns.length; i++) {
            PropertyAggregation p = columns[i];
            sql.append(",CASE WHEN property=? THEN ")
               .append(rawExpression(p.getAgg(), numeric(context, p), functionSchema))
               .append(" END AS _a").append(i);
            parameters.add(p.getProperty());
        }
        sql.append(" FROM (").append(source.getSql()).append(") raw_data GROUP BY 1,2");
        appendLimit(sql, window, context);
        parameters.addAll(Arrays.asList(source.getParameters()));
        return SqlRequests.of(sql.toString(), parameters.toArray());
    }

    private String rawExpression(Aggregation aggregation, boolean numeric, String functionSchema) {
        String value = numeric ? "\"numberValue\"" : "\"value\"";
        switch (aggregation) {
            case FIRST:
            case TOP:
                return ContinuousAggregateManager.function(functionSchema, "first")
                    + "(" + value + "," + key("\"timestamp\"", quote(thingColumn)) + ")";
            case LAST:
                return ContinuousAggregateManager.function(functionSchema, "last")
                    + "(" + value + "," + key("\"timestamp\"", quote(thingColumn)) + ")";
            default:
                return createAggFunction(aggregation, functionSchema);
        }
    }

    String createAggFunction(Aggregation aggregation, String functionSchema) {
        switch (aggregation) {
            case COUNT: return "count(*)";
            case DISTINCT_COUNT: return "count(distinct \"numberValue\")";
            case AVG: return "avg(\"numberValue\")";
            case MAX: return "max(\"numberValue\")";
            case MIN: return "min(\"numberValue\")";
            case SUM: return "sum(\"numberValue\")";
            case FIRST:
            case TOP: return ContinuousAggregateManager.function(functionSchema, "first")
                + "(\"numberValue\"," + key("\"timestamp\"", quote(thingColumn)) + ")";
            case LAST: return ContinuousAggregateManager.function(functionSchema, "last")
                + "(\"numberValue\"," + key("\"timestamp\"", quote(thingColumn)) + ")";
            default: throw AggregationWindow.invalid("不支持的聚合函数: " + aggregation);
        }
    }

    /** Fixed-width timestamp + device id gives deterministic ordering without array_agg memory growth. */
    static String key(String time, String device) {
        return "(to_char(" + time + ",'YYYY-MM-DD HH24:MI:SS.US') || " + device + ") COLLATE \"C\"";
    }

    private SqlRequest aggregateSql(String table, AggregationRequest request, AggregationContext context,
                                    AggregationWindow window, ContinuousAggregateManager.Layer layer,
                                    ContinuousAggregateManager manager, String functionSchema) {
        StateProjection projection = StateProjection.of(context.getProperties());
        long[] full = fullCaggWindow(window, layer);
        long start = full[0];
        long end = full[1];
        if (start >= end) {
            return rawSql(table, request, context, window, functionSchema);
        }
        List<SqlRequest> parts = new ArrayList<>();
        if (window.from < start) {
            parts.add(rawState(
                table, request, context, window.from, start, projection, functionSchema));
        }
        List<Object> values = new ArrayList<>();
        String filter = caggFilter(request.getFilter().getTerms(), values);
        Set<String> ids = propertyIds(context);
        String cagg = "SELECT "
            + AggregationWindow.bucketSql(request.getInterval(), "bucket_start", functionSchema)
            + " AS _ts,property" + projection.caggColumns(thingColumn) + " FROM "
            + manager.qualified(table + layer.suffix)
            + " WHERE (" + filter + ") AND bucket_start>=?::timestamp AND bucket_start<?::timestamp"
            + " AND property IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")";
        values.add(AggregationWindow.local(start));
        values.add(AggregationWindow.local(end));
        values.addAll(ids);
        parts.add(SqlRequests.of(cagg, values.toArray()));
        if (end < window.toExclusive) {
            parts.add(rawState(
                table, request, context, end, window.toExclusive, projection, functionSchema));
        }
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT _ts,property");
        PropertyAggregation[] columns = context.getProperties();
        for (int i = 0; i < columns.length; i++) {
            sql.append(",CASE WHEN property=? THEN ")
               .append(stateExpression(columns[i].getAgg(), functionSchema))
               .append(" END AS _a").append(i);
            parameters.add(columns[i].getProperty());
        }
        sql.append(" FROM (")
           .append(parts.stream().map(SqlRequest::getSql).collect(Collectors.joining(" UNION ALL ")))
           .append(") states GROUP BY 1,2");
        appendLimit(sql, window, context);
        parts.forEach(part -> parameters.addAll(Arrays.asList(part.getParameters())));
        return SqlRequests.of(sql.toString(), parameters.toArray());
    }

    private SqlRequest rawState(String table,
                                AggregationRequest request,
                                AggregationContext context,
                                long from,
                                long to,
                                StateProjection projection,
                                String functionSchema) {
        SqlRequest source = rawSource(table, request, context, from, to);
        return SqlRequests.of(
            "SELECT " + AggregationWindow.bucketSql(
                request.getInterval(), "\"timestamp\"", functionSchema)
                + " AS _ts,property" + projection.rawColumns(thingColumn, functionSchema)
                + " FROM (" + source.getSql() + ") raw_data GROUP BY 1,2",
            source.getParameters());
    }

    static final class StateProjection {
        private boolean sum;
        private boolean min;
        private boolean max;
        private boolean sampleCount;
        private boolean numericCount;
        private boolean first;
        private boolean last;

        static StateProjection of(PropertyAggregation[] aggregations) {
            StateProjection projection = new StateProjection();
            for (PropertyAggregation aggregation : aggregations) {
                switch (aggregation.getAgg()) {
                    case AVG:
                        projection.sum = true;
                        projection.numericCount = true;
                        break;
                    case SUM:
                        projection.sum = true;
                        break;
                    case COUNT:
                        projection.sampleCount = true;
                        break;
                    case MIN:
                        projection.min = true;
                        break;
                    case MAX:
                        projection.max = true;
                        break;
                    case FIRST:
                    case TOP:
                        projection.first = true;
                        break;
                    case LAST:
                        projection.last = true;
                        break;
                    default:
                        throw AggregationWindow.invalid(
                            "Unsupported pre-aggregation function: " + aggregation.getAgg());
                }
            }
            return projection;
        }

        String caggColumns(String thingColumn) {
            StringBuilder sql = new StringBuilder();
            append(sql, sum, "sum_value");
            append(sql, min, "min_value");
            append(sql, max, "max_value");
            append(sql, sampleCount, "sample_count");
            append(sql, numericCount, "numeric_count");
            if (first) {
                sql.append(",first_value,")
                   .append(key("first_timestamp", quote(thingColumn)))
                   .append(" AS first_key");
            }
            if (last) {
                sql.append(",last_value,")
                   .append(key("last_timestamp", quote(thingColumn)))
                   .append(" AS last_key");
            }
            return sql.toString();
        }

        String rawColumns(String thingColumn, String functionSchema) {
            StringBuilder sql = new StringBuilder();
            append(sql, sum, "sum(\"numberValue\") AS sum_value");
            append(sql, min, "min(\"numberValue\") AS min_value");
            append(sql, max, "max(\"numberValue\") AS max_value");
            append(sql, sampleCount, "count(*) AS sample_count");
            append(sql, numericCount, "count(\"numberValue\") AS numeric_count");
            String order = key("\"timestamp\"", quote(thingColumn));
            if (first) {
                sql.append(',').append(ContinuousAggregateManager.function(functionSchema, "first"))
                   .append("(\"numberValue\",").append(order).append(") AS first_value")
                   .append(",min(").append(order).append(") AS first_key");
            }
            if (last) {
                sql.append(',').append(ContinuousAggregateManager.function(functionSchema, "last"))
                   .append("(\"numberValue\",").append(order).append(") AS last_value")
                   .append(",max(").append(order).append(") AS last_key");
            }
            return sql.toString();
        }

        private static void append(StringBuilder sql, boolean required, String expression) {
            if (required) {
                sql.append(',').append(expression);
            }
        }
    }

    static String stateExpression(Aggregation aggregation, String functionSchema) {
        switch (aggregation) {
            case AVG: return "sum(sum_value)/nullif(sum(numeric_count),0)";
            case SUM: return "sum(sum_value)";
            case COUNT: return "sum(sample_count)::bigint";
            case MAX: return "max(max_value)";
            case MIN: return "min(min_value)";
            case FIRST:
            case TOP: return ContinuousAggregateManager.function(functionSchema, "first")
                + "(first_value,first_key)";
            case LAST: return ContinuousAggregateManager.function(functionSchema, "last")
                + "(last_value,last_key)";
            default: throw AggregationWindow.invalid("不支持的预聚合函数: " + aggregation);
        }
    }

    private void appendLimit(StringBuilder sql, AggregationWindow window, AggregationContext context) {
        sql.append(" ORDER BY 1 DESC LIMIT ").append((long) window.rows.size() * propertyIds(context).size());
    }

    boolean isSafeForCagg(List<Term> terms) {
        if (terms == null) return true;
        for (Term term : terms) {
            if (term.getTerms() != null && !term.getTerms().isEmpty()) {
                if (term.getColumn() != null || !isSafeForCagg(term.getTerms())) return false;
            } else if (!thingIdColumns.contains(term.getColumn()) || term.getValue() == null
                       || (term.getOptions() != null && !term.getOptions().isEmpty())
                       || !("eq".equals(term.getTermType()) || "in".equals(term.getTermType()))
                       || ("eq".equals(term.getTermType()) && !(term.getValue() instanceof String))
                       || ("in".equals(term.getTermType())
                           && (!(term.getValue() instanceof Collection)
                               || ((Collection<?>) term.getValue()).stream().anyMatch(value -> !(value instanceof String))))) {
                return false;
            }
        }
        return true;
    }

    String caggFilter(List<Term> terms, List<Object> parameters) {
        if (terms == null || terms.isEmpty()) return "TRUE";
        StringBuilder result = new StringBuilder();
        for (Term term : terms) {
            if (result.length() > 0) {
                result.append(term.getType() == Term.Type.or ? " OR " : " AND ");
            }
            if (term.getTerms() != null && !term.getTerms().isEmpty()) {
                result.append("(").append(caggFilter(term.getTerms(), parameters)).append(")");
            } else if ("in".equals(term.getTermType())) {
                Collection<?> values = (Collection<?>) term.getValue();
                if (values.isEmpty()) {
                    result.append("FALSE");
                } else {
                    result.append(quote(thingColumn)).append(" IN (")
                          .append(String.join(",", Collections.nCopies(values.size(), "?"))).append(")");
                    values.forEach(value -> parameters.add(String.valueOf(value)));
                }
            } else {
                result.append(quote(thingColumn)).append("=?");
                parameters.add(String.valueOf(term.getValue()));
            }
        }
        return result.toString();
    }

    private TimeSeriesData convertToTimeSeriesData(Record record) {
        return TimeSeriesData.of(record.get(ThingsDataConstants.COLUMN_TIMESTAMP)
            .map(val -> CastUtils.castNumber(val).longValue()).orElseGet(System::currentTimeMillis), record);
    }

    private TimeSeriesData convertToTimeSeriesData(Map<String, Object> source) {
        Map<String, Object> row = new LinkedHashMap<>(source);
        Object timestamp = row.get(ThingsDataConstants.COLUMN_TIMESTAMP);
        long value = timestamp == null
            ? System.currentTimeMillis()
            : ((Number) new ShanghaiTimestampCodec().decode(timestamp)).longValue();
        row.put(ThingsDataConstants.COLUMN_TIMESTAMP, value);
        return TimeSeriesData.of(value, row);
    }
}
