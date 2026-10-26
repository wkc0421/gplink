package org.jetlinks.community.timescaledb.thing.counter;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

class CounterSnapshotTimeTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static long epoch(String local) {
        return LocalDateTime.parse(local).atZone(SHANGHAI).toInstant().toEpochMilli();
    }

    @Test
    void fullDayBackfillContainsTwentyFiveBoundaries() {
        long from = epoch("2024-02-29T00:00:00");
        long to = CounterSnapshotService.ceilHour(epoch("2024-02-29T23:59:59"));
        List<Long> boundaries = new ArrayList<>();
        for (long time = from; time <= to; time = CounterSnapshotService.nextHour(time)) {
            boundaries.add(time);
        }
        assertThat(boundaries).hasSize(25);
        assertThat(boundaries.get(24)).isEqualTo(epoch("2024-03-01T00:00:00"));
        assertThat(CounterSnapshotService.midnight(boundaries.get(0))).isTrue();
        assertThat(CounterSnapshotService.midnight(boundaries.get(24))).isTrue();
    }

    @Test
    void roundingUsesShanghaiAcrossMonthYearAndDifferentJvmZones() {
        TimeZone previous = TimeZone.getDefault();
        try {
            long leap = epoch("2024-02-29T23:30:00");
            for (String zone : new String[]{"UTC", "America/Los_Angeles", "Asia/Tokyo"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertThat(CounterSnapshotService.floorHour(leap))
                    .isEqualTo(epoch("2024-02-29T23:00:00"));
                assertThat(CounterSnapshotService.ceilHour(leap))
                    .isEqualTo(epoch("2024-03-01T00:00:00"));
                assertThat(CounterSnapshotService.floorHour(epoch("2025-01-01T00:00:00")))
                    .isEqualTo(epoch("2025-01-01T00:00:00"));
            }
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void alignedEndpointDoesNotExpand() {
        long aligned = epoch("2026-09-23T08:00:00");
        assertThat(CounterSnapshotService.floorHour(aligned)).isEqualTo(aligned);
        assertThat(CounterSnapshotService.ceilHour(aligned)).isEqualTo(aligned);
        assertThat(CounterSnapshotService.nextHour(aligned))
            .isEqualTo(epoch("2026-09-23T09:00:00"));
    }
}
