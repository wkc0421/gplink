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
package org.jetlinks.community.timescaledb.metadata;

import org.hswebframework.ezorm.rdb.executor.DefaultBatchSqlRequest;
import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.ddl.CommonCreateTableSqlBuilder;
import org.jetlinks.community.things.data.ThingsDataConstants;

public class TimescaleDBCreateTableSqlBuilder extends CommonCreateTableSqlBuilder {

    private String schema;

    public TimescaleDBCreateTableSqlBuilder(String schema) {
        this.schema = schema;
    }

    @Override
    public SqlRequest build(RDBTableMetadata table) {
        DefaultBatchSqlRequest sqlRequest = (DefaultBatchSqlRequest) super.build(table);

        table.getFeature(CreateHypertable.ID)
             .ifPresent(createHypertable -> sqlRequest.addBatch(createCreateHypertableSQL(table, createHypertable)));

        table.getFeature(CreateRetentionPolicy.ID)
             .ifPresent(feature -> sqlRequest.addBatch(createCreateRetentionPolicySQL(table, feature)));

        table.getFeature(CreateCompressionPolicy.ID)
             .ifPresent(policy -> {
                 sqlRequest.addBatch(createAlterCompressionSQL(table, policy));
                 sqlRequest.addBatch(createAddCompressionPolicySQL(table, policy));
             });

        table.getFeature(CreateContinuousAggregate.ID)
             .ifPresent(cagg -> {
                 // 小时级 cagg
                 sqlRequest.addBatch(createCaggViewSQL(table, cagg));
                 sqlRequest.addBatch(createCaggMaterializedOnlySQL(hourlyCaggName(table)));
                 sqlRequest.addBatch(createCaggRefreshPolicySQL(table, cagg));
                 sqlRequest.addBatch(createCaggRetentionPolicySQL(hourlyCaggName(table), cagg.getRetention()));
                 // 日级层级 cagg（在小时 cagg 之上）
                 if (cagg.isDailyEnabled()) {
                     sqlRequest.addBatch(createDailyCaggViewSQL(table, cagg));
                     sqlRequest.addBatch(createCaggMaterializedOnlySQL(dailyCaggName(table)));
                     sqlRequest.addBatch(createDailyCaggRefreshPolicySQL(table, cagg));
                     sqlRequest.addBatch(createCaggRetentionPolicySQL(dailyCaggName(table), cagg.getRetention()));
                 }
                 // 月级层级 cagg（在日级 cagg 之上，需要 dailyEnabled）
                 if (cagg.isDailyEnabled() && cagg.isMonthlyEnabled()) {
                     sqlRequest.addBatch(createMonthlyCaggViewSQL(table, cagg));
                     sqlRequest.addBatch(createCaggMaterializedOnlySQL(monthlyCaggName(table)));
                     sqlRequest.addBatch(createMonthlyCaggRefreshPolicySQL(table));
                     sqlRequest.addBatch(createCaggRetentionPolicySQL(monthlyCaggName(table), cagg.getRetention()));
                 }
             });

        return sqlRequest;
    }


    private SqlRequest createCreateRetentionPolicySQL(RDBTableMetadata table, CreateRetentionPolicy createHypertable) {

        String interval = createHypertable.getInterval().getNumber().intValue() + " "
            + createHypertable.getInterval().getUnit().name().toLowerCase();

        return SqlRequests.of(
            "SELECT "+ schema +".add_retention_policy( ? , INTERVAL '" + interval + "')",
            table.getFullName()
        );
    }

    private SqlRequest createAlterCompressionSQL(RDBTableMetadata table, CreateCompressionPolicy policy) {
        return SqlRequests.of(
            "ALTER TABLE " + table.getFullName()
                + " SET (timescaledb.compress"
                + ", timescaledb.compress_segmentby = '" + policy.getSegmentBy() + "'"
                + ", timescaledb.compress_orderby = '" + policy.getOrderBy() + "')"
        );
    }

    private SqlRequest createAddCompressionPolicySQL(RDBTableMetadata table, CreateCompressionPolicy policy) {
        String interval = policy.getCompressAfter().getNumber().intValue() + " "
            + policy.getCompressAfter().getUnit().name().toLowerCase();
        return SqlRequests.of(
            "SELECT " + schema + ".add_compression_policy( ? , INTERVAL '" + interval + "')",
            table.getFullName()
        );
    }

    private SqlRequest createCreateHypertableSQL(RDBTableMetadata table, CreateHypertable createHypertable) {

        String interval = createHypertable.getChunkTimeInterval().getNumber().intValue() + " "
            + createHypertable.getChunkTimeInterval().getUnit().name().toLowerCase();

        return SqlRequests.of(
            "SELECT "+ schema +".create_hypertable( ? , ? , chunk_time_interval => INTERVAL '" + interval + "')",
            table.getFullName(),
            table.getColumnNow(createHypertable.getColumn()).getName()
        );
    }

    // ── continuous aggregate helpers ─────────────────────────────────────────

    private String hourlyCaggName(RDBTableMetadata table) {
        return table.getFullName() + "_hourly_agg";
    }

    private String dailyCaggName(RDBTableMetadata table) {
        return table.getFullName() + "_daily_agg";
    }

    private String monthlyCaggName(RDBTableMetadata table) {
        return table.getFullName() + "_monthly_agg";
    }

    private String intervalStr(org.jetlinks.community.Interval i) {
        return i.getNumber().intValue() + " " + i.getUnit().name().toLowerCase();
    }

    private String quotedPropertyColumn() {
        return "\"" + ThingsDataConstants.COLUMN_PROPERTY_ID + "\"";
    }

    /** 将实际列名加双引号，用于 SQL 标识符引用 */
    private String quoted(String columnName) {
        return "\"" + columnName + "\"";
    }

    /** 小时级 cagg：直接在原始表上聚合，物化 first/last/avg/sum/min/max/count */
    private SqlRequest createCaggViewSQL(RDBTableMetadata table, CreateContinuousAggregate cagg) {
        String view = hourlyCaggName(table);
        String raw  = table.getFullName();
        String qThingId = quoted(cagg.getThingIdColumn());
        return SqlRequests.of(
            "CREATE MATERIALIZED VIEW " + view + " WITH (timescaledb.continuous) AS " +
            "SELECT time_bucket('1 hour',\"timestamp\") AS bucket_start," +
            qThingId + "," + quotedPropertyColumn() + "," +
            "first(\"numberValue\",\"timestamp\") AS first_value," +
            "last(\"numberValue\",\"timestamp\")  AS last_value," +
            "avg(\"numberValue\")                 AS avg_value," +
            "sum(\"numberValue\")                 AS sum_value," +
            "min(\"numberValue\")                 AS min_value," +
            "max(\"numberValue\")                 AS max_value," +
            "count(*)                             AS sample_count " +
            "FROM " + raw + " " +
            "GROUP BY 1," + qThingId + "," + quotedPropertyColumn() + " WITH NO DATA"
        );
    }

    /**
     * 日级层级 cagg：在 _hourly_agg 之上聚合。
     * avg_value 不单独存储，查询时由 sum_value/sample_count 派生。
     */
    private SqlRequest createDailyCaggViewSQL(RDBTableMetadata table, CreateContinuousAggregate cagg) {
        String view   = dailyCaggName(table);
        String hourly = hourlyCaggName(table);
        String qThingId = quoted(cagg.getThingIdColumn());
        return SqlRequests.of(
            "CREATE MATERIALIZED VIEW " + view + " WITH (timescaledb.continuous) AS " +
            "SELECT time_bucket('1 day',bucket_start) AS bucket_start," +
            qThingId + "," + quotedPropertyColumn() + "," +
            "first(first_value,bucket_start) AS first_value," +
            "last(last_value,bucket_start)   AS last_value," +
            "sum(sum_value)                  AS sum_value," +
            "min(min_value)                  AS min_value," +
            "max(max_value)                  AS max_value," +
            "sum(sample_count)               AS sample_count " +
            "FROM " + hourly + " " +
            "GROUP BY 1," + qThingId + "," + quotedPropertyColumn() + " WITH NO DATA"
        );
    }

    /**
     * 月级层级 cagg：在 _daily_agg 之上聚合。
     */
    private SqlRequest createMonthlyCaggViewSQL(RDBTableMetadata table, CreateContinuousAggregate cagg) {
        String view  = monthlyCaggName(table);
        String daily = dailyCaggName(table);
        String qThingId = quoted(cagg.getThingIdColumn());
        return SqlRequests.of(
            "CREATE MATERIALIZED VIEW " + view + " WITH (timescaledb.continuous) AS " +
            "SELECT time_bucket('1 month',bucket_start) AS bucket_start," +
            qThingId + "," + quotedPropertyColumn() + "," +
            "first(first_value,bucket_start) AS first_value," +
            "last(last_value,bucket_start)   AS last_value," +
            "sum(sum_value)                  AS sum_value," +
            "min(min_value)                  AS min_value," +
            "max(max_value)                  AS max_value," +
            "sum(sample_count)               AS sample_count " +
            "FROM " + daily + " " +
            "GROUP BY 1," + qThingId + "," + quotedPropertyColumn() + " WITH NO DATA"
        );
    }

    private SqlRequest createCaggMaterializedOnlySQL(String viewName) {
        return SqlRequests.of(
            "ALTER MATERIALIZED VIEW " + viewName +
            " SET (timescaledb.materialized_only = false)"
        );
    }

    /** 小时 cagg 刷新策略（使用 cagg 中配置的 offset 和 schedule） */
    private SqlRequest createCaggRefreshPolicySQL(RDBTableMetadata table, CreateContinuousAggregate cagg) {
        return SqlRequests.of(
            "SELECT " + schema + ".add_continuous_aggregate_policy(?," +
            "start_offset => INTERVAL '" + intervalStr(cagg.getStartOffset()) + "'," +
            "end_offset   => INTERVAL '" + intervalStr(cagg.getEndOffset()) + "'," +
            "schedule_interval => INTERVAL '" + intervalStr(cagg.getRefreshInterval()) + "')",
            hourlyCaggName(table)
        );
    }

    /** 日级 cagg 刷新策略：start_offset 由配置决定（默认 3d），end_offset=1d，schedule=1d。
     *  TimescaleDB 要求 (start_offset - end_offset) > schedule_interval(1d)，
     *  3d - 1d = 2d > 1d 满足约束，且 end_offset=1d 跳过当天不完整的 bucket。 */
    private SqlRequest createDailyCaggRefreshPolicySQL(RDBTableMetadata table, CreateContinuousAggregate cagg) {
        return SqlRequests.of(
            "SELECT " + schema + ".add_continuous_aggregate_policy(?," +
            "start_offset => INTERVAL '" + intervalStr(cagg.getDailyStartOffset()) + "'," +
            "end_offset   => INTERVAL '1 days'," +
            "schedule_interval => INTERVAL '1 days')",
            dailyCaggName(table)
        );
    }

    /** 月级 cagg 刷新策略：start_offset=3 months, end_offset=1 month, schedule=1 day */
    private SqlRequest createMonthlyCaggRefreshPolicySQL(RDBTableMetadata table) {
        return SqlRequests.of(
            "SELECT " + schema + ".add_continuous_aggregate_policy(?," +
            "start_offset => INTERVAL '3 months'," +
            "end_offset   => INTERVAL '1 months'," +
            "schedule_interval => INTERVAL '1 days')",
            monthlyCaggName(table)
        );
    }

    private SqlRequest createCaggRetentionPolicySQL(String viewName, org.jetlinks.community.Interval retention) {
        return SqlRequests.of(
            "SELECT " + schema + ".add_retention_policy(?,INTERVAL '" + intervalStr(retention) + "')",
            viewName
        );
    }
}
