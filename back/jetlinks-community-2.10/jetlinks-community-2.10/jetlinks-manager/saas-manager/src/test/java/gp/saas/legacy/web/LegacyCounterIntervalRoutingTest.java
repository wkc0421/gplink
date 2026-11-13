package gp.saas.legacy.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gp.saas.apitoken.service.ApiTokenScopeService;
import gp.saas.legacy.dto.DeiceDataRequestEntity;
import gp.saas.legacy.dto.DeviceIntervalDataEntity;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.community.things.data.PropertyBoundaryValues;
import org.jetlinks.community.timescaledb.thing.counter.CounterBoundary;
import org.jetlinks.community.timescaledb.thing.counter.CounterReadContext;
import org.jetlinks.community.timescaledb.thing.counter.CounterSnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegacyCounterIntervalRoutingTest {

    @Test
    void devicesOfOneProductUseTheirOwnPolicyForHistoryAndSummary() {
        Fixture fixture = new Fixture("excluded", "enabled");
        List<Long> boundaries = calendarBoundaries("week");
        long start = boundaries.get(0), end = boundaries.get(2);
        when(fixture.snapshots.readContexts(eq("product-1"), eq("energy"), anyCollection()))
            .thenReturn(Mono.just(Map.of(
                "excluded", new CounterReadContext(false, 1, 1, 1),
                "enabled", new CounterReadContext(true, 1, 1, 1))));
        Map<Long, CounterBoundary> values = new LinkedHashMap<>();
        boundaries.forEach(time -> values.put(time, boundary(time, time == start ? "100" : "150")));
        when(fixture.snapshots.resolveBoundaries("product-1", "energy", List.of("enabled"), boundaries))
            .thenReturn(Mono.just(Map.of("enabled", values)));
        when(fixture.raw.queryPropertyAtBoundaries("excluded", "energy", start, end, boundaries))
            .thenReturn(Mono.just(new PropertyBoundaryValues("energy", start, end, 10,
                boundaries.stream().map(time -> new PropertyBoundaryValues.BoundaryValue(time, 20)).toList())));

        List<List<DeviceIntervalDataEntity>> history = fixture.controller.queryDeviceIntervalHistory(
            "week", start, end, fixture.request()).collectList().block();
        assertThat(history).extracting(rows -> rows.get(0).getDeviceId()).containsExactly("excluded", "enabled");
        assertThat(history.get(1).get(0).getStartValue()).isEqualTo(new BigDecimal("100"));
        verify(fixture.raw).queryPropertyAtBoundaries("excluded", "energy", start, end, boundaries);
        verify(fixture.snapshots).resolveBoundaries("product-1", "energy", List.of("enabled"), boundaries);

        when(fixture.snapshots.summaryBoundaryValues(
            "product-1", "energy", List.of("enabled"), start, false, false))
            .thenReturn(Mono.just(Map.of("enabled", "100")));
        when(fixture.snapshots.summaryBoundaryValues(
            "product-1", "energy", List.of("enabled"), end, true, false))
            .thenReturn(Mono.just(Map.of("enabled", "150")));
        when(fixture.raw.queryEachOneProperties(eq("excluded"),
            org.mockito.ArgumentMatchers.any(org.hswebframework.web.api.crud.entity.QueryParamEntity.class), eq("energy")))
            .thenReturn(reactor.core.publisher.Flux.empty());
        List<DeviceIntervalDataEntity> summary = fixture.controller.queryDeviceInterval(
            start, end, fixture.request()).collectList().block();
        assertThat(summary).extracting(DeviceIntervalDataEntity::getDeviceId).containsExactly("excluded", "enabled");
        assertThat(summary.get(0).getStartValue()).isNull();
        assertThat(summary.get(1).getStartValue()).isEqualTo(new BigDecimal("100"));
        assertThat(summary.get(1).getEndValue()).isEqualTo(new BigDecimal("150"));
    }

    @Test
    void changingOneDevicePolicyInvalidatesOnlyItsHistoryCache() {
        Fixture fixture = new Fixture("d1", "d2");
        List<Long> boundaries = calendarBoundaries("week");
        long start = boundaries.get(0), end = boundaries.get(2);
        java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong(1);
        when(fixture.snapshots.readContexts(eq("product-1"), eq("energy"), anyCollection()))
            .thenAnswer(call -> {
                CounterReadContext first = new CounterReadContext(true, 1, 1, 1);
                first.setPolicyRevision(revision.get());
                return Mono.just(Map.of("d1", first, "d2", new CounterReadContext(true, 1, 1, 1)));
            });
        when(fixture.snapshots.resolveBoundaries(eq("product-1"), eq("energy"), anyCollection(), eq(boundaries)))
            .thenAnswer(call -> {
                Map<String, Map<Long, CounterBoundary>> result = new LinkedHashMap<>();
                Collection<String> requested = call.getArgument(2);
                for (String id : requested) {
                    Map<Long, CounterBoundary> values = new LinkedHashMap<>();
                    boundaries.forEach(time -> values.put(time, boundary(time, time == start
                        ? Long.toString(id.equals("d1") ? revision.get() * 100 : 100) : "500")));
                    result.put(id, values);
                }
                return Mono.just(result);
            });
        for (int attempt = 0; attempt < 2; attempt++) {
            fixture.controller.queryDeviceIntervalHistory("week", start, end, fixture.request()).collectList().block();
        }
        revision.incrementAndGet();
        List<List<DeviceIntervalDataEntity>> updated = fixture.controller.queryDeviceIntervalHistory(
            "week", start, end, fixture.request()).collectList().block();
        assertThat(updated.get(0).get(0).getStartValue()).isEqualTo(new BigDecimal("200"));
        assertThat(updated.get(1).get(0).getStartValue()).isEqualTo(new BigDecimal("100"));
        verify(fixture.snapshots, times(1)).resolveBoundaries("product-1", "energy", List.of("d1", "d2"), boundaries);
        verify(fixture.snapshots, times(1)).resolveBoundaries("product-1", "energy", List.of("d1"), boundaries);
        verifyNoInteractions(fixture.raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"week", "quarter"})
    void calendarHistoryUsesConfiguredBoundariesInDeviceOrderAndInvalidatesVersionedCache(String type) {
        Fixture fixture = new Fixture("d2", "d1");
        List<Long> boundaries = calendarBoundaries(type);
        long start = boundaries.get(0), end = boundaries.get(2);
        java.util.concurrent.atomic.AtomicLong version = new java.util.concurrent.atomic.AtomicLong(1);
        when(fixture.snapshots.readContexts(eq("product-1"), eq("energy"), anyCollection()))
            .thenAnswer(call -> Mono.just(fixture.contexts(new CounterReadContext(true, version.get(), 1, 1))));
        when(fixture.snapshots.resolveBoundaries(
            eq("product-1"), eq("energy"), anyCollection(), eq(boundaries)))
            .thenAnswer(call -> {
                Map<Long, CounterBoundary> readings = new LinkedHashMap<>();
                readings.put(start, boundary(start, version.get() == 1 ? "100.123456789012345678" : "105"));
                readings.put(boundaries.get(1), boundary(boundaries.get(1), null));
                readings.put(end, boundary(end, "120.123456789012345678"));
                // Reverse storage order to verify that API output follows requested device order.
                Map<String, Map<Long, CounterBoundary>> devices = new LinkedHashMap<>();
                devices.put("d1", readings);
                devices.put("d2", readings);
                return Mono.just(devices);
            });

        for (int attempt = 0; attempt < 2; attempt++) {
            List<List<DeviceIntervalDataEntity>> result = fixture.controller.queryDeviceIntervalHistory(
                type, start, end, fixture.request()).collectList().block();
            assertThat(result).extracting(rows -> rows.get(0).getDeviceId()).containsExactly("d2", "d1");
            result.forEach(rows -> {
                assertThat(rows).extracting(DeviceIntervalDataEntity::getStartTime).containsExactly(start, boundaries.get(1));
                assertThat(rows).extracting(DeviceIntervalDataEntity::getEndTime).containsExactly(boundaries.get(1), end);
                assertThat(rows).extracting(DeviceIntervalDataEntity::getStartValue)
                    .containsExactly(new BigDecimal("100.123456789012345678"), new BigDecimal("100.123456789012345678"));
                assertThat(rows.get(1).getEndValue()).isEqualTo(new BigDecimal("120.123456789012345678"));
                rows.get(0).setStartValue(BigDecimal.ZERO);
            });
        }
        verify(fixture.snapshots, times(1)).resolveBoundaries("product-1", "energy", fixture.ids, boundaries);
        version.incrementAndGet();
        List<List<DeviceIntervalDataEntity>> refreshed = fixture.controller.queryDeviceIntervalHistory(
            type, start, end, fixture.request()).collectList().block();
        assertThat(refreshed.get(0).get(0).getStartValue()).isEqualTo(new BigDecimal("105"));
        verify(fixture.snapshots, times(2)).resolveBoundaries("product-1", "energy", fixture.ids, boundaries);
        verify(fixture.scope, times(3)).requireDevice("d2", "product-1");
        verifyNoInteractions(fixture.raw);

        when(fixture.scope.requireDevice("d2", "product-1"))
            .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)));
        StepVerifier.create(fixture.controller.queryDeviceIntervalHistory(type, start, end, fixture.request()))
            .expectError(ResponseStatusException.class).verify();
        verify(fixture.snapshots, times(2)).resolveBoundaries("product-1", "energy", fixture.ids, boundaries);
    }

    @ParameterizedTest
    @ValueSource(strings = {"week", "quarter"})
    void calendarHistoryUnconfiguredOrDisabledUsesRawBoundaries(String type) {
        Fixture fixture = new Fixture("d1");
        List<Long> boundaries = calendarBoundaries(type);
        long start = boundaries.get(0), end = boundaries.get(2);
        when(fixture.snapshots.readContexts(eq("product-1"), eq("energy"), anyCollection()))
            .thenReturn(Mono.just(fixture.contexts(new CounterReadContext(false, 0, 0, 0))));
        when(fixture.raw.queryPropertyAtBoundaries("d1", "energy", start, end, boundaries))
            .thenReturn(Mono.just(new PropertyBoundaryValues("energy", start, end, 10,
                boundaries.stream().map(time -> new PropertyBoundaryValues.BoundaryValue(time, 10)).toList())));

        assertThat(fixture.controller.queryDeviceIntervalHistory(type, start, end, fixture.request())
            .single().block()).hasSize(2);
        verify(fixture.raw).queryPropertyAtBoundaries("d1", "energy", start, end, boundaries);
        verify(fixture.snapshots, never()).resolveBoundaries(
            eq("product-1"), eq("energy"), anyCollection(), anyCollection());
    }

    @ParameterizedTest
    @ValueSource(strings = {"week", "quarter"})
    void calendarHistorySnapshotFailureIsNotConvertedToEmptyOrRawResult(String type) {
        Fixture fixture = new Fixture("d1");
        List<Long> boundaries = calendarBoundaries(type);
        when(fixture.snapshots.readContexts(eq("product-1"), eq("energy"), anyCollection()))
            .thenReturn(Mono.just(fixture.contexts(new CounterReadContext(true, 1, 1, 1))));
        when(fixture.snapshots.resolveBoundaries(
            eq("product-1"), eq("energy"), anyCollection(), anyCollection()))
            .thenReturn(Mono.error(new IllegalStateException("snapshot database unavailable")));
        StepVerifier.create(fixture.controller.queryDeviceIntervalHistory(
            type, boundaries.get(0), boundaries.get(2), fixture.request()))
            .expectErrorMessage("snapshot database unavailable").verify();
        verifyNoInteractions(fixture.raw);
    }

    private static List<Long> calendarBoundaries(String type) {
        return (type.equals("week")
            ? List.of("2024-01-03T12:30:00", "2024-01-08T00:00:00", "2024-01-10T18:45:00")
            : List.of("2024-02-29T12:30:00", "2024-04-01T00:00:00", "2024-05-20T18:45:00"))
            .stream().map(LegacyCounterIntervalRoutingTest::timestamp).toList();
    }

    @Test
    void historyUsesFollowingReadingOnlyAtOuterEndAndCountsGapOnce() {
        Fixture fixture = new Fixture("d1");
        long start = timestamp("2024-01-20T08:00:00");
        long middle = timestamp("2024-01-20T09:00:00");
        long end = timestamp("2024-01-20T10:00:00");
        long previous = timestamp("2024-01-19T23:00:00");
        when(fixture.snapshots.readContexts(eq("product-1"), eq("energy"), anyCollection()))
            .thenReturn(Mono.just(fixture.contexts(new CounterReadContext(true, 1, 1, 1))));
        Map<Long, CounterBoundary> values = new LinkedHashMap<>();
        for (long boundary : List.of(start, middle, end)) {
            values.put(boundary, new CounterBoundary(boundary, "100", previous, CounterBoundary.VALID, "SNAPSHOT"));
        }
        when(fixture.snapshots.resolveBoundaries(
            eq("product-1"), eq("energy"), anyCollection(), anyCollection(), eq(true)))
            .thenReturn(Mono.just(Map.of("d1", values)));
        when(fixture.snapshots.firstValuesAfter("product-1", "energy", List.of("d1"), end))
            .thenReturn(Mono.just(Map.of("d1", "120")));

        List<DeviceIntervalDataEntity> result = fixture.controller
            .queryDeviceIntervalHistory("day", start, end, fixture.request()).single().block();

        assertThat(result).extracting(DeviceIntervalDataEntity::getStartValue)
            .containsExactly(new BigDecimal("100"), new BigDecimal("100"));
        assertThat(result).extracting(DeviceIntervalDataEntity::getEndValue)
            .containsExactly(new BigDecimal("100"), new BigDecimal("120"));
        verify(fixture.snapshots).firstValuesAfter("product-1", "energy", List.of("d1"), end);
    }

    @Test
    void configuredHistorySeedsMissingBaselineFromFirstObservedReading() {
        Fixture fixture = new Fixture("d1");
        long start = timestamp("2024-01-01T08:00:00");
        long middle = timestamp("2024-01-01T09:00:00");
        long end = timestamp("2024-01-01T10:00:00");
        when(fixture.snapshots.readContexts(eq("product-1"), eq("energy"), anyCollection()))
            .thenReturn(Mono.just(fixture.contexts(new CounterReadContext(true, 1, 1, 1))));
        Map<Long, CounterBoundary> values = new LinkedHashMap<>();
        values.put(start, boundary(start, null));
        values.put(middle, boundary(middle, "10"));
        values.put(end, boundary(end, "20"));
        when(fixture.snapshots.resolveBoundaries(
            eq("product-1"), eq("energy"), anyCollection(), anyCollection(), eq(true)))
            .thenReturn(Mono.just(Map.of("d1", values)));

        when(fixture.snapshots.firstValuesInRange("product-1", "energy", List.of("d1"), start, end))
            .thenReturn(Mono.just(Map.of("d1", "5")));

        List<DeviceIntervalDataEntity> intervals = fixture.controller
            .queryDeviceIntervalHistory("day", start, end, fixture.request())
            .single().block();

        assertThat(intervals).hasSize(2);
        assertThat(intervals.get(0).getStartValue()).isEqualTo(new BigDecimal("5"));
        assertThat(intervals.get(0).getEndValue()).isEqualTo(new BigDecimal("10"));
        assertThat(intervals.get(1).getStartValue()).isEqualTo(new BigDecimal("10"));
        assertThat(intervals.get(1).getEndValue()).isEqualTo(new BigDecimal("20"));
        verifyNoInteractions(fixture.raw);
    }

    @Test
    void dailyAndMonthlyHistoryUseTheSameActualInitialReading() {
        Fixture fixture = new Fixture("d1");
        long start = timestamp("2024-01-01T00:00:00"), end = timestamp("2024-03-01T00:00:00");
        long firstDay = timestamp("2024-01-16T00:00:00");
        when(fixture.snapshots.readContexts(eq("product-1"), eq("energy"), anyCollection()))
            .thenReturn(Mono.just(fixture.contexts(new CounterReadContext(true, 1, 1, 1))));
        when(fixture.snapshots.resolveBoundaries(eq("product-1"), eq("energy"), anyCollection(), anyCollection()))
            .thenAnswer(call -> {
                Map<Long, CounterBoundary> points = new LinkedHashMap<>();
                for (long time : (Collection<Long>) call.getArgument(3)) {
                    points.put(time, boundary(time, time < firstDay ? null : time == end ? "150" : "110"));
                }
                return Mono.just(Map.of("d1", points));
            });
        when(fixture.snapshots.firstValuesInRange("product-1", "energy", List.of("d1"), start, end))
            .thenReturn(Mono.just(Map.of("d1", "100.123456789012345678")));
        for (String type : List.of("month", "year")) {
            List<DeviceIntervalDataEntity> rows = fixture.controller
                .queryDeviceIntervalHistory(type, start, end, fixture.request()).single().block();
            assertThat(rows.get(0).getStartValue()).isEqualTo(new BigDecimal("100.123456789012345678"));
            BigDecimal sum = rows.stream().map(row -> ((BigDecimal) row.getEndValue())
                .subtract((BigDecimal) row.getStartValue())).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).isEqualByComparingTo("49.876543210987654322");
        }
    }

    @Test
    void missingActualBaselineDoesNotSubstituteABucketLastValue() {
        Fixture fixture = new Fixture("d1");
        long start = timestamp("2024-01-01T00:00:00"), end = timestamp("2024-02-01T00:00:00");
        when(fixture.snapshots.readContexts(eq("product-1"), eq("energy"), anyCollection()))
            .thenReturn(Mono.just(fixture.contexts(new CounterReadContext(true, 1, 1, 1))));
        when(fixture.snapshots.resolveBoundaries(eq("product-1"), eq("energy"), anyCollection(), anyCollection()))
            .thenReturn(Mono.just(Map.of("d1", points(start, null, end, "50"))));
        when(fixture.snapshots.firstValuesInRange("product-1", "energy", List.of("d1"), start, end))
            .thenReturn(Mono.just(Map.of()));
        List<DeviceIntervalDataEntity> rows = fixture.controller
            .queryDeviceIntervalHistory("year", start, end, fixture.request()).single().block();
        assertThat(rows.get(0).getStartValue()).isNull();
        // The legacy DTO intentionally exposes neither reading when a delta cannot be computed.
        assertThat(rows.get(0).getEndValue()).isNull();
    }

    @Test
    void rawSuccessorOutsideTheRangeDoesNotManufactureAMissingBaseline() {
        Fixture fixture = new Fixture("d1");
        long start = 0L, end = 300_000L;
        when(fixture.raw.queryPropertyAtBoundaries("d1", "energy", start, end, List.of(start, end)))
            .thenReturn(Mono.just(new PropertyBoundaryValues("energy", start, end, null,
                List.of(new PropertyBoundaryValues.BoundaryValue(start, null),
                    new PropertyBoundaryValues.BoundaryValue(end, 50)))));
        List<DeviceIntervalDataEntity> rows = fixture.controller
            .queryDeviceIntervalHistory("5m", start, end, fixture.request()).single().block();
        assertThat(rows.get(0).getStartValue()).isNull();
        assertThat(rows.get(0).getEndValue()).isNull();
    }

    @Test
    void movingEndReusesClosedBoundariesButRefreshesTailAndChecksVersionAndAuthorization() {
        Fixture fixture = new Fixture("d1");
        long end = System.currentTimeMillis() - 120_000;
        java.time.ZonedDateTime monday = java.time.Instant.ofEpochMilli(end).atZone(ZoneId.of("Asia/Shanghai"))
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            .truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        long start = monday.minusWeeks(2).toInstant().toEpochMilli();
        java.util.concurrent.atomic.AtomicLong version = new java.util.concurrent.atomic.AtomicLong(1);
        List<List<Long>> queries = new java.util.ArrayList<>();
        when(fixture.snapshots.readContexts(eq("product-1"), eq("energy"), anyCollection()))
            .thenAnswer(call -> Mono.just(fixture.contexts(new CounterReadContext(true, version.get(), 1, 1))));
        when(fixture.snapshots.resolveBoundaries(eq("product-1"), eq("energy"), anyCollection(), anyCollection()))
            .thenAnswer(call -> {
                List<Long> times = List.copyOf(call.getArgument(3));
                queries.add(times);
                Map<Long, CounterBoundary> points = new LinkedHashMap<>();
                for (long time : times) points.put(time, boundary(time, time == start ? "100" :
                    times.size() == 1 ? Integer.toString(200 + queries.size()) : "150"));
                return Mono.just(Map.of("d1", points));
            });
        List<DeviceIntervalDataEntity> first = fixture.controller.queryDeviceIntervalHistory(
            "week", start, end, fixture.request()).single().block();
        first.get(0).setStartValue(BigDecimal.ZERO);
        List<DeviceIntervalDataEntity> second = fixture.controller.queryDeviceIntervalHistory(
            "week", start, end + 1_000, fixture.request()).single().block();
        assertThat(queries).hasSize(3);
        assertThat(queries.get(0)).hasSizeGreaterThan(1);
        assertThat(queries.get(1)).containsExactly(end);
        assertThat(queries.get(2)).containsExactly(end + 1_000);
        assertThat(second.get(0).getStartValue()).isEqualTo(new BigDecimal("100"));
        assertThat(second.get(second.size() - 1).getEndValue()).isEqualTo(new BigDecimal("203"));
        version.incrementAndGet();
        fixture.controller.queryDeviceIntervalHistory("week", start, end + 2_000, fixture.request()).collectList().block();
        assertThat(queries).hasSize(5);
        when(fixture.scope.requireDevice("d1", "product-1"))
            .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)));
        StepVerifier.create(fixture.controller.queryDeviceIntervalHistory("week", start, end + 3_000, fixture.request()))
            .expectError(ResponseStatusException.class).verify();
        assertThat(queries).hasSize(5);
    }

    @Test
    void deniedDeviceNeverReadsCacheOrSnapshot() {
        Fixture fixture = new Fixture("d1");
        when(fixture.scope.requireDevice("d1", "product-1"))
            .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)));

        StepVerifier.create(fixture.controller.queryDeviceInterval(
                1_000L, 2_000L, fixture.request()))
            .expectError(ResponseStatusException.class)
            .verify();

        verify(fixture.snapshots, never()).readContexts(eq("product-1"), eq("energy"), anyCollection());
        verifyNoInteractions(fixture.raw);
    }

    @Test
    void cachedEmptyPrefixUsesTheFirstActualTailReadingIncludingZero() {
        Fixture fixture = new Fixture("d1");
        long end = System.currentTimeMillis() - 120_000;
        java.time.ZonedDateTime monday = java.time.Instant.ofEpochMilli(end).atZone(ZoneId.of("Asia/Shanghai"))
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            .truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        long closedEnd = monday.toInstant().toEpochMilli(), start = monday.minusWeeks(2).toInstant().toEpochMilli();
        when(fixture.snapshots.readContexts(eq("product-1"), eq("energy"), anyCollection()))
            .thenReturn(Mono.just(fixture.contexts(new CounterReadContext(true, 1, 1, 1))));
        when(fixture.snapshots.resolveBoundaries(eq("product-1"), eq("energy"), anyCollection(), anyCollection()))
            .thenAnswer(call -> {
                List<Long> times = List.copyOf(call.getArgument(3));
                Map<Long, CounterBoundary> points = new LinkedHashMap<>();
                for (long time : times) points.put(time, boundary(time, times.size() == 1 ? "25" : null));
                return Mono.just(Map.of("d1", points));
            });
        when(fixture.snapshots.firstValuesInRange("product-1", "energy", List.of("d1"), start, closedEnd))
            .thenReturn(Mono.just(Map.of()));
        for (long actualEnd : List.of(end, end + 1_000)) {
            when(fixture.snapshots.firstValuesInRange("product-1", "energy", List.of("d1"), closedEnd, actualEnd))
                .thenReturn(Mono.just(Map.of("d1", "0")));
            List<DeviceIntervalDataEntity> rows = fixture.controller
                .queryDeviceIntervalHistory("week", start, actualEnd, fixture.request()).single().block();
            assertThat(rows.get(0).getStartValue()).isEqualTo(BigDecimal.ZERO);
            assertThat(rows.get(rows.size() - 1).getStartValue()).isEqualTo(BigDecimal.ZERO);
            assertThat(rows.get(rows.size() - 1).getEndValue()).isEqualTo(new BigDecimal("25"));
        }
        verify(fixture.snapshots, times(1)).firstValuesInRange("product-1", "energy", List.of("d1"), start, closedEnd);
        verify(fixture.snapshots, never()).firstValuesAfter(eq("product-1"), eq("energy"), anyCollection(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void currentCounterHourRetainsReadingsAndStopsAtNow() {
        Fixture fixture = new Fixture("d1");
        long middle = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        long start = middle - 3_600_000L;
        long end = middle + 3_600_000L;
        when(fixture.snapshots.readContexts(eq("product-1"), eq("energy"), anyCollection()))
            .thenReturn(Mono.just(fixture.contexts(new CounterReadContext(true, 1, 1, 1))));
        when(fixture.snapshots.resolveBoundaries(
            eq("product-1"), eq("energy"), anyCollection(), anyCollection(), eq(true)))
            .thenAnswer(call -> {
                Collection<Long> boundaries = call.getArgument(3);
                Map<Long, CounterBoundary> values = new LinkedHashMap<>();
                for (Long time : boundaries) {
                    values.put(time, boundary(time, time == start ? "541"
                        : time == middle ? "554.4" : "560"));
                }
                return Mono.just(Map.of("d1", values));
            });
        long before = System.currentTimeMillis();

        List<DeviceIntervalDataEntity> result = fixture.controller
            .queryDeviceIntervalHistory("day", start, end, fixture.request()).single().block();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getStartValue()).isEqualTo(new BigDecimal("541"));
        assertThat(result.get(0).getEndValue()).isEqualTo(new BigDecimal("554.4"));
        assertThat(result.get(1).getStartValue()).isEqualTo(new BigDecimal("554.4"));
        assertThat(result.get(1).getEndValue()).isEqualTo(new BigDecimal("560"));
        assertThat(result.get(1).getEndTime()).isBetween(before, System.currentTimeMillis());
        assertThat(result.get(1).getEndTime()).isLessThan(end);
        verifyNoInteractions(fixture.raw);
    }

    @Test
    void counterDailyHistoryAndCachePreserveGpSaasAdjacentStartDelta() throws Exception {
        Fixture fixture = new Fixture("d1");
        long start = timestamp("2024-01-20T00:00:00");
        long next = timestamp("2024-01-21T00:00:00");
        long today = timestamp("2024-01-22T00:00:00");
        long end = timestamp("2024-01-23T00:00:00");
        when(fixture.snapshots.readContexts(eq("product-1"), eq("energy"), anyCollection()))
            .thenReturn(Mono.just(fixture.contexts(new CounterReadContext(true, 1, 1, 1))));
        Map<Long, CounterBoundary> values = new LinkedHashMap<>();
        values.put(start, boundary(start, "1718469.63"));
        values.put(next, boundary(next, "1718469.63"));
        values.put(today, boundary(today, "1783475.97"));
        values.put(end, boundary(end, null));
        when(fixture.snapshots.resolveBoundaries(
            eq("product-1"), eq("energy"), anyCollection(), anyCollection()))
            .thenReturn(Mono.just(Map.of("d1", values)));
        when(fixture.snapshots.firstValuesAfter("product-1", "energy", List.of("d1"), end))
            .thenReturn(Mono.just(Map.of()));

        for (int attempt = 0; attempt < 2; attempt++) {
            List<DeviceIntervalDataEntity> result = fixture.controller
                .queryDeviceIntervalHistory("month", start, end, fixture.request()).single().block();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.readTree(mapper.writeValueAsString(result));
            assertThat(json.size()).isEqualTo(3);
            assertThat(json.get(2).hasNonNull("startValue")).isTrue();
            assertThat(json.get(2).get("startValue").decimalValue()
                .subtract(json.get(1).get("startValue").decimalValue()))
                .isEqualByComparingTo("65006.34");
            assertThat(json.get(2).get("endValue").decimalValue()).isEqualByComparingTo("1783475.97");
            // A caller mutating its response must not change a subsequent cached result.
            result.get(2).setStartValue(BigDecimal.ZERO);
        }
        verify(fixture.snapshots, times(1)).resolveBoundaries(
            eq("product-1"), eq("energy"), anyCollection(), anyCollection());
        verifyNoInteractions(fixture.raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"month", "week", "quarter"})
    void futureOnlyHistoryStillChecksDeviceAuthorization(String type) {
        Fixture fixture = new Fixture("d1");
        when(fixture.scope.requireDevice("d1", "product-1"))
            .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)));
        long start = System.currentTimeMillis() + 2 * 86_400_000L;

        StepVerifier.create(fixture.controller.queryDeviceIntervalHistory(
                type, start, start + 86_400_000L, fixture.request()))
            .expectError(ResponseStatusException.class).verify();

        verifyNoInteractions(fixture.snapshots, fixture.raw);
    }

    private static Map<Long, CounterBoundary> points(long firstTime, String first,
                                                      long secondTime, String second) {
        return Map.of(firstTime, boundary(firstTime, first),
            secondTime, boundary(secondTime, second));
    }

    private static CounterBoundary boundary(long time, String value) {
        return new CounterBoundary(time, value, value == null ? null : time,
            value == null ? CounterBoundary.NO_VALUE : CounterBoundary.VALID, "SNAPSHOT");
    }

    private static long timestamp(String value) {
        return LocalDateTime.parse(value)
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toInstant().toEpochMilli();
    }

    private static class Fixture {
        final DeviceDataService raw = mock(DeviceDataService.class);
        final LocalDeviceInstanceService devices = mock(LocalDeviceInstanceService.class);
        final CounterSnapshotService snapshots = mock(CounterSnapshotService.class);
        final ApiTokenScopeService scope = mock(ApiTokenScopeService.class);
        final LegacyDeviceDataController controller = new LegacyDeviceDataController(
            raw, devices, null, snapshots, scope);
        final List<String> ids;

        Fixture(String... ids) {
            this.ids = Arrays.asList(ids);
            for (String id : ids) {
                DeviceInstanceEntity entity = new DeviceInstanceEntity();
                entity.setId(id);
                entity.setProductId("product-1");
                when(devices.findById(id)).thenReturn(Mono.just(entity));
                when(scope.requireDevice(id, "product-1")).thenReturn(Mono.empty());
            }
        }

        Map<String, CounterReadContext> contexts(CounterReadContext context) {
            Map<String, CounterReadContext> result = new LinkedHashMap<>();
            ids.forEach(id -> result.put(id, context));
            return result;
        }

        DeiceDataRequestEntity request() {
            DeiceDataRequestEntity request = new DeiceDataRequestEntity();
            request.setDevices(ids);
            request.setProperties(List.of("energy"));
            return request;
        }
    }
}
