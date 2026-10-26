package gp.saas.legacy.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gp.saas.legacy.dto.DeiceDataRequestEntity;
import gp.saas.legacy.dto.DeviceIntervalDataEntity;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.community.things.data.PropertyBoundaryValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;

class LegacyDeviceIntervalQueryTest {

    @ParameterizedTest
    @CsvSource({
        "week,2023-12-31T23:59:59,2024-01-01T00:00:00,2023-12-31T23:59:59;2024-01-01T00:00:00",
        "week,2024-01-01T00:00:00,2024-01-15T00:00:00,2024-01-01T00:00:00;2024-01-08T00:00:00;2024-01-15T00:00:00",
        "week,2024-02-29T12:30:00,2024-03-05T18:45:00,2024-02-29T12:30:00;2024-03-04T00:00:00;2024-03-05T18:45:00",
        "week,2024-12-29T12:00:00,2025-01-02T00:00:00,2024-12-29T12:00:00;2024-12-30T00:00:00;2025-01-02T00:00:00",
        "week,2024-01-03T08:00:00,2024-01-05T18:00:00,2024-01-03T08:00:00;2024-01-05T18:00:00",
        "quarter,2024-01-01T00:00:00,2025-01-01T00:00:00,2024-01-01T00:00:00;2024-04-01T00:00:00;2024-07-01T00:00:00;2024-10-01T00:00:00;2025-01-01T00:00:00",
        "quarter,2024-02-29T12:30:00,2024-07-03T18:45:00,2024-02-29T12:30:00;2024-04-01T00:00:00;2024-07-01T00:00:00;2024-07-03T18:45:00",
        "quarter,2024-12-31T23:59:59,2025-01-01T00:00:00,2024-12-31T23:59:59;2025-01-01T00:00:00",
        "quarter,2024-02-01T00:00:30,2024-03-31T23:59:40,2024-02-01T00:00:30;2024-03-31T23:59:40"
    })
    void naturalCalendarHistoryPreservesPartialBoundsInEveryHostTimezone(
        String type, String from, String to, String expected) {
        List<Long> boundaries = Arrays.stream(expected.split(";")).map(this::timestamp).toList();
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : List.of("UTC", "Asia/Shanghai", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                DeviceDataService service = mock(DeviceDataService.class);
                LegacyDeviceDataController controller = new LegacyDeviceDataController(service, null, null);
                stubEmptyBoundaries(service);
                List<DeviceIntervalDataEntity> result = controller.queryDeviceIntervalHistory(
                    type, timestamp(from), timestamp(to), request()).single().block();
                assertThat(result).extracting(DeviceIntervalDataEntity::getStartTime)
                    .containsExactlyElementsOf(boundaries.subList(0, boundaries.size() - 1));
                assertThat(result).extracting(DeviceIntervalDataEntity::getEndTime)
                    .containsExactlyElementsOf(boundaries.subList(1, boundaries.size()));
                verify(service).queryPropertyAtBoundaries("d1", "p1", timestamp(from), timestamp(to), boundaries);
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"week", "quarter"})
    void naturalCalendarHistoryStopsAtNowAndReturnsNoFutureOnlyRows(String type) {
        DeviceDataService service = mock(DeviceDataService.class);
        LegacyDeviceDataController controller = new LegacyDeviceDataController(service, null, null);
        stubEmptyBoundaries(service);
        long before = System.currentTimeMillis();
        long start = before - 10 * 86_400_000L;
        long future = before + 100 * 86_400_000L;
        List<DeviceIntervalDataEntity> rows = controller.queryDeviceIntervalHistory(type, start, future, request())
            .single().block();
        assertThat(rows.get(0).getStartTime()).isEqualTo(start);
        assertThat(rows.get(rows.size() - 1).getEndTime()).isBetween(before, System.currentTimeMillis());
        DeviceDataService futureService = mock(DeviceDataService.class);
        assertThat(new LegacyDeviceDataController(futureService, null, null)
            .queryDeviceIntervalHistory(type, future, future + 86_400_000L, request()).single().block()).isEmpty();
        verifyNoInteractions(futureService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"week", "quarter"})
    void naturalCalendarHistoryRetainsBucketAndCellLimits(String type) {
        DeviceDataService service = mock(DeviceDataService.class);
        LegacyDeviceDataController controller = new LegacyDeviceDataController(service, null, null);
        LocalDateTime start = LocalDateTime.of(2024, 1, 1, 0, 0);
        LocalDateTime end = type.equals("week") ? start.plusWeeks(1000) : start.plusMonths(3000);
        List<Long> boundaries = ReflectionTestUtils.invokeMethod(controller, "intervalBoundaries", type,
            timestamp(start.toString()), timestamp(end.toString()));
        assertThat(boundaries).hasSize(1001);
        // The public endpoint checks the final segment count after appending the end boundary.
        assertThatThrownBy(() -> controller.queryDeviceIntervalHistory(type,
            timestamp(type.equals("week") ? "2000-01-03T00:00:00" : "1700-01-01T00:00:00"),
            timestamp("2024-01-01T00:00:00"), request()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1000");
        DeiceDataRequestEntity many = request();
        many.setDevices(java.util.stream.IntStream.range(0, 500).mapToObj(i -> "d" + i).toList());
        assertThatThrownBy(() -> controller.queryDeviceIntervalHistory(type,
            timestamp(type.equals("week") ? "2010-01-01T00:00:00" : "1900-01-01T00:00:00"),
            timestamp("2024-01-01T00:00:00"), many)).hasMessageContaining("100000");
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"week", "quarter"})
    void naturalCalendarHistoryUsesLongCacheExceptForHotWindows(String type) {
        LegacyDeviceDataController controller = new LegacyDeviceDataController(null, null, null);
        Object cold = ReflectionTestUtils.invokeMethod(controller, "intervalHistoryCache", type,
            timestamp("2024-01-01T00:00:00"));
        Object hot = ReflectionTestUtils.invokeMethod(controller, "intervalHistoryCache", type, System.currentTimeMillis());
        assertThat(cold).isSameAs(ReflectionTestUtils.getField(controller, "longIntervalHistoryCache"));
        assertThat(hot).isSameAs(ReflectionTestUtils.getField(controller, "shortIntervalHistoryCache"));
    }

    @Test
    void historyCarriesInitialValueAcrossEmptyBoundaries() {
        DeviceDataService service = mock(DeviceDataService.class);
        LegacyDeviceDataController controller = new LegacyDeviceDataController(service, null, null);
        long start = 0L;
        long middle = 5L * 60 * 1000;
        long end = 10L * 60 * 1000;
        List<Long> boundaries = Arrays.asList(start, middle, end);
        when(service.queryPropertyAtBoundaries("d1", "p1", start, end, boundaries))
            .thenReturn(Mono.just(new PropertyBoundaryValues(
                "p1",
                start,
                end,
                10,
                Arrays.asList(
                    new PropertyBoundaryValues.BoundaryValue(start, 10),
                    new PropertyBoundaryValues.BoundaryValue(middle, null),
                    new PropertyBoundaryValues.BoundaryValue(end, 20)))));

        List<DeviceIntervalDataEntity> result = controller
            .queryDeviceIntervalHistory("5m", start, end, request())
            .single()
            .block();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getStartValue()).isEqualTo(10);
        assertThat(result.get(0).getEndValue()).isEqualTo(10);
        assertThat(result.get(1).getStartValue()).isEqualTo(10);
        assertThat(result.get(1).getEndValue()).isEqualTo(20);
        verify(service).queryPropertyAtBoundaries("d1", "p1", start, end, boundaries);
    }

    @Test
    void historyCarriesValuesThroughMissingOuterEndForLegacyClient() {
        DeviceDataService service = mock(DeviceDataService.class);
        LegacyDeviceDataController controller = new LegacyDeviceDataController(service, null, null);
        long step = 5L * 60 * 1000;
        List<Long> boundaries = Arrays.asList(0L, step, 2 * step, 3 * step);
        when(service.queryPropertyAtBoundaries("d1", "p1", 0L, 3 * step, boundaries))
            .thenReturn(Mono.just(new PropertyBoundaryValues(
                "p1",
                0L,
                3 * step,
                10,
                Arrays.asList(
                    new PropertyBoundaryValues.BoundaryValue(0L, 10),
                    new PropertyBoundaryValues.BoundaryValue(step, 20),
                    new PropertyBoundaryValues.BoundaryValue(2 * step, null),
                    new PropertyBoundaryValues.BoundaryValue(3 * step, null)))));

        List<DeviceIntervalDataEntity> result = controller
            .queryDeviceIntervalHistory("5m", 0L, 3 * step, request())
            .single()
            .block();

        assertThat(result).extracting(DeviceIntervalDataEntity::getStartValue)
            .containsExactly(10, 20, 20);
        assertThat(result).extracting(DeviceIntervalDataEntity::getEndValue)
            .containsExactly(20, 20, 20);
    }

    @Test
    void currentPartialHourRetainsItsStartAndLatestReadingForLegacyClient() {
        DeviceDataService service = mock(DeviceDataService.class);
        LegacyDeviceDataController controller = new LegacyDeviceDataController(service, null, null);
        long middle = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        long start = middle - 3_600_000L;
        long end = middle + 3_600_000L;
        long before = System.currentTimeMillis();
        when(service.queryPropertyAtBoundaries(eq("d1"), eq("p1"), eq(start), anyLong(), anyList()))
            .thenAnswer(call -> {
                long actualEnd = call.getArgument(3);
                return Mono.just(new PropertyBoundaryValues("p1", start, actualEnd, 541,
                    List.of(new PropertyBoundaryValues.BoundaryValue(start, 541),
                        new PropertyBoundaryValues.BoundaryValue(middle, 554.4),
                        new PropertyBoundaryValues.BoundaryValue(actualEnd, 560))));
            });

        List<DeviceIntervalDataEntity> result = controller
            .queryDeviceIntervalHistory("day", start, end, request()).single().block();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getStartValue()).isEqualTo(541);
        assertThat(result.get(0).getEndValue()).isEqualTo(554.4);
        assertThat(result.get(1).getStartValue()).isEqualTo(554.4);
        assertThat(result.get(1).getEndValue()).isEqualTo(560);
        assertThat(result.get(1).getEndTime()).isBetween(before, System.currentTimeMillis());
        assertThat(result.get(1).getEndTime()).isLessThan(end);
        assertThat(((Number) result.get(1).getStartValue()).doubleValue()
            - ((Number) result.get(0).getStartValue()).doubleValue()).isCloseTo(13.4,
                offset(0.000001));
    }

    @Test
    void rawDailyHistoryPreservesReportedConsumptionWhenFinalBoundaryIsMissing() throws Exception {
        DeviceDataService service = mock(DeviceDataService.class);
        LegacyDeviceDataController controller = new LegacyDeviceDataController(service, null, null);
        long start = timestamp("2024-01-20T00:00:00");
        long next = timestamp("2024-01-21T00:00:00");
        long today = timestamp("2024-01-22T00:00:00");
        long end = timestamp("2024-01-23T00:00:00");
        List<Long> boundaries = List.of(start, next, today, end);
        when(service.queryPropertyAtBoundaries("d1", "p1", start, end, boundaries))
            .thenReturn(Mono.just(new PropertyBoundaryValues("p1", start, end, 1718469.63,
                List.of(new PropertyBoundaryValues.BoundaryValue(start, 1718469.63),
                    new PropertyBoundaryValues.BoundaryValue(next, 1718469.63),
                    new PropertyBoundaryValues.BoundaryValue(today, 1783475.97),
                    new PropertyBoundaryValues.BoundaryValue(end, null)))));

        List<DeviceIntervalDataEntity> result = controller
            .queryDeviceIntervalHistory("month", start, end, request()).single().block();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(result));
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.get(2).hasNonNull("startValue")).isTrue();
        BigDecimal delta = json.get(2).get("startValue").decimalValue()
            .subtract(json.get(1).get("startValue").decimalValue());
        assertThat(delta).isEqualByComparingTo("65006.34");
        assertThat(json.get(2).get("endValue").decimalValue())
            .isEqualByComparingTo(json.get(2).get("startValue").decimalValue());
    }

    @Test
    void futureOnlyHistoryReturnsEmptyDeviceTimelineWithoutReadingData() {
        DeviceDataService service = mock(DeviceDataService.class);
        LegacyDeviceDataController controller = new LegacyDeviceDataController(service, null, null);
        long start = System.currentTimeMillis() + 2 * 86_400_000L;

        List<DeviceIntervalDataEntity> result = controller
            .queryDeviceIntervalHistory("month", start, start + 86_400_000L, request()).single().block();

        assertThat(result).isEmpty();
        verifyNoInteractions(service);
    }

    @Test
    void allEmptyRangeKeepsEveryBucketValueNull() {
        DeviceDataService service = mock(DeviceDataService.class);
        LegacyDeviceDataController controller = new LegacyDeviceDataController(service, null, null);
        long step = 5L * 60 * 1000;
        List<Long> boundaries = Arrays.asList(0L, step, 2 * step);
        when(service.queryPropertyAtBoundaries("d1", "p1", 0L, 2 * step, boundaries))
            .thenReturn(Mono.just(new PropertyBoundaryValues(
                "p1",
                0L,
                2 * step,
                null,
                boundaries.stream()
                    .map(boundary -> new PropertyBoundaryValues.BoundaryValue(boundary, null))
                    .collect(Collectors.toList()))));

        List<DeviceIntervalDataEntity> result = controller
            .queryDeviceIntervalHistory("5m", 0L, 2 * step, request())
            .single()
            .block();

        assertThat(result).hasSize(2).allSatisfy(bucket -> {
            assertThat(bucket.getStartValue()).isNull();
            assertThat(bucket.getEndValue()).isNull();
        });
    }

    @Test
    void exactMinuteHourDayAndMonthBoundariesArePreserved() {
        List<BoundaryScenario> scenarios = Arrays.asList(
            scenario("5m", "2024-01-01T10:00:00", "2024-01-01T10:10:00",
                "2024-01-01T10:00:00", "2024-01-01T10:05:00", "2024-01-01T10:10:00"),
            scenario("day", "2024-01-01T10:00:00", "2024-01-01T12:00:00",
                "2024-01-01T10:00:00", "2024-01-01T11:00:00", "2024-01-01T12:00:00"),
            scenario("month", "2024-01-01T00:00:00", "2024-01-03T00:00:00",
                "2024-01-01T00:00:00", "2024-01-02T00:00:00", "2024-01-03T00:00:00"),
            scenario("year", "2024-01-01T00:00:00", "2024-03-01T00:00:00",
                "2024-01-01T00:00:00", "2024-02-01T00:00:00", "2024-03-01T00:00:00"));

        for (BoundaryScenario scenario : scenarios) {
            DeviceDataService service = mock(DeviceDataService.class);
            LegacyDeviceDataController controller = new LegacyDeviceDataController(service, null, null);
            List<PropertyBoundaryValues.BoundaryValue> values = scenario.boundaries().stream()
                .map(boundary -> new PropertyBoundaryValues.BoundaryValue(boundary, null))
                .collect(Collectors.toList());
            when(service.queryPropertyAtBoundaries(
                "d1", "p1", scenario.start(), scenario.end(), scenario.boundaries()))
                .thenReturn(Mono.just(new PropertyBoundaryValues(
                    "p1", scenario.start(), scenario.end(), null, values)));

            List<DeviceIntervalDataEntity> result = controller
                .queryDeviceIntervalHistory(
                    scenario.type(), scenario.start(), scenario.end(), request())
                .single()
                .block();

            assertThat(result).extracting(DeviceIntervalDataEntity::getStartTime)
                .containsExactlyElementsOf(scenario.boundaries().subList(0, scenario.boundaries().size() - 1));
            assertThat(result).extracting(DeviceIntervalDataEntity::getEndTime)
                .containsExactlyElementsOf(scenario.boundaries().subList(1, scenario.boundaries().size()));
            verify(service).queryPropertyAtBoundaries(
                "d1", "p1", scenario.start(), scenario.end(), scenario.boundaries());
        }
    }

    @Test
    void hourlyHistoryRoundsOutwardInShanghaiAndKeepsAlignedEndpoints() {
        DeviceDataService service = mock(DeviceDataService.class);
        LegacyDeviceDataController controller = new LegacyDeviceDataController(service, null, null);
        stubEmptyBoundaries(service);
        long start = timestamp("2024-01-02T08:30:00");
        long end = timestamp("2024-01-02T23:30:00");

        List<DeviceIntervalDataEntity> result = controller
            .queryDeviceIntervalHistory("day", start, end, request()).single().block();

        assertThat(result).hasSize(16);
        assertThat(result.get(0).getStartTime()).isEqualTo(timestamp("2024-01-02T08:00:00"));
        assertThat(result.get(15).getEndTime()).isEqualTo(timestamp("2024-01-03T00:00:00"));
        verify(service).queryPropertyAtBoundaries(eq("d1"), eq("p1"),
            eq(timestamp("2024-01-02T08:00:00")),
            eq(timestamp("2024-01-03T00:00:00")), anyList());
    }

    @Test
    void dailyHistoryRoundsOutwardAcrossLeapDay() {
        DeviceDataService service = mock(DeviceDataService.class);
        LegacyDeviceDataController controller = new LegacyDeviceDataController(service, null, null);
        stubEmptyBoundaries(service);

        List<DeviceIntervalDataEntity> result = controller
            .queryDeviceIntervalHistory("month",
                timestamp("2024-02-28T12:00:00"),
                timestamp("2024-03-02T23:59:59"), request())
            .single().block();

        assertThat(result).extracting(DeviceIntervalDataEntity::getStartTime)
            .containsExactly(
                timestamp("2024-02-28T00:00:00"),
                timestamp("2024-02-29T00:00:00"),
                timestamp("2024-03-01T00:00:00"),
                timestamp("2024-03-02T00:00:00"));
        assertThat(result.get(3).getEndTime()).isEqualTo(timestamp("2024-03-03T00:00:00"));
    }

    @Test
    void monthlyHistoryOnlySnapsEndpointsWithinOneMinute() {
        DeviceDataService service = mock(DeviceDataService.class);
        LegacyDeviceDataController controller = new LegacyDeviceDataController(service, null, null);
        stubEmptyBoundaries(service);

        List<DeviceIntervalDataEntity> result = controller.queryDeviceIntervalHistory(
            "year", timestamp("2024-01-01T00:00:30"),
            timestamp("2024-03-01T00:00:20"), request()).single().block();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getStartTime()).isEqualTo(timestamp("2024-01-01T00:00:00"));
        assertThat(result.get(1).getEndTime()).isEqualTo(timestamp("2024-03-01T00:00:00"));
    }

    @Test
    void shanghaiNormalizationDoesNotDependOnJvmDefaultTimeZone() {
        DeviceDataService service = mock(DeviceDataService.class);
        LegacyDeviceDataController controller = new LegacyDeviceDataController(service, null, null);
        stubEmptyBoundaries(service);
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            List<DeviceIntervalDataEntity> utc = controller
                .queryDeviceIntervalHistory("month",
                    timestamp("2024-02-29T11:00:00"),
                    timestamp("2024-03-01T11:00:00"), request()).single().block();
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            List<DeviceIntervalDataEntity> pacific = controller
                .queryDeviceIntervalHistory("month",
                    timestamp("2024-02-29T11:00:00"),
                    timestamp("2024-03-01T11:00:00"), request()).single().block();

            assertThat(utc).extracting(DeviceIntervalDataEntity::getStartTime)
                .containsExactly(timestamp("2024-02-29T00:00:00"), timestamp("2024-03-01T00:00:00"));
            assertThat(pacific).extracting(DeviceIntervalDataEntity::getStartTime)
                .containsExactly(timestamp("2024-02-29T00:00:00"), timestamp("2024-03-01T00:00:00"));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private void stubEmptyBoundaries(DeviceDataService service) {
        when(service.queryPropertyAtBoundaries(eq("d1"), eq("p1"),
            anyLong(), anyLong(), anyList()))
            .thenAnswer(invocation -> {
                long start = invocation.getArgument(2);
                long end = invocation.getArgument(3);
                List<Long> boundaries = invocation.getArgument(4);
                return Mono.just(new PropertyBoundaryValues(
                    "p1", start, end, null,
                    boundaries.stream()
                        .map(boundary -> new PropertyBoundaryValues.BoundaryValue(boundary, null))
                        .collect(Collectors.toList())));
            });
    }

    private DeiceDataRequestEntity request() {
        DeiceDataRequestEntity request = new DeiceDataRequestEntity();
        request.setDevices(Collections.singletonList("d1"));
        request.setProperties(Collections.singletonList("p1"));
        return request;
    }

    private BoundaryScenario scenario(String type,
                                      String start,
                                      String end,
                                      String... boundaries) {
        List<Long> timestamps = new ArrayList<>(boundaries.length);
        for (String boundary : boundaries) {
            timestamps.add(timestamp(boundary));
        }
        return new BoundaryScenario(type, timestamp(start), timestamp(end), timestamps);
    }

    private long timestamp(String value) {
        return LocalDateTime
            .parse(value)
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli();
    }

    private record BoundaryScenario(String type, long start, long end, List<Long> boundaries) {
    }
}
