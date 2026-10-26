package org.jetlinks.community.timescaledb.thing;

import org.hswebframework.ezorm.core.ValueCodec;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.NativeSql;

import java.time.*;
import java.util.Date;
import java.util.Collection;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.jetlinks.community.utils.DateMathParser;

/** TIMESTAMP WITHOUT TIME ZONE stores Shanghai wall time; the API exposes epoch millis. */
public final class ShanghaiTimestampCodec implements ValueCodec<Object, Object> {
    @Override
    public Object encode(Object value) {
        if (value == null || value instanceof NativeSql) {
            return value;
        }
        if (value instanceof Collection) {
            return ((Collection<?>) value).stream().map(this::encode).collect(Collectors.toList());
        }
        if (value instanceof Object[]) {
            return Arrays.stream((Object[]) value).map(this::encode).collect(Collectors.toList());
        }
        if (value instanceof String && ((String) value).contains(",")) {
            return Arrays.stream(((String) value).split(",")).map(this::encode).collect(Collectors.toList());
        }
        return local(value);
    }

    @Override
    public Object decode(Object value) {
        if (value == null) {
            return null;
        }
        return local(value).atZone(AggregationWindow.ZONE).toInstant().toEpochMilli();
    }

    public static LocalDateTime local(Object value) {
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        // A JDBC Timestamp represents the wall time in this column, not an instant.
        if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toLocalDateTime();
        }
        if (value instanceof Date) {
            return AggregationWindow.local(((Date) value).getTime());
        }
        if (value instanceof Number) {
            return AggregationWindow.local(((Number) value).longValue());
        }
        if (value instanceof Instant) {
            return LocalDateTime.ofInstant((Instant) value, AggregationWindow.ZONE);
        }
        if (value instanceof OffsetDateTime) {
            return ((OffsetDateTime) value).atZoneSameInstant(AggregationWindow.ZONE).toLocalDateTime();
        }
        return AggregationWindow.local(DateMathParser.parse(String.valueOf(value).trim(),
            System::currentTimeMillis, AggregationWindow.ZONE));
    }
}
