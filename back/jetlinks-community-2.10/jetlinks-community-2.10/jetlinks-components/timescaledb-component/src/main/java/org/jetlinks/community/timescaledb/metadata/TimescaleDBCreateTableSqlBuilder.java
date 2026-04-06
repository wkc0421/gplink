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
                 sqlRequest.addBatch(createCaggViewSQL(table, cagg));
                 sqlRequest.addBatch(createCaggMaterializedOnlySQL(table));
                 sqlRequest.addBatch(createCaggRefreshPolicySQL(table, cagg));
                 sqlRequest.addBatch(createCaggRetentionPolicySQL(table, cagg));
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

    private String caggViewName(RDBTableMetadata table) {
        return table.getFullName() + "_hourly_agg";
    }

    private String intervalStr(org.jetlinks.community.Interval i) {
        return i.getNumber().intValue() + " " + i.getUnit().name().toLowerCase();
    }

    private SqlRequest createCaggViewSQL(RDBTableMetadata table, CreateContinuousAggregate cagg) {
        String view = caggViewName(table);
        String raw  = table.getFullName();
        return SqlRequests.of(
            "CREATE MATERIALIZED VIEW " + view + " WITH (timescaledb.continuous) AS " +
            "SELECT time_bucket('1 hour',\"timestamp\") AS bucket_start," +
            "\"thing_id\",\"property\"," +
            "first(\"numberValue\",\"timestamp\") AS first_value," +
            "last(\"numberValue\",\"timestamp\")  AS last_value," +
            "avg(\"numberValue\")                 AS avg_value," +
            "sum(\"numberValue\")                 AS sum_value," +
            "min(\"numberValue\")                 AS min_value," +
            "max(\"numberValue\")                 AS max_value," +
            "count(*)                             AS sample_count " +
            "FROM " + raw + " " +
            "GROUP BY 1,\"thing_id\",\"property\" WITH NO DATA"
        );
    }

    private SqlRequest createCaggMaterializedOnlySQL(RDBTableMetadata table) {
        return SqlRequests.of(
            "ALTER MATERIALIZED VIEW " + caggViewName(table) +
            " SET (timescaledb.materialized_only = false)"
        );
    }

    private SqlRequest createCaggRefreshPolicySQL(RDBTableMetadata table, CreateContinuousAggregate cagg) {
        return SqlRequests.of(
            "SELECT " + schema + ".add_continuous_aggregate_policy(?," +
            "start_offset => INTERVAL '" + intervalStr(cagg.getStartOffset()) + "'," +
            "end_offset   => INTERVAL '" + intervalStr(cagg.getEndOffset()) + "'," +
            "schedule_interval => INTERVAL '" + intervalStr(cagg.getRefreshInterval()) + "')",
            caggViewName(table)
        );
    }

    private SqlRequest createCaggRetentionPolicySQL(RDBTableMetadata table, CreateContinuousAggregate cagg) {
        return SqlRequests.of(
            "SELECT " + schema + ".add_retention_policy(?,INTERVAL '" + intervalStr(cagg.getRetention()) + "')",
            caggViewName(table)
        );
    }
}
