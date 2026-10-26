package org.jetlinks.community.timescaledb.thing;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.community.Interval;
import org.jetlinks.community.things.data.*;
import org.jetlinks.community.timeseries.query.Aggregation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.jetlinks.community.timescaledb.thing.CaggTestSupport.*;

class AggregationWindowTest {
    private final PropertyAggregation[] columns = {new PropertyAggregation("p", "值", Aggregation.AVG)};
    private final TimescaleDBThingsDataProperties.AggregationLimits limits =
        new TimescaleDBThingsDataProperties.AggregationLimits();

    @ParameterizedTest
    @CsvSource({
        "1w,2023-12-31 23:59:59.999,2023-12-25 00:00:00",
        "1w,2024-01-01 00:00:00,2024-01-01 00:00:00",
        "1w,2025-01-01 12:00:00,2024-12-30 00:00:00",
        "1w,2024-02-29 12:00:00,2024-02-26 00:00:00",
        "1q,2024-02-29 12:00:00,2024-01-01 00:00:00",
        "1q,2024-03-31 23:59:59.999,2024-01-01 00:00:00",
        "1q,2024-04-01 00:00:00,2024-04-01 00:00:00",
        "1q,2024-06-30 23:59:59.999,2024-04-01 00:00:00",
        "1q,2024-07-01 00:00:00,2024-07-01 00:00:00",
        "1q,2024-09-30 23:59:59.999,2024-07-01 00:00:00",
        "1q,2024-10-01 00:00:00,2024-10-01 00:00:00",
        "1q,2024-12-31 23:59:59.999,2024-10-01 00:00:00",
        "1q,2025-01-01 00:00:00,2025-01-01 00:00:00"
    })
    void naturalWeekAndQuarterBoundariesIgnoreHostTimezone(String interval, String instant, String expected) {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : Arrays.asList("UTC", "Asia/Shanghai", "America/New_York")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                AggregationWindow window = AggregationWindow.create(request(interval, instant, instant, 1), columns, limits);
                assertThat(window.rows.keySet()).containsExactly(time(expected));
                assertThat(window.rows.values().iterator().next().get("time")).isEqualTo(expected);
                assertThat(window.from).isEqualTo(time(instant));
                assertThat(window.toExclusive).isEqualTo(time(instant) + 1);
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test void weeklyGridIncludesNewYearBoundaryAndKeepsOnlyLatestBuckets() {
        AggregationWindow window = AggregationWindow.create(
            request("1w", "2023-12-20 12:00:00", "2024-01-01 00:00:00", 2), columns, limits);
        assertThat(window.rows.keySet()).containsExactly(time("2024-01-01 00:00:00"), time("2023-12-25 00:00:00"));
        assertThat(window.from).isEqualTo(time("2023-12-25 00:00:00"));
        assertThat(window.toExclusive).isEqualTo(time("2024-01-01 00:00:00") + 1);
        window.rows.values().forEach(row -> assertThat(row.get("值")).isEqualTo(columns[0].getDefaultValue()));
    }

    @Test void quarterlyGridPreservesPartialBoundsAndAdvancesAcrossAllSeasons() {
        AggregationWindow window = AggregationWindow.create(
            request("1q", "2024-02-29 12:00:00", "2025-01-01 00:00:00", 10), columns, limits);
        assertThat(window.rows.keySet()).containsExactly(time("2025-01-01 00:00:00"), time("2024-10-01 00:00:00"),
            time("2024-07-01 00:00:00"), time("2024-04-01 00:00:00"), time("2024-01-01 00:00:00"));
        assertThat(window.from).isEqualTo(time("2024-02-29 12:00:00"));
        assertThat(window.toExclusive).isEqualTo(time("2025-01-01 00:00:00") + 1);
        AggregationWindow limited = AggregationWindow.create(
            request("1q", "2024-02-29 12:00:00", "2025-01-01 00:00:00", 2), columns, limits);
        assertThat(limited.rows.keySet()).containsExactly(time("2025-01-01 00:00:00"), time("2024-10-01 00:00:00"));
        assertThat(limited.from).isEqualTo(time("2024-10-01 00:00:00"));
    }

    @Test void twoHourBucketsFloorInsteadOfShiftingForward() {
        AggregationWindow window = AggregationWindow.create(
            request("2h", "2026-09-01 00:00:00", "2026-09-01 06:00:00", 10), columns, limits);
        assertThat(window.rows.keySet()).containsExactly(time("2026-09-01 06:00:00"),
            time("2026-09-01 04:00:00"), time("2026-09-01 02:00:00"), time("2026-09-01 00:00:00"));
        assertThat(window.toExclusive).isEqualTo(time("2026-09-01 06:00:00") + 1);
    }

    @Test void narrowSqlBeforePreparingLargeRanges() {
        AggregationWindow window = AggregationWindow.create(
            request("1s", "2000-01-01 00:00:00", "2026-09-01 06:00:00", 2), columns, limits);
        assertThat(window.rows).hasSize(2);
        assertThat(window.from).isEqualTo(time("2026-09-01 05:59:59"));
    }

    @ParameterizedTest @ValueSource(strings={"0h","-1h","0.5h","2147483648h","2147483647y","200000000y","400000000w"})
    void rejectInvalidIntervals(String interval) {
        assertThatThrownBy(() -> AggregationWindow.create(
            request(interval, "2026-01-01 00:00:00", "2026-09-01 06:00:00", 10), columns, limits))
            .isInstanceOf(BusinessException.class).extracting("status").isEqualTo(400);
    }

    @Test void calendarBucketsAndCenturyQuery() {
        AggregationWindow window = AggregationWindow.create(
            request("1M", "2024-02-29 23:00:00", "2024-03-01 00:00:00", 10), columns, limits);
        assertThat(window.rows.keySet()).containsExactly(time("2024-03-01 00:00:00"), time("2024-02-01 00:00:00"));
        assertThat(AggregationWindow.create(
            request("100y", "2026-01-01 00:00:00", "2026-09-01 06:00:00", 1), columns, limits).rows)
            .hasSize(1).containsKey(time("2000-01-01 00:00:00"));
        assertThatThrownBy(() -> AggregationWindow.create(
            request("100y", "0001-01-01 00:00:00", "0001-01-02 00:00:00", 1), columns, limits))
            .isInstanceOf(BusinessException.class);
    }

    @Test void independentOfJvmTimezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : Arrays.asList("UTC", "Asia/Shanghai", "America/New_York")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                AggregationWindow window = AggregationWindow.create(
                    request("1d", "2026-09-01 01:00:00", "2026-09-01 23:00:00", 1), columns, limits);
                assertThat(window.rows.keySet()).containsExactly(time("2026-09-01 00:00:00"));
                assertThat(window.rows.values().iterator().next().get("time")).isEqualTo("2026-09-01 00:00:00");
                ShanghaiTimestampCodec codec = new ShanghaiTimestampCodec();
                assertThat(codec.encode(new Date(time("2026-09-01 00:00:00"))))
                    .isEqualTo(LocalDateTime.of(2026,9,1,0,0));
                assertThat(codec.decode(LocalDateTime.of(2026,9,1,0,0))).isEqualTo(time("2026-09-01 00:00:00"));
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test void guardRangeLimitsAndCommonIterator() {
        assertThatThrownBy(() -> AggregationWindow.create(
            request("1h", "2026-09-02 00:00:00", "2026-09-01 00:00:00", 10), columns, limits))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AggregationWindow.create(
            request("1h", "2026-09-01 00:00:00", "2026-09-02 00:00:00", 10001), columns, limits))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> Interval.of("0h").iterate(0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Interval.of("2h").iterate(0, 6*3600000L)).containsExactly(0L,7200000L,14400000L,21600000L);
    }

    @Test void requestDatesAndDateMathUseShanghaiRegardlessOfHost() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            AggregationRequest request = mapper.readValue(
                "{\"from\":\"2026-09-01 00:00:00\",\"to\":\"2026-09-02T00:00:00+08:00\"}",
                AggregationRequest.class);
            assertThat(request.getFrom().getTime()).isEqualTo(time("2026-09-01 00:00:00"));
            assertThat(request.getTo().getTime()).isEqualTo(time("2026-09-02 00:00:00"));
            assertThat(org.jetlinks.community.utils.DateMathParser.parse(
                "now/d", () -> time("2026-09-01 01:00:00"),AggregationWindow.ZONE))
                .isEqualTo(time("2026-09-01 00:00:00"));
            assertThat(new ShanghaiTimestampCodec().encode(Arrays.asList("2026-09-01","2026-09-02")))
                .isEqualTo(Arrays.asList(LocalDateTime.of(2026,9,1,0,0),LocalDateTime.of(2026,9,2,0,0)));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
