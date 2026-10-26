package org.jetlinks.community.timescaledb.thing.counter;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CounterSnapshotRefreshProofTest {
    @Test
    void fiveDayLateBatchIncludesTheWholeFirstBeijingDay() {
        LocalDateTime from = LocalDateTime.of(2026, 9, 19, 9, 0);
        LocalDateTime to = LocalDateTime.of(2026, 9, 20, 10, 0);

        assertThat(CounterSnapshotService.calendarStarts(from, to, false))
            .containsExactly(LocalDateTime.of(2026, 9, 19, 0, 0),
                LocalDateTime.of(2026, 9, 20, 0, 0));
        assertThat(CounterSnapshotService.calendarStarts(from, to, true))
            .containsExactly(LocalDateTime.of(2026, 9, 1, 0, 0));
    }

    @Test
    void fiveHourLateReportRefreshesOnlyCompletedAffectedHours() {
        LocalDateTime day = LocalDateTime.of(2026, 9, 24, 0, 0);
        LocalDateTime[] hours = CounterSnapshotService.openDayRefreshWindow(
            day.plusHours(10), day.plusHours(17), day, day.plusHours(16));

        assertThat(hours).containsExactly(day.plusHours(10), day.plusHours(16));
        assertThat(CounterSnapshotService.openDayRefreshWindow(
            day.plusHours(16), day.plusHours(17), day, day.plusHours(16))).isNull();
    }

    @Test
    void adjacentHourlyProofsCertifyFullDayButGapDoesNot() {
        LocalDateTime day = LocalDateTime.of(2026, 9, 19, 0, 0);
        List<Map<String, Object>> continuous = List.of(
            range(day.plusHours(12), day.plusDays(1)),
            range(day, day.plusHours(12)));
        assertThat(CounterSnapshotService.coversRefreshWindow(
            continuous, day, day.plusDays(1))).isTrue();
        assertThat(CounterSnapshotService.coversRefreshWindow(
            List.of(range(day, day.plusHours(10)),
                range(day.plusHours(11), day.plusDays(1))), day, day.plusDays(1))).isFalse();
    }

    @Test
    void dailyProofsMustCoverTheEntireClosedCalendarMonth() {
        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime to = from.plusMonths(1);
        assertThat(CounterSnapshotService.coversRefreshWindow(List.of(
            range(from, from.plusDays(20)), range(from.plusDays(20), to)), from, to)).isTrue();
        assertThat(CounterSnapshotService.coversRefreshWindow(List.of(
            range(from.plusDays(19), to)), from, to)).isFalse();
    }

    private static Map<String, Object> range(LocalDateTime from, LocalDateTime to) {
        return Map.of("from_time", from, "to_time", to);
    }

    @Test
    void onePhysicalRefreshCanCertifyOnlyUnchangedProperties() {
        CounterSnapshotService.VersionKey stable =
            new CounterSnapshotService.VersionKey("product", "energy");
        CounterSnapshotService.VersionKey changed =
            new CounterSnapshotService.VersionKey("product", "water");
        Map<CounterSnapshotService.VersionKey, Long> before = new LinkedHashMap<>();
        before.put(stable, 12L);
        before.put(changed, 4L);
        Map<CounterSnapshotService.VersionKey, Long> after = new LinkedHashMap<>();
        after.put(stable, 12L);
        after.put(changed, 5L); // An ingest committed while the physical CAGG refreshed.
        assertThat(CounterSnapshotService.unchangedVersions(before, after))
            .containsExactly(stable);
    }
}
