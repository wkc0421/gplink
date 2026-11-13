package org.jetlinks.community.timescaledb.thing;

import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.jetlinks.community.things.data.PropertyExtremes;
import org.jetlinks.community.things.data.operations.PropertyExtremesQuery;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.jetlinks.community.timescaledb.thing.ContinuousAggregateManager.quote;

/** One statement/snapshot for grouped extrema and their earliest timestamps. No DDL or CAGG dependency. */
final class TimescaleDBPropertyExtremesQuery implements PropertyExtremesQuery {
    private record StorageKey(Object database, String schema, String table, String thingColumn) { }

    private final StorageKey key;
    private final Function<SqlRequest, Flux<Map<String, Object>>> select;

    TimescaleDBPropertyExtremesQuery(Object database, String schema, String table, String thingColumn,
                                   Function<SqlRequest, Flux<Map<String, Object>>> select) {
        this.key = new StorageKey(database, schema, table, thingColumn);
        this.select = select;
    }

    @Override
    public Object storageKey() {
        return key;
    }

    @Override
    public Flux<PropertyExtremes> query(List<PropertyExtremes.Target> targets, long startTime, long endTime) {
        return Flux.defer(() -> {
            if (targets.isEmpty()) {
                return Flux.empty();
            }
            return select.apply(statement(targets, startTime, endTime)).map(row -> {
                PropertyExtremes.Target target = new PropertyExtremes.Target(
                    (String) row.get("thing_id"), (String) row.get("property"));
                if (row.get("min_value") == null) {
                    return PropertyExtremes.empty(target, PropertyExtremes.Status.NO_DATA);
                }
                return new PropertyExtremes(target.thingId(), target.property(), PropertyExtremes.Status.OK,
                    ((Number) row.get("min_value")).doubleValue(), timestamp(row.get("min_time")),
                    ((Number) row.get("max_value")).doubleValue(), timestamp(row.get("max_time")));
            });
        });
    }

    SqlRequest statement(List<PropertyExtremes.Target> targets, long startTime, long endTime) {
        if (targets.isEmpty() || targets.size() > MAX_BATCH_SIZE || endTime <= startTime) {
            throw new IllegalArgumentException("Invalid extremes batch or time range");
        }
        String table = quote(key.schema()) + "." + quote(key.table());
        String device = quote(key.thingColumn());
        String values = targets.stream().map(ignored -> "(?::varchar,?::varchar)")
            .collect(Collectors.joining(","));
        String sql = "WITH requested(thing_id,property) AS (VALUES " + values + "),"
            + "extremes AS (SELECT d." + device + " AS thing_id,d.\"property\","
            + "min(d.\"numberValue\") AS min_value,max(d.\"numberValue\") AS max_value"
            + " FROM " + table + " d JOIN requested r ON d." + device + "=r.thing_id"
            + " AND d.\"property\"=r.property"
            + " WHERE d.\"timestamp\">=?::timestamp AND d.\"timestamp\"<=?::timestamp"
            // PostgreSQL orders NaN above +Infinity, so these bounds exclude all non-finite values.
            + " AND d.\"numberValue\">'-Infinity'::double precision"
            + " AND d.\"numberValue\"<'Infinity'::double precision"
            + " GROUP BY d." + device + ",d.\"property\")"
            + " SELECT r.thing_id,r.property,e.min_value,e.max_value,"
            + "lo.\"timestamp\" AS min_time,hi.\"timestamp\" AS max_time"
            + " FROM requested r LEFT JOIN extremes e USING (thing_id,property)"
            + " LEFT JOIN LATERAL (SELECT d.\"timestamp\" FROM " + table + " d"
            + " WHERE d." + device + "=r.thing_id AND d.\"property\"=r.property"
            + " AND d.\"timestamp\">=?::timestamp AND d.\"timestamp\"<=?::timestamp"
            + " AND d.\"numberValue\"=e.min_value ORDER BY d.\"timestamp\" ASC LIMIT 1) lo ON TRUE"
            + " LEFT JOIN LATERAL (SELECT d.\"timestamp\" FROM " + table + " d"
            + " WHERE d." + device + "=r.thing_id AND d.\"property\"=r.property"
            + " AND d.\"timestamp\">=?::timestamp AND d.\"timestamp\"<=?::timestamp"
            + " AND d.\"numberValue\"=e.max_value ORDER BY d.\"timestamp\" ASC LIMIT 1) hi ON TRUE";
        List<Object> parameters = new ArrayList<>();
        for (PropertyExtremes.Target target : targets) {
            parameters.add(target.thingId());
            parameters.add(target.property());
        }
        for (int i = 0; i < 3; i++) {
            parameters.add(AggregationWindow.local(startTime));
            parameters.add(AggregationWindow.local(endTime));
        }
        return SqlRequests.of(sql, parameters.toArray());
    }

    private static Long timestamp(Object value) {
        if (value == null) {
            throw new IllegalStateException("Extreme sample timestamp missing");
        }
        if (value instanceof LocalDateTime time) {
            return time.atZone(AggregationWindow.ZONE).toInstant().toEpochMilli();
        }
        throw new IllegalStateException("Unexpected timestamp type: " + value.getClass().getName());
    }
}
