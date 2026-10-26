package gp.saas.legacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.ezorm.core.param.TermType;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.community.Interval;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.community.device.web.request.AggRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class LegacyAggregationTimezoneTest {
    @ParameterizedTest
    @ValueSource(strings = {"1w", "1q"})
    void calendarIntervalDeserializationPreservesPartialBeijingBounds(String interval) throws Exception {
        DeviceDataService.AggregationRequest query = new ObjectMapper().readValue(
            "{\"interval\":\"" + interval + "\",\"from\":\"2024-02-29 12:30:00\","
                + "\"to\":\"2024-07-03 18:45:00\",\"format\":\"yyyy-MM-dd HH:mm:ss\",\"limit\":20}",
            DeviceDataService.AggregationRequest.class);
        assertThat(query.getInterval().toString()).isEqualTo(interval);
        assertThat(query.getFrom().getTime()).isEqualTo(timestamp("2024-02-29T12:30:00"));
        assertThat(query.getTo().getTime()).isEqualTo(timestamp("2024-07-03T18:45:00"));
        assertThat(query.getLimit()).isEqualTo(20);
    }

    @Test void legacyCalendarAndResultParsingUseBeijingTime() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            LegacyDeviceDataController controller = new LegacyDeviceDataController(null,null,null);
            long start = timestamp("2024-02-29T23:00:00");
            long end = timestamp("2024-03-02T01:00:00");
            List<Long> boundaries = ReflectionTestUtils.invokeMethod(controller,"intervalBoundaries","month",start,end);
            assertThat(boundaries).containsExactly(start,timestamp("2024-03-01T00:00:00"),
                                                   timestamp("2024-03-02T00:00:00"),end);
            Long parsed = ReflectionTestUtils.invokeMethod(controller,"parseAggregationTime","2024-03-01 00:00:00");
            assertThat(parsed).isEqualTo(timestamp("2024-03-01T00:00:00"));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test void deviceRequestDatesAreExplicitZoneAndInvalidEnvelopeIs400() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            DeviceDataService.AggregationRequest query = new ObjectMapper().readValue(
                "{\"from\":\"2024-03-01 00:00:00\",\"to\":\"2024-03-02 00:00:00\"}",
                DeviceDataService.AggregationRequest.class);
            assertThat(query.getFrom().getTime()).isEqualTo(timestamp("2024-03-01T00:00:00"));
            assertThat(query.getTo().getTime()).isEqualTo(timestamp("2024-03-02T00:00:00"));
            assertThatThrownBy(() -> new AggRequest().validate())
                .isInstanceOf(BusinessException.class).extracting("status").isEqualTo(400);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test void longHistoryChoosesCalendarBucketsWithoutTruncatingAtOneThousandDays() {
        LegacyDeviceDataController controller = new LegacyDeviceDataController(null,null,null);
        long start = timestamp("2000-01-01T00:00:00");
        long fiveYears = timestamp("2005-01-01T00:00:00");
        long twoHundredYears = timestamp("2200-01-01T00:00:00");

        Interval monthly = ReflectionTestUtils.invokeMethod(controller,"chooseHistoryInterval",start,fiveYears);
        Interval yearly = ReflectionTestUtils.invokeMethod(controller,"chooseHistoryInterval",start,twoHundredYears);
        Integer monthlyLimit = ReflectionTestUtils.invokeMethod(controller,"computeBucketLimit",start,fiveYears,monthly);
        Integer yearlyLimit = ReflectionTestUtils.invokeMethod(controller,"computeBucketLimit",start,twoHundredYears,yearly);

        assertThat(monthly).isNotNull();
        assertThat(monthly.getExpression()).isEqualTo(Interval.month);
        assertThat(yearly).isNotNull();
        assertThat(yearly.getExpression()).isEqualTo(Interval.year);
        assertThat(monthlyLimit).isBetween(1,1000);
        assertThat(yearlyLimit).isBetween(1,1000);
    }

    @Test void strictTimeBoundsArePreservedAndRemovedFromAggregationFilter() {
        LegacyDeviceDataController controller = new LegacyDeviceDataController(null,null,null);
        long start = timestamp("2024-01-01T00:00:00");
        long end = timestamp("2024-02-01T00:00:00");
        QueryParamEntity query = QueryParamEntity.of();
        query.and("timestamp",TermType.gt,start);
        query.and("timestamp",TermType.lt,end);
        query.and("value",TermType.gt,10);

        Object range = ReflectionTestUtils.invokeMethod(controller,"extractTimeRangeMillis",query);
        QueryParamEntity filter = ReflectionTestUtils.invokeMethod(controller,"downsampleFilter",query);

        assertThat(String.valueOf((Object) ReflectionTestUtils.invokeMethod(range,"status"))).isEqualTo("VALID");
        assertThat((Long) ReflectionTestUtils.invokeMethod(range,"start")).isEqualTo(start + 1);
        assertThat((Long) ReflectionTestUtils.invokeMethod(range,"end")).isEqualTo(end - 1);
        assertThat(filter.getTerms()).singleElement().satisfies(term -> {
            assertThat(term.getColumn()).isEqualTo("value");
            assertThat(term.getValue()).isEqualTo(10);
        });
    }

    @Test void unsafeOrTimestampFilterIsRejectedInsteadOfSilentlyRunningRaw() {
        LegacyDeviceDataController controller = new LegacyDeviceDataController(null,null,null);
        QueryParamEntity query = QueryParamEntity.of();
        query.and("timestamp",TermType.gte,timestamp("2024-01-01T00:00:00"));
        query.and("timestamp",TermType.lte,timestamp("2024-02-01T00:00:00"));
        query.and("value",TermType.gt,10);
        query.getTerms().get(2).setType(org.hswebframework.ezorm.core.param.Term.Type.or);

        Object range = ReflectionTestUtils.invokeMethod(controller,"extractTimeRangeMillis",query);
        assertThat(String.valueOf((Object) ReflectionTestUtils.invokeMethod(range,"status"))).isEqualTo("UNSAFE");
    }

    @Test void incompleteTimestampRangeIsRejectedBeforeAnUnboundedRawQuery() {
        LegacyDeviceDataController controller = new LegacyDeviceDataController(null,null,null);
        QueryParamEntity query = QueryParamEntity.of();
        query.and("timestamp",TermType.gte,timestamp("2024-01-01T00:00:00"));

        Object range = ReflectionTestUtils.invokeMethod(controller,"extractTimeRangeMillis",query);
        assertThat(String.valueOf((Object) ReflectionTestUtils.invokeMethod(range,"status"))).isEqualTo("UNSAFE");
        assertThat(String.valueOf((Object) ReflectionTestUtils.invokeMethod(range,"reason")))
            .contains("both start and end bounds");
    }

    @Test void intervalSelectionUsesExactInclusiveBucketCountAtCap() {
        LegacyDeviceDataController controller = new LegacyDeviceDataController(null,null,null);
        long start = timestamp("2024-01-01T00:00:00");
        long exactlyOneThousandFiveMinuteBuckets = start + Duration.ofMinutes(5L * 999).toMillis();
        long oneThousandAndOneFiveMinuteBuckets = start + Duration.ofMinutes(5L * 1000).toMillis();

        Interval finest = ReflectionTestUtils.invokeMethod(
            controller,"chooseHistoryInterval",start,exactlyOneThousandFiveMinuteBuckets);
        Interval next = ReflectionTestUtils.invokeMethod(
            controller,"chooseHistoryInterval",start,oneThousandAndOneFiveMinuteBuckets);

        assertThat(finest.getExpression()).isEqualTo(Interval.ofMinutes(5).getExpression());
        assertThat(next.toString()).isEqualTo(Interval.ofMinutes(6).toString());
    }

    @Test void mediumRangeUsesTheFinestWholeHourInsteadOfJumpingToOneDay() {
        LegacyDeviceDataController controller = new LegacyDeviceDataController(null,null,null);
        long start = timestamp("2024-01-01T00:00:00");
        long ninetyDays = start + Duration.ofDays(90).toMillis();

        Interval interval = ReflectionTestUtils.invokeMethod(
            controller,"chooseHistoryInterval",start,ninetyDays);

        assertThat(interval.toString()).isEqualTo(Interval.ofHours(3).toString());
        Integer limit = ReflectionTestUtils.invokeMethod(
            controller,"computeBucketLimit",start,ninetyDays,interval);
        assertThat(limit).isBetween(1,1000);
    }

    @Test void invalidAggregationTimeIsNeverReplacedWithNow() {
        LegacyDeviceDataController controller = new LegacyDeviceDataController(null,null,null);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(controller,"parseAggregationTime","not-a-time"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid aggregation time");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(controller,"parseAggregationTime",new Object[]{null}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("required");
    }

    @Test void totalResponseCellLimitIsEnforced() {
        LegacyDeviceDataController controller = new LegacyDeviceDataController(null,null,null);
        assertThatCode(() -> ReflectionTestUtils.invokeMethod(controller,"validateResultCells",100,1000,1))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(controller,"validateResultCells",101,1000,1))
            .isInstanceOf(BusinessException.class)
            .extracting("status").isEqualTo(400);
    }

    private static long timestamp(String value) {
        return LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }
}
