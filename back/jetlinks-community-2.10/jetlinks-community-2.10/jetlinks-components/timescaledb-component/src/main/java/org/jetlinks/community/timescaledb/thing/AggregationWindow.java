package org.jetlinks.community.timescaledb.thing;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.community.Interval;
import org.jetlinks.community.IntervalUnit;
import org.jetlinks.community.things.data.AggregationRequest;
import org.jetlinks.community.things.data.PropertyAggregation;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import java.time.*;
import java.util.*;

/** Bounded, descending bucket grid shared by SQL and gap filling. */
final class AggregationWindow {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    final LinkedHashMap<Long, Map<String, Object>> rows = new LinkedHashMap<>();
    final Interval interval;
    final long from;
    final long toExclusive;

    private AggregationWindow(AggregationRequest request, PropertyAggregation[] columns,
                              TimescaleDBThingsDataProperties.AggregationLimits limits) {
        interval = request.getInterval();
        if (request.getFrom() == null || request.getTo() == null
            || request.getFrom().after(request.getTo())) {
            throw invalid("from/to 时间范围无效");
        }
        validateInterval(interval);
        int limit = request.getExplicitLimit() == null ? 30 : request.getExplicitLimit();
        if (limit < 1 || limit > limits.getMaxBuckets() || columns.length == 0
            || columns.length > limits.getMaxColumns()
            || (long) limit * columns.length > limits.getMaxCells()) {
            throw invalid("聚合桶数、列数或结果单元数超出限制");
        }
        DateTimeFormatter format = DateTimeFormat.forPattern(request.getFormat())
            .withZone(DateTimeZone.forID(ZONE.getId()));
        long start = request.getFrom().getTime();
        long end = request.getTo().getTime();
        if (local(start).getYear() < 1 || local(end).getYear() > 9999) {
            throw invalid("时间必须在公元 1 至 9999 年范围内");
        }
        toExclusive = Math.addExact(end, 1);
        if (interval == null) {
            rows.put(0L, defaults(columns));
            from = start;
        } else {
            long first = floor(start, interval);
            long cursor = floor(end, interval);
            if (local(first).getYear() < 1) {
                throw invalid("interval 的桶起点超出公元 1 至 9999 年范围");
            }
            for (int i = 0; i < limit && cursor >= first; i++) {
                Map<String, Object> row = defaults(columns);
                row.put("time", format.print(cursor));
                rows.put(cursor, row);
                if (cursor == first) {
                    break;
                }
                cursor = advance(cursor, interval, -1);
            }
            long oldest = rows.keySet().stream().reduce((a, b) -> b).orElse(first);
            from = Math.max(start, oldest);
        }
    }

    static AggregationWindow create(AggregationRequest request, PropertyAggregation[] columns,
                                    TimescaleDBThingsDataProperties.AggregationLimits limits) {
        try {
            return new AggregationWindow(request, columns, limits);
        } catch (IllegalArgumentException | ArithmeticException | DateTimeException e) {
            throw invalid("聚合时间参数无效: " + e.getMessage());
        }
    }

    static BusinessException invalid(String message) {
        return new BusinessException(message, 400);
    }

    static void validateInterval(Interval interval) {
        if (interval == null) {
            return;
        }
        int count = interval.getNumber().intValueExact();
        if (count <= 0 || interval.getUnit() == IntervalUnit.FOREVER) {
            throw invalid("interval 必须为正整数时间间隔");
        }
        interval.getUnit().toMillis(count); // reject overflow before any loop or SQL
        // PostgreSQL stores the months and days portions of INTERVAL as int32.
        if (interval.getUnit() == IntervalUnit.YEARS) {
            Math.multiplyExact(count, 12);
        } else if (interval.getUnit() == IntervalUnit.QUARTER) {
            Math.multiplyExact(count, 3);
        } else if (interval.getUnit() == IntervalUnit.WEEKS) {
            Math.multiplyExact(count, 7);
        }
    }

    static long floor(long timestamp, Interval interval) {
        return interval.getUnit().truncatedTo(timestamp, interval.getNumber().intValueExact(), ZONE);
    }

    static long advance(long timestamp, Interval interval, int direction) {
        return interval.getUnit().advance(timestamp,
            Math.multiplyExact(interval.getNumber().intValueExact(), direction), ZONE);
    }

    static LocalDateTime local(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZONE);
    }

    static String sqlInterval(Interval interval) {
        validateInterval(interval);
        if (interval.getUnit() == IntervalUnit.QUARTER) {
            return Math.multiplyExact(interval.getNumber().longValueExact(), 3) + " months";
        }
        return interval.getNumber().intValueExact() + " "
            + (interval.getUnit() == IntervalUnit.MILLIS ? "milliseconds"
               : interval.getUnit().name().toLowerCase(Locale.ROOT));
    }

    static boolean calendar(Interval interval) {
        return interval.getUnit() == IntervalUnit.MONTHS
            || interval.getUnit() == IntervalUnit.QUARTER
            || interval.getUnit() == IntervalUnit.YEARS;
    }

    static String bucketSql(Interval interval, String column, String functionSchema) {
        return interval == null ? "0::bigint" :
            ContinuousAggregateManager.function(functionSchema, "time_bucket")
                + "(INTERVAL '" + sqlInterval(interval) + "'," + column
                + ", TIMESTAMP '" + (calendar(interval) ? "2000-01-01" : "2000-01-03") + " 00:00:00')";
    }

    private static Map<String, Object> defaults(PropertyAggregation[] columns) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (PropertyAggregation column : columns) {
            values.put(column.getAlias(), column.getDefaultValue());
        }
        return values;
    }
}
