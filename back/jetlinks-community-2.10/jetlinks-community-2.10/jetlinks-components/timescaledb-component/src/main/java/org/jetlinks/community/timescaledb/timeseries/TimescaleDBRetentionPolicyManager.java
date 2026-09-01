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
package org.jetlinks.community.timescaledb.timeseries;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.reactive.ReactiveSqlExecutor;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers;
import org.jetlinks.community.Interval;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Map;

@Slf4j
final class TimescaleDBRetentionPolicyManager {

    static final String SCHEDULE_INTERVAL = "1 day";

    private static final String FIND_POLICY_SQL =
        "SELECT job_id," +
            " scheduled," +
            " schedule_interval = INTERVAL '" + SCHEDULE_INTERVAL + "' AS schedule_matches," +
            " (config->>'drop_after')::interval = ?::interval AS retention_matches " +
            "FROM timescaledb_information.jobs " +
            "WHERE proc_name = 'policy_retention' " +
            "AND hypertable_schema = ? " +
            "AND hypertable_name = ? " +
            "LIMIT 1";

    private final String schema;
    private final ReactiveSqlExecutor executor;

    TimescaleDBRetentionPolicyManager(String schema, ReactiveSqlExecutor executor) {
        this.schema = schema;
        this.executor = executor;
    }

    Mono<Void> ensure(String tableName, Interval retention) {
        if (retention == null || retention.getNumber().signum() <= 0) {
            return Mono.empty();
        }

        String retentionInterval = toSqlInterval(retention);
        return find(tableName, retentionInterval)
            .flatMap(job -> {
                if (job.matches()) {
                    log.info("TimescaleDB retention policy unchanged: table=[{}.{}], jobId=[{}], retention=[{}], schedule=[{}]",
                             schema, tableName, job.jobId, retentionInterval, SCHEDULE_INTERVAL);
                    return Mono.just(job);
                }
                return updateAndVerify(tableName, job.jobId, retentionInterval);
            })
            .switchIfEmpty(Mono.defer(() -> createAndReconcile(tableName, retentionInterval)))
            .then()
            .doOnError(error -> log.error(
                "Ensure TimescaleDB retention policy failed: table=[{}.{}], retention=[{}]",
                schema,
                tableName,
                retentionInterval,
                error));
    }

    private Mono<RetentionPolicyJob> find(String tableName, String retentionInterval) {
        return select(FIND_POLICY_SQL, retentionInterval, schema, tableName)
            .next()
            .map(row -> new RetentionPolicyJob(
                ((Number) row.get("job_id")).intValue(),
                Boolean.TRUE.equals(row.get("scheduled")),
                Boolean.TRUE.equals(row.get("schedule_matches")),
                Boolean.TRUE.equals(row.get("retention_matches"))
            ));
    }

    private Mono<RetentionPolicyJob> createAndReconcile(String tableName, String retentionInterval) {
        return create(tableName, retentionInterval)
            .then(find(tableName, retentionInterval)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                    "TimescaleDB retention policy is missing after create: " + schema + "." + tableName)))
                .flatMap(job -> {
                    if (!job.matches()) {
                        return updateAndVerify(tableName, job.jobId, retentionInterval);
                    }
                    log.info(
                        "TimescaleDB retention policy created: table=[{}.{}], jobId=[{}], retention=[{}], schedule=[{}]",
                        schema,
                        tableName,
                        job.jobId,
                        retentionInterval,
                        SCHEDULE_INTERVAL);
                    return Mono.just(job);
                }));
    }

    private Mono<RetentionPolicyJob> updateAndVerify(String tableName,
                                                     int jobId,
                                                     String retentionInterval) {
        return update(tableName, jobId, retentionInterval)
            .then(verify(tableName, retentionInterval))
            .doOnNext(job -> log.info(
                "TimescaleDB retention policy updated: table=[{}.{}], jobId=[{}], retention=[{}], schedule=[{}]",
                schema,
                tableName,
                job.jobId,
                retentionInterval,
                SCHEDULE_INTERVAL));
    }

    private Mono<RetentionPolicyJob> verify(String tableName, String retentionInterval) {
        return find(tableName, retentionInterval)
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "TimescaleDB retention policy is missing after coordination: " + schema + "." + tableName)))
            .flatMap(job -> job.matches()
                ? Mono.just(job)
                : Mono.error(new IllegalStateException(
                    "TimescaleDB retention policy does not match expected configuration after coordination: "
                        + schema + "." + tableName)));
    }

    private Mono<Void> create(String tableName, String retentionInterval) {
        String createSql =
            "SELECT " + quoteIdentifier(schema) + ".add_retention_policy(" +
                "?::regclass," +
                " drop_after => ?::interval," +
                " schedule_interval => INTERVAL '" + SCHEDULE_INTERVAL + "'," +
                " if_not_exists => TRUE) AS job_id";

        return execute(createSql, qualifiedTable(tableName), retentionInterval);
    }

    private Mono<Void> update(String tableName, int jobId, String retentionInterval) {
        String updateSql =
            "SELECT " + quoteIdentifier(schema) + ".alter_job(" +
                "job_id," +
                " schedule_interval => INTERVAL '" + SCHEDULE_INTERVAL + "'," +
                " scheduled => TRUE," +
                " config => jsonb_set(config, '{drop_after}', to_jsonb(?::text))) " +
                "FROM timescaledb_information.jobs " +
                "WHERE job_id = ?";

        return execute(updateSql, retentionInterval, jobId);
    }

    private Flux<Map<String, Object>> select(String sql, Object... parameters) {
        return executor.select(SqlRequests.of(sql, parameters), ResultWrappers.map());
    }

    private Mono<Void> execute(String sql, Object... parameters) {
        // The shared Spring executor marks select(...) as read-only. TimescaleDB
        // job functions mutate catalog state, so route them through execute(...),
        // which uses an independent writable transaction.
        return executor.execute(Mono.just(SqlRequests.of(sql, parameters)));
    }

    private String qualifiedTable(String tableName) {
        return quoteIdentifier(schema) + "." + quoteIdentifier(tableName);
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    static String toSqlInterval(Interval interval) {
        return interval.getNumber().stripTrailingZeros().toPlainString()
            + " "
            + interval.getUnit().name().toLowerCase(Locale.ROOT);
    }

    private static final class RetentionPolicyJob {
        private final int jobId;
        private final boolean scheduled;
        private final boolean scheduleMatches;
        private final boolean retentionMatches;

        private RetentionPolicyJob(int jobId,
                                   boolean scheduled,
                                   boolean scheduleMatches,
                                   boolean retentionMatches) {
            this.jobId = jobId;
            this.scheduled = scheduled;
            this.scheduleMatches = scheduleMatches;
            this.retentionMatches = retentionMatches;
        }

        private boolean matches() {
            return scheduled && scheduleMatches && retentionMatches;
        }
    }
}
