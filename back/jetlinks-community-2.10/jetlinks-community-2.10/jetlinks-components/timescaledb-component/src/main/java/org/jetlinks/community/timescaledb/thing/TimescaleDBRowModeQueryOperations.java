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

import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.core.Conditional;
import org.hswebframework.ezorm.core.dsl.Query;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.core.param.TermType;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers;
import org.hswebframework.ezorm.rdb.mapping.defaults.record.Record;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.ezorm.rdb.operator.dml.QueryOperator;
import org.hswebframework.ezorm.rdb.operator.dml.query.NativeSelectColumn;
import org.hswebframework.ezorm.rdb.operator.dml.query.SelectColumn;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.metadata.EventMetadata;
import org.jetlinks.core.things.ThingsRegistry;
import org.jetlinks.community.things.data.AggregationRequest;
import org.jetlinks.community.things.data.PropertyAggregation;
import org.jetlinks.community.things.data.ThingsDataConstants;
import org.jetlinks.community.things.data.ThingsDataUtils;
import org.jetlinks.community.things.data.operations.DataSettings;
import org.jetlinks.community.things.data.operations.MetricBuilder;
import org.jetlinks.community.things.data.operations.RowModeQueryOperationsBase;
import org.jetlinks.community.timescaledb.TimescaleDBUtils;
import org.jetlinks.community.timescaledb.thing.TimescaleDBThingsDataProperties;
import org.jetlinks.community.timeseries.TimeSeriesData;
import org.jetlinks.community.timeseries.query.Aggregation;
import org.jetlinks.community.timeseries.query.AggregationData;
import org.jetlinks.reactor.ql.utils.CastUtils;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.Function;

import static org.hswebframework.ezorm.core.param.TermType.eq;
import static org.hswebframework.ezorm.core.param.TermType.in;

import static org.jetlinks.community.timescaledb.thing.TimescaleDBColumnModeQueryOperations.doAggregation0;

@Slf4j
public class TimescaleDBRowModeQueryOperations extends RowModeQueryOperationsBase {
    private final DatabaseOperator database;
    private final TimescaleDBThingsDataProperties properties;

    public TimescaleDBRowModeQueryOperations(String thingType,
                                             String thingTemplateId,
                                             String thingId,
                                             MetricBuilder metricBuilder,
                                             DataSettings settings,
                                             ThingsRegistry registry,
                                             DatabaseOperator database,
                                             TimescaleDBThingsDataProperties properties) {
        super(thingType, thingTemplateId, thingId, metricBuilder, settings, registry);
        this.database = database;
        this.properties = properties;
    }

    @Override
    protected Flux<TimeSeriesData> doQuery(String metric, Query<?, QueryParamEntity> query) {

        return query
            .execute(
                database
                    .dml()
                    .createReactiveRepository(TimescaleDBUtils.getTableName(metric))
                    .createQuery()::setParam
            )
            .fetch()
            .map(this::convertToTimeSeriesData)
            .contextWrite(ctx -> ctx.put(Logger.class, log));
    }

    @Override
    protected <T> Mono<PagerResult<T>> doQueryPage(String metric,
                                                   Query<?, QueryParamEntity> query,
                                                   Function<TimeSeriesData, T> mapper) {
        return QueryHelper
            .queryPager(
                query.getParam(),
                () -> database
                    .dml()
                    .createReactiveRepository(TimescaleDBUtils.getTableName(metric))
                    .createQuery(),
                record -> mapper.apply(convertToTimeSeriesData(record))
            )
            .contextWrite(ctx -> ctx.put(Logger.class, log));
    }

    static final String timestampAlias = "_ts";
    private static final String CAGG_THING_ID_COLUMN = ThingsDataConstants.COLUMN_THING_ID;

    /** cagg thing_id 过滤的允许列名（Java camelCase 和 DB snake_case 均接受） */
    private static final Set<String> THING_ID_COLUMNS = new HashSet<>(Arrays.asList(
        ThingsDataConstants.COLUMN_THING_ID, "thing_id"
    ));

    @Override
    protected Flux<AggregationData> doAggregation(String metric,
                                                  AggregationRequest request,
                                                  AggregationContext context) {
        metric = TimescaleDBUtils.getTableName(metric);

        // 满足条件时路由到最优 cagg 视图层（月 > 日 > 小时），避免全扫原始表
        if (properties.getCagg().isEnabled()
                && request.getInterval() != null
                && isCaggInterval(request.getInterval())
                && allSupportedByCagg(context.getProperties())
                && isSafeForCagg(request.getFilter())) {
            return doAggregationFromCagg(selectCaggView(metric, request.getInterval()), request, context);
        }

        QueryParamEntity filter = request.getFilter();
        filter.setSorts(new ArrayList<>());
        filter.setPaging(false);
        QueryOperator query = database.dml().query(metric);

        SelectColumn propertyColumn = SelectColumn.of(ThingsDataConstants.COLUMN_PROPERTY_ID);

        query.groupBy(propertyColumn);

        //按时间分组
        if (request.getInterval() != null) {
            NativeSelectColumn column = TimescaleDBUtils.createTimeGroupColumn(
                request.getFrom().getTime(),
                request.getInterval()
            );

            query.groupBy(column);
            query.select(column);
            column.setAlias(timestampAlias);
        }

        query.select(propertyColumn);

        Set<String> propertyId = new HashSet<>();

        if (context.getProperties().length > 1) {
            for (PropertyAggregation property : context.getProperties()) {
                NativeSelectColumn column = new NativeSelectColumn(
                    "case when property = ? then " + createAggFunction(property.getAgg()) +
                        " end as \"" + property.getAlias() + "\"");
                column.setParameters(new Object[]{property.getProperty()});
                query.select(column);
                propertyId.add(property.getProperty());
            }
        } else {
            PropertyAggregation property = context.getProperties()[0];
            String sql = createAggFunction(property.getAgg()) + " as \"" + property.getAlias() + "\"";
            query.select(NativeSelectColumn.of(sql));
            propertyId.add(property.getProperty());

        }


        query.where(cdt -> {
            cdt.each(filter.getTerms(), Conditional::accept);
            cdt.between(ThingsDataConstants.COLUMN_TIMESTAMP, request.getFrom(), request.getTo());
            if (!propertyId.isEmpty()) {
                cdt.in(ThingsDataConstants.COLUMN_PROPERTY_ID, propertyId);
            }
        });

        NavigableMap<Long, Map<String, Object>>
            prepares = ThingsDataUtils.prepareAggregationData(request, context.getProperties());

        return query
            .fetch(ResultWrappers.map())
            .reactive()
            .map(val -> Maps.filterValues(val, Objects::nonNull))
            .map(AggregationData::of)
            .groupBy(data -> data.getLong(timestampAlias).orElse(0L), Integer.MAX_VALUE)
            .flatMap(group -> {
                long time = group.key();
                Map<String, Object> prepare = ThingsDataUtils.findAggregationData(time, prepares);
                if (prepare == null) {
                    return Mono.empty();
                }
                return group
                    .doOnNext(data -> {
                        for (PropertyAggregation property : context.getProperties()) {
                            String alias = property.getAlias();
                            data.get(alias)
                                .ifPresent(val -> prepare.put(alias, val));
                        }
                    });
            })
            .thenMany(Flux.fromIterable(prepares.values()))
            .map(AggregationData::of)
            .take((long) request.getLimit() * propertyId.size())
            .contextWrite(ctx -> ctx.put(Logger.class, log));
    }


    private TimeSeriesData convertToTimeSeriesData(Record record) {
        return TimeSeriesData.of(
            record.get(ThingsDataConstants.COLUMN_TIMESTAMP)
                  .map(val -> CastUtils.castNumber(val).longValue())
                  .orElseGet(System::currentTimeMillis),
            record
        );
    }


    protected String createAggFunction(Aggregation aggregation) {
        switch (aggregation) {
            case COUNT:
                return "count(1)";
            case DISTINCT_COUNT:
                return "count(distinct \"numberValue\")";
            case AVG:
                return "avg(\"numberValue\")";
            case MAX:
                return "max(\"numberValue\")";
            case MIN:
                return "min(\"numberValue\")";
            case SUM:
                return "sum(\"numberValue\")";
//            case STDDEV:
//                return "stddev(\"numberValue\")";
        }
        throw new UnsupportedOperationException("不支持的聚合函数:" + aggregation);
    }

    // ── cagg 路由辅助 ─────────────────────────────────────────────────────────

    /** 请求间隔 >= 1 小时才走 cagg */
    private boolean isCaggInterval(org.jetlinks.community.Interval interval) {
        return interval.toMillis() >= 3_600_000L;
    }

    private static final long DAY_MS   = 24L * 3_600_000L;
    private static final long MONTH_MS = 30L * DAY_MS; // 用于粗筛，月对齐由 isMonthAligned() 精确判断

    /**
     * 根据请求间隔选择最优 cagg 视图层：
     * <ul>
     *   <li>interval 为整月（MONTHS/YEARS 单位）且 daily+monthly 均已启用 → _monthly_agg</li>
     *   <li>interval 为整天（DAY_MS 的整数倍）且 dailyEnabled → _daily_agg</li>
     *   <li>其他（>= 1 小时）→ _hourly_agg</li>
     * </ul>
     * P1-1：月级路由必须同时要求 dailyEnabled，与 DDL 建视图条件保持一致。
     * P1-2：非整天对齐的间隔（如 25h、36h）不能路由到日级视图，否则 bucket 精度丢失。
     */
    private String selectCaggView(String metric, org.jetlinks.community.Interval interval) {
        long ms = interval.toMillis();
        TimescaleDBThingsDataProperties.Cagg cagg = properties.getCagg();
        // 月级：要求 daily+monthly 均已启用，且间隔为整月（避免变长月份导致精度问题）
        if (cagg.isDailyEnabled() && cagg.isMonthlyEnabled()
                && ms >= MONTH_MS && isMonthAligned(interval)) {
            return metric + "_monthly_agg";
        }
        // 日级：要求 dailyEnabled 且间隔为 DAY_MS 的整数倍
        if (cagg.isDailyEnabled() && ms >= DAY_MS && ms % DAY_MS == 0) {
            return metric + "_daily_agg";
        }
        return metric + "_hourly_agg";
    }

    /** 月对齐：仅当 Interval 单位本身为 MONTHS 或 YEARS 时才路由到月级 cagg */
    private boolean isMonthAligned(org.jetlinks.community.Interval interval) {
        String unit = interval.getUnit().name().toUpperCase();
        return "MONTHS".equals(unit) || "YEARS".equals(unit);
    }

    /** cagg 支持的聚合类型：avg/max/min/first/last/top */
    private boolean allSupportedByCagg(PropertyAggregation[] props) {
        for (PropertyAggregation p : props) {
            switch (p.getAgg()) {
                case AVG:
                case MAX:
                case MIN:
                case FIRST:
                case LAST:
                case TOP:
                    break;
                default:
                    return false; // SUM/COUNT/DISTINCT_COUNT 回退原始表
            }
        }
        return true;
    }

    /**
     * 将聚合类型映射到 cagg 视图中的重聚合表达式。
     * <ul>
     *   <li>AVG  → sum(sum_value)/nullif(sum(sample_count),0)（跨多个小时桶精确加权平均）</li>
     *   <li>MAX  → max(max_value)</li>
     *   <li>MIN  → min(min_value)</li>
     *   <li>FIRST/TOP → first(first_value,bucket_start)（TimescaleDB 原生函数）</li>
     *   <li>LAST → last(last_value,bucket_start)</li>
     * </ul>
     */
    private String caggAggExpr(Aggregation agg) {
        switch (agg) {
            case AVG:
                return "sum(sum_value)/nullif(sum(sample_count),0)";
            case MAX:
                return "max(max_value)";
            case MIN:
                return "min(min_value)";
            case FIRST:
            case TOP:
                return "first(first_value,bucket_start)";
            case LAST:
                return "last(last_value,bucket_start)";
            default:
                throw new UnsupportedOperationException("cagg 不支持:" + agg);
        }
    }

    private String caggIntervalStr(org.jetlinks.community.Interval i) {
        return i.getNumber().intValue() + " " + i.getUnit().name().toLowerCase();
    }

    /**
     * 检查 filter 是否仅包含 cagg 能安全映射的条件：
     * <ul>
     *   <li>只允许 thing_id / thingId 列</li>
     *   <li>只允许 eq 或 in 操作符</li>
     *   <li>不允许嵌套子条件（对 cagg 视图无法安全翻译）</li>
     * </ul>
     * 其他任何列或复杂条件（如 numberValue 过滤）均回退原始表。
     */
    private boolean isSafeForCagg(QueryParamEntity filter) {
        List<Term> terms = filter.getTerms();
        if (terms == null || terms.isEmpty()) {
            return true;
        }
        for (Term term : terms) {
            // 有嵌套子条件，直接回退
            if (term.getTerms() != null && !term.getTerms().isEmpty()) {
                return false;
            }
            if (!THING_ID_COLUMNS.contains(term.getColumn())) {
                return false;
            }
            String type = term.getTermType();
            if (!eq.equals(type) && !in.equals(type)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将 filter 中的 thing_id / thingId 条件应用到 cagg 视图查询。
     * 将列名统一映射为 cagg 视图列名 {@code thing_id}，
     * 并正确处理 eq（单值）和 in（多值）两种操作符。
     */
    @SuppressWarnings("unchecked")
    private void applyThingIdTermsToCagg(Conditional<?> cdt, List<Term> terms) {
        if (terms == null) {
            return;
        }
        for (Term term : terms) {
            if (!THING_ID_COLUMNS.contains(term.getColumn())) {
                continue;
            }
            Object value = term.getValue();
            if (value == null) {
                continue;
            }
            if (in.equals(term.getTermType())) {
                // in 条件：value 可能是 Collection 或 Array，直接传递给 cdt.in
                if (value instanceof Collection) {
                    cdt.in(CAGG_THING_ID_COLUMN, (Collection<?>) value);
                } else {
                    // 其他类型（如逗号字符串），让 ezorm 按原始方式处理
                    cdt.in(CAGG_THING_ID_COLUMN, value);
                }
            } else {
                cdt.is(CAGG_THING_ID_COLUMN, value.toString());
            }
        }
    }

    /**
     * 基于 cagg 视图执行聚合查询。
     * 时间列为 bucket_start，通过 time_bucket 对多小时桶进行二次聚合。
     */
    private Flux<AggregationData> doAggregationFromCagg(String caggView,
                                                         AggregationRequest request,
                                                         AggregationContext context) {
        QueryOperator query = database.dml().query(caggView);

        SelectColumn propertyCol = SelectColumn.of("property");
        query.groupBy(propertyCol);

        // 时间分桶（基于 bucket_start 二次聚合）
        String unit = caggIntervalStr(request.getInterval());
        NativeSelectColumn timeCol = NativeSelectColumn.of(
            "time_bucket('" + unit + "',bucket_start)");
        timeCol.setAlias(timestampAlias);
        query.groupBy(timeCol);
        query.select(timeCol);
        query.select(propertyCol);

        Set<String> propertyIds = new HashSet<>();
        PropertyAggregation[] aggs = context.getProperties();
        if (aggs.length > 1) {
            for (PropertyAggregation p : aggs) {
                NativeSelectColumn col = new NativeSelectColumn(
                    "case when property = ? then " + caggAggExpr(p.getAgg()) +
                    " end as \"" + p.getAlias() + "\"");
                col.setParameters(new Object[]{p.getProperty()});
                query.select(col);
                propertyIds.add(p.getProperty());
            }
        } else {
            PropertyAggregation p = aggs[0];
            query.select(NativeSelectColumn.of(
                caggAggExpr(p.getAgg()) + " as \"" + p.getAlias() + "\""));
            propertyIds.add(p.getProperty());
        }

        // WHERE：time_bucket 范围 + property 过滤 + thing_id（正确映射 eq/in 两种形式）
        List<Term> filterTerms = request.getFilter().getTerms();
        query.where(cdt -> {
            applyThingIdTermsToCagg(cdt, filterTerms);
            cdt.between("bucket_start", request.getFrom(), request.getTo());
            if (!propertyIds.isEmpty()) {
                cdt.in("property", propertyIds);
            }
        });

        NavigableMap<Long, Map<String, Object>> prepares =
            ThingsDataUtils.prepareAggregationData(request, context.getProperties());

        return query
            .fetch(ResultWrappers.map())
            .reactive()
            .map(val -> Maps.filterValues(val, Objects::nonNull))
            .map(AggregationData::of)
            .groupBy(data -> data.getLong(timestampAlias).orElse(0L), Integer.MAX_VALUE)
            .flatMap(group -> {
                long time = group.key();
                Map<String, Object> prepare = ThingsDataUtils.findAggregationData(time, prepares);
                if (prepare == null) {
                    return Mono.empty();
                }
                return group
                    .doOnNext(data -> {
                        for (PropertyAggregation p : context.getProperties()) {
                            String alias = p.getAlias();
                            data.get(alias).ifPresent(val -> prepare.put(alias, val));
                        }
                    });
            })
            .thenMany(Flux.fromIterable(prepares.values()))
            .map(AggregationData::of)
            .take((long) request.getLimit() * propertyIds.size())
            .contextWrite(ctx -> ctx.put(Logger.class, log));
    }
}
