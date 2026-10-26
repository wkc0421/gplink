package gp.saas.legacy.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gp.saas.apitoken.service.ApiTokenScopeService;
import gp.saas.legacy.dto.DeiceDataRequestEntity;
import gp.saas.legacy.dto.DeviceIntervalDataEntity;
import gp.saas.legacy.service.LegacyDeviceLatestValueService;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceProperty;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.community.timescaledb.thing.counter.CounterReadContext;
import org.jetlinks.community.timescaledb.thing.counter.CounterSnapshotService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Golden cases from G:/gplink's DeviceDataControllerIntervalEnergyTest and ES fallback. */
class LegacyIntervalSummaryCompatibilityTest {
    @Test
    void rawStartIncludesExactSampleButEndExcludesExactSample() {
        Fixture f = new Fixture(false, 1000, 10, 2000, 20);
        assertValues(f.query(1000, 2000), "10", "10");
    }

    @Test
    void missingRawBaselineUsesFirstReadingStrictlyInsideRange() {
        Fixture f = new Fixture(false, 1500, 15, 1900, 19);
        assertValues(f.query(1000, 2000), "15", "19");
    }

    @Test
    void wideningStartBeforeDeviceFirstReportUsesFirstAvailableReading() {
        long firstReport = time("2026-10-20T08:00:00");
        long earlierStart = time("2026-10-16T00:00:00");
        long laterStart = time("2026-10-21T00:00:00");
        long end = time("2026-10-22T13:48:16");
        for (boolean enabled : List.of(false, true)) {
            Fixture f = new Fixture(enabled,
                firstReport, 1_600_000D,
                laterStart, 1_718_469.63D,
                time("2026-10-22T13:00:00"), 1_826_092.8D);
            if (enabled) {
                f.stored(earlierStart, end, null, "1826092.8", false);
                f.stored(laterStart, end, "1718469.63", "1826092.8", false);
            }

            DeviceIntervalDataEntity wider = f.query(earlierStart, end);
            DeviceIntervalDataEntity narrower = f.query(laterStart, end);

            assertValues(wider, "1600000", "1826092.8");
            assertValues(narrower, "1718469.63", "1826092.8");
            BigDecimal widerDelta = new BigDecimal(wider.getEndValue().toString())
                .subtract(new BigDecimal(wider.getStartValue().toString()));
            BigDecimal narrowerDelta = new BigDecimal(narrower.getEndValue().toString())
                .subtract(new BigDecimal(narrower.getStartValue().toString()));
            assertThat(widerDelta).isGreaterThan(narrowerDelta);
        }
    }

    @Test
    void emptyRawGapDoesNotBorrowTheReadingAfterEnd() {
        Fixture f = new Fixture(false, 900, 10, 2100, 20);
        assertValues(f.query(1000, 2000), "10", "10");
    }

    @Test
    void oldRawReadingRemainsUsableWhenNoLaterSampleEverArrived() {
        Fixture f = new Fixture(false, 900, 10);
        assertValues(f.query(1000, 2000), "10", "10");
    }

    @Test
    void samplesOnlyAtOrAfterEndAreNotPulledIntoRawRange() {
        for (long sample : List.of(2000L, 2100L)) {
            Fixture f = new Fixture(false, sample, 20);
            assertValues(f.query(1000, 2000), null, null);
        }
    }

    @Test
    void realZeroAndCounterResetArePreserved() {
        assertValues(new Fixture(false, 900, 0, 1500, 0).query(1000, 2000), "0", "0");
        assertValues(new Fixture(false, 900, 100, 1500, 2).query(1000, 2000), "100", "2");
    }

    @Test
    void completelyEmptyRawRangeDoesNotFabricateZero() {
        assertValues(new Fixture(false).query(1000, 2000), null, null);
    }

    @Test
    void nullRawReadingIsNotTreatedAsAnAbsentRow() {
        assertValues(new Fixture(false, 900, 10, 1500, 15, 1900, null).query(1000, 2000), "10", null);
        assertValues(new Fixture(false, 900, null, 1500, 15, 1900, 19).query(1000, 2000), null, "19");
    }

    @Test
    void unconfiguredRangeKeepsItsOriginalNearHourBoundaries() {
        Fixture f = new Fixture(false);
        long start = time("2024-01-01T08:00:45"), end = time("2024-01-01T10:59:30");
        DeviceIntervalDataEntity row = f.query(start, end);
        assertThat(row.getStartTime()).isEqualTo(start);
        assertThat(row.getEndTime()).isEqualTo(end);
        verify(f.counters, never()).summaryBoundaryValues(anyString(), anyString(), anyCollection(), anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void configuredRangeAlignsAtInclusiveOneMinuteTolerance() {
        long start = time("2024-01-01T08:00:00"), end = time("2024-01-01T10:00:00");
        for (long offset : List.of(-60_000L, 0L, 60_000L)) {
            Fixture f = new Fixture(true);
            f.stored(start, end, "10", "20", false);
            DeviceIntervalDataEntity row = f.query(start + offset, end + offset);
            assertValues(row, "10", "20");
            assertThat(row.getStartTime()).isEqualTo(start);
            assertThat(row.getEndTime()).isEqualTo(end);
            verifyNoInteractions(f.raw);
        }
    }

    @Test
    void configuredRangeOutsideToleranceRetainsRequestedTimes() {
        Fixture f = new Fixture(true);
        long start = time("2024-01-01T08:01:01"), end = time("2024-01-01T10:01:01");
        f.stored(start, end, "10", "20", false);
        DeviceIntervalDataEntity row = f.query(start, end);
        assertThat(row.getStartTime()).isEqualTo(start);
        assertThat(row.getEndTime()).isEqualTo(end);
        assertValues(row, "10", "20");
    }

    @Test
    void midnightRangeUsesDailyBoundaryPolicy() {
        Fixture f = new Fixture(true);
        long start = time("2024-01-01T00:00:00"), end = time("2024-01-03T00:00:00");
        f.stored(start, end, "100", "130", true);
        assertValues(f.query(start, end), "100", "130");
    }

    @Test
    void incompleteStoredPairFallsBackToBothRawReadingsRatherThanMixingSources() {
        Fixture f = new Fixture(true, 90000, 11, 190000, 19);
        f.stored(100000, 200000, "999", null, false);
        assertValues(f.query(100000, 200000), "11", "19");
    }

    @Test
    void futureEndUsesLatestRedisValueAndStopsAtNow() {
        Fixture f = new Fixture(true);
        long now = System.currentTimeMillis(), start = liveStart(now);
        when(f.counters.summaryBoundaryValues("p1", "E", List.of("d1"), start, false, false))
            .thenReturn(Mono.just(Map.of("d1", "10")));
        when(f.latest.readProperty("d1", "E"))
            .thenReturn(Mono.just(new LegacyDeviceLatestValueService.LatestValue(" 25 ", now - 1)));
        DeviceIntervalDataEntity row = f.query(start + 30_000L, now + 3_600_000L);
        assertValues(row, "10", "25");
        assertThat(row.getStartTime()).isEqualTo(start);
        assertThat(row.getEndTime()).isBetween(now, System.currentTimeMillis());
        verify(f.counters, times(1)).summaryBoundaryValues(anyString(), anyString(), anyCollection(), anyLong(), anyBoolean(), anyBoolean());
        verifyNoInteractions(f.raw);
    }

    @Test
    void invalidRedisReadingsReuseStoredStartAndFallBackToCompletedHourEnd() {
        long now = System.currentTimeMillis(), start = liveStart(now);
        List<LegacyDeviceLatestValueService.LatestValue> invalid = List.of(
            new LegacyDeviceLatestValueService.LatestValue(99, start),
            new LegacyDeviceLatestValueService.LatestValue(99, now + 86_400_000L),
            new LegacyDeviceLatestValueService.LatestValue("NaN", now - 1),
            new LegacyDeviceLatestValueService.LatestValue("Infinity", now - 1),
            new LegacyDeviceLatestValueService.LatestValue("bad", now - 1));
        for (LegacyDeviceLatestValueService.LatestValue value : invalid) {
            Fixture f = new Fixture(true);
            f.stored(start, hour(now), "10", "20", false);
            when(f.latest.readProperty("d1", "E")).thenReturn(Mono.just(value));
            assertValues(f.query(start, now + 3_600_000L), "10", "20");
            verifyNoInteractions(f.raw);
        }
    }

    @Test
    void redisFailureAndMissingStoredEndUseCompleteRawFallback() {
        long now = System.currentTimeMillis(), start = liveStart(now);
        Fixture f = new Fixture(true, start - 1, 11, now - 1, 19);
        f.stored(start, hour(now), "999", null, false);
        when(f.latest.readProperty("d1", "E")).thenReturn(Mono.error(new IllegalStateException("redis unavailable")));
        assertValues(f.query(start, now + 3_600_000L), "11", "19");
    }

    @Test
    void rawFutureEndAlsoStopsAtNow() {
        long now = System.currentTimeMillis();
        Fixture f = new Fixture(false, now - 10_000, 10, now - 1, 20);
        DeviceIntervalDataEntity row = f.query(now - 5000, now + 3_600_000);
        assertValues(row, "10", "20");
        assertThat(row.getEndTime()).isBetween(now, System.currentTimeMillis());
    }

    @Test
    void whollyFutureRangeReturnsMissingReadingsWithoutAccessingStorage() {
        Fixture f = new Fixture(true);
        long start = System.currentTimeMillis() + 86_400_000L;
        DeviceIntervalDataEntity row = f.query(start, start + 86_400_000L);
        assertValues(row, null, null);
        assertThat(row.getStartTime()).isEqualTo(start);
        assertThat(row.getEndTime()).isEqualTo(start);
        verifyNoInteractions(f.counters, f.raw, f.latest);
        verify(f.scope).requireDevice("d1", "p1");
    }

    @Test
    void storageFailureIsNotTurnedIntoRawFallback() {
        Fixture f = new Fixture(true);
        when(f.counters.summaryBoundaryValues(anyString(), anyString(), anyCollection(), anyLong(), anyBoolean(), anyBoolean()))
            .thenReturn(Mono.error(new IllegalStateException("database unavailable")));
        StepVerifier.create(f.controller.queryDeviceInterval(100000L, 200000L, f.request()))
            .expectErrorMessage("database unavailable").verify();
        verifyNoInteractions(f.raw);
    }

    @Test
    void summarySerializationKeepsAnAvailableReadingIndependently() {
        DeviceIntervalDataEntity row = new gp.saas.legacy.dto.DeviceIntervalSummaryDataEntity("d1", "E", 1000, 2000, 10, null);
        JsonNode json = new ObjectMapper().valueToTree(row);
        assertThat(json.get("startValue").intValue()).isEqualTo(10);
        assertThat(json.has("endValue")).isFalse();
    }

    @Test
    void configuredCollapsedAlignedRangeIsEmptyLikeReference() {
        Fixture f = new Fixture(true);
        long hour = time("2024-01-01T08:00:00");
        DeviceIntervalDataEntity row = f.query(hour + 10_000L, hour + 20_000L);
        assertValues(row, null, null);
        assertThat(row.getStartTime()).isEqualTo(hour);
        assertThat(row.getEndTime()).isEqualTo(hour);
    }

    @Test
    void configuredDevicesAreBatchedAndReturnedInRequestedOrder() {
        Fixture f = new Fixture(true);
        DeviceInstanceEntity other = new DeviceInstanceEntity(); other.setId("d2"); other.setProductId("p1");
        when(f.devices.findById("d2")).thenReturn(Mono.just(other));
        when(f.scope.requireDevice("d2", "p1")).thenReturn(Mono.empty());
        when(f.counters.summaryBoundaryValues(eq("p1"), eq("E"), anyCollection(), eq(100000L), eq(false), eq(false)))
            .thenReturn(Mono.just(Map.of("d1", "10", "d2", "30")));
        when(f.counters.summaryBoundaryValues(eq("p1"), eq("E"), anyCollection(), eq(200000L), eq(true), eq(false)))
            .thenReturn(Mono.just(Map.of("d1", "20", "d2", "40")));
        DeiceDataRequestEntity request = f.request(); request.setDevices(List.of("d2", "d1"));
        List<DeviceIntervalDataEntity> rows = f.controller.queryDeviceInterval(100000L, 200000L, request).collectList().block();
        assertThat(rows).extracting(DeviceIntervalDataEntity::getDeviceId).containsExactly("d2", "d1");
        assertValues(rows.get(0), "30", "40"); assertValues(rows.get(1), "10", "20");
        verify(f.counters, times(2)).summaryBoundaryValues(anyString(), anyString(), anyCollection(), anyLong(), anyBoolean(), anyBoolean());
        verify(f.counters, times(1)).readContext("p1", "E");
        verifyNoInteractions(f.raw);
    }

    @Test
    void deniedFutureRangeDoesNotBypassScopeChecks() {
        Fixture f = new Fixture(true);
        when(f.scope.requireDevice("d1", "p1")).thenReturn(Mono.error(new IllegalStateException("denied")));
        long start = System.currentTimeMillis() + 86_400_000L;
        StepVerifier.create(f.controller.queryDeviceInterval(start, start + 86_400_000L, f.request()))
            .expectErrorMessage("denied").verify();
        verifyNoInteractions(f.counters, f.raw, f.latest);
    }

    @Test
    void rejectsMissingOrBlankTargetsAndInvalidRangesLikeReference() {
        Fixture f = new Fixture(false);
        for (List<String> ids : Arrays.<List<String>>asList(null, Arrays.asList("d1", null), List.of(" "),
            java.util.Collections.nCopies(501, "d1"))) {
            DeiceDataRequestEntity request = f.request(); request.setDevices(ids);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> f.controller.queryDeviceInterval(1000L, 2000L, request))
                .isInstanceOf(IllegalArgumentException.class);
        }
        for (List<String> properties : Arrays.<List<String>>asList(null, Arrays.asList((String) null), List.of(" "), List.of(), List.of("E", "F"))) {
            DeiceDataRequestEntity request = f.request(); request.setProperties(properties);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> f.controller.queryDeviceInterval(1000L, 2000L, request))
                .isInstanceOf(IllegalArgumentException.class);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> f.controller.queryDeviceInterval(1000L, 1000L, f.request()))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(f.counters, f.raw, f.latest);
    }

    @Test
    void emptyDeviceCollectionReturnsAnEmptyResultLikeReference() {
        Fixture f = new Fixture(false);
        DeiceDataRequestEntity request = f.request(); request.setDevices(List.of());
        assertThat(f.controller.queryDeviceInterval(1000L, 2000L, request).collectList().block()).isEmpty();
        verifyNoInteractions(f.counters, f.raw, f.latest);
    }

    private static void assertValues(DeviceIntervalDataEntity row, String first, String last) {
        if (first == null) assertThat(row.getStartValue()).isNull();
        else assertThat(new BigDecimal(row.getStartValue().toString())).isEqualByComparingTo(first);
        if (last == null) assertThat(row.getEndValue()).isNull();
        else assertThat(new BigDecimal(row.getEndValue().toString())).isEqualByComparingTo(last);
    }

    private static long time(String value) {
        return LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }

    private static long liveStart(long now) {
        long start = hour(now) - 7_200_000L;
        return java.time.Instant.ofEpochMilli(start).atZone(ZoneId.of("Asia/Shanghai"))
            .toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? start - 3_600_000L : start;
    }

    private static long hour(long value) {
        return Math.floorDiv(value, 3_600_000L) * 3_600_000L;
    }

    private static class Fixture {
        final DeviceDataService raw = mock(DeviceDataService.class);
        final CounterSnapshotService counters = mock(CounterSnapshotService.class);
        final LocalDeviceInstanceService devices = mock(LocalDeviceInstanceService.class);
        final ApiTokenScopeService scope = mock(ApiTokenScopeService.class);
        final LegacyDeviceLatestValueService latest = mock(LegacyDeviceLatestValueService.class);
        final LegacyDeviceDataController controller = new LegacyDeviceDataController(raw, devices, latest, counters, scope);

        Fixture(boolean enabled, Object... samples) {
            DeviceInstanceEntity device = new DeviceInstanceEntity();
            device.setId("d1"); device.setProductId("p1");
            when(devices.findById("d1")).thenReturn(Mono.just(device));
            when(scope.requireDevice("d1", "p1")).thenReturn(Mono.empty());
            when(counters.readContext("p1", "E")).thenReturn(Mono.just(new CounterReadContext(enabled, 1, 1, 1)));
            when(latest.readProperty("d1", "E")).thenReturn(Mono.empty());
            List<DeviceProperty> points = new java.util.ArrayList<>();
            for (int i = 0; i < samples.length; i += 2) {
                DeviceProperty point = new DeviceProperty();
                point.setTimestamp(((Number) samples[i]).longValue()); point.setValue(samples[i + 1]);
                points.add(point);
            }
            when(raw.queryEachOneProperties(eq("d1"), any(QueryParamEntity.class), eq("E")))
                .thenAnswer(call -> {
                    QueryParamEntity query = call.getArgument(1);
                    java.util.stream.Stream<DeviceProperty> stream = points.stream().filter(point ->
                        query.getTerms().stream().allMatch(term -> {
                            long boundary = ((Number) term.getValue()).longValue(), stamp = point.getTimestamp();
                            return switch (term.getTermType()) {
                                case "lte" -> stamp <= boundary;
                                case "lt" -> stamp < boundary;
                                case "gt" -> stamp > boundary;
                                default -> throw new AssertionError(term.getTermType());
                            };
                        }));
                    Comparator<DeviceProperty> comparator = Comparator.comparing(DeviceProperty::getTimestamp);
                    if (query.getOrderBy().contains("desc")) comparator = comparator.reversed();
                    return Flux.fromIterable(stream.sorted(comparator).limit(1).toList());
                });
        }

        void stored(long start, long end, String first, String last, boolean daily) {
            when(counters.summaryBoundaryValues("p1", "E", List.of("d1"), start, false, daily))
                .thenReturn(Mono.just(first == null ? Map.of() : Map.of("d1", first)));
            when(counters.summaryBoundaryValues("p1", "E", List.of("d1"), end, true, daily))
                .thenReturn(Mono.just(last == null ? Map.of() : Map.of("d1", last)));
        }

        DeviceIntervalDataEntity query(long start, long end) {
            return controller.queryDeviceInterval(start, end, request()).single().block();
        }

        DeiceDataRequestEntity request() {
            DeiceDataRequestEntity request = new DeiceDataRequestEntity();
            request.setDevices(List.of("d1")); request.setProperties(List.of("E")); return request;
        }
    }
}
