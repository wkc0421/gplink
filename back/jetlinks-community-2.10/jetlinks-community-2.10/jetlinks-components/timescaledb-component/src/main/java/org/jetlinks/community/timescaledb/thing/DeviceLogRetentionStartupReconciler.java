package org.jetlinks.community.timescaledb.thing;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.rdb.executor.reactive.ReactiveSqlExecutor;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.jetlinks.community.timescaledb.TimescaleDBUtils;
import org.jetlinks.community.timescaledb.timeseries.TimescaleDBRetentionPolicyManager;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

/** Applies the independent log retention policy to dormant as well as newly registered tables. */
@Slf4j
public final class DeviceLogRetentionStartupReconciler {
    private final DatabaseOperator database;
    private final TimescaleDBThingsDataProperties properties;
    private final String logPrefix;
    private final String logAllInOne;

    public DeviceLogRetentionStartupReconciler(DatabaseOperator database,
                                               TimescaleDBThingsDataProperties properties,
                                               String logPrefix, String logAllInOne) {
        this.database = database;
        this.properties = properties;
        this.logPrefix = logPrefix;
        this.logAllInOne = logAllInOne;
    }

    public void init() {
        Long checked = reconcile().block(Duration.ofMinutes(5));
        log.info("TimescaleDB device log retention reconciled for {} hypertables", checked);
    }

    Mono<Long> reconcile() {
        if (properties.getLogRetentionPolicy() == null) {
            return Mono.just(0L);
        }
        if (!StringUtils.hasText(logPrefix)) {
            return Mono.error(new IllegalArgumentException("Device log table prefix is empty"));
        }
        String schema = database.getMetadata().getCurrentSchema().getName();
        ReactiveSqlExecutor executor = database.getMetadata().getFeatureNow(ReactiveSqlExecutor.ID);
        String sql = "SELECT h.hypertable_name AS log_table FROM timescaledb_information.hypertables h"
            + " WHERE h.hypertable_schema=?"
            + " AND (SELECT count(*) FROM information_schema.columns c"
            + " WHERE c.table_schema=h.hypertable_schema AND c.table_name=h.hypertable_name"
            + " AND c.column_name IN ('timestamp','type','content'))=3"
            + " ORDER BY h.hypertable_name";
        return executor.select(sql, schema)
            .map(row -> Objects.toString(row.get("log_table"), ""))
            .filter(table -> matches(table, logPrefix, logAllInOne))
            .concatMap(table -> ensure(database, properties, table).thenReturn(table))
            .count();
    }

    static boolean matches(String table, String prefix, String allInOne) {
        return StringUtils.hasText(prefix) && StringUtils.hasText(table)
            && (table.startsWith(TimescaleDBUtils.getTableName(prefix))
                || (StringUtils.hasText(allInOne)
                    && table.equals(TimescaleDBUtils.getTableName(allInOne))));
    }

    static Mono<Void> ensure(DatabaseOperator database, TimescaleDBThingsDataProperties properties,
                             String table) {
        if (properties.getLogRetentionPolicy() == null) {
            return Mono.empty();
        }
        return new TimescaleDBRetentionPolicyManager(
            database.getMetadata().getCurrentSchema().getName(),
            database.getMetadata().getFeatureNow(ReactiveSqlExecutor.ID))
            .ensure(table, properties.getLogRetentionPolicy());
    }
}
