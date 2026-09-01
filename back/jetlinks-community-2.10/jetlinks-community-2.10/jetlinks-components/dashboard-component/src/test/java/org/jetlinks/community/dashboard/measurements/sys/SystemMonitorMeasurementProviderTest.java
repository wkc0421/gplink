package org.jetlinks.community.dashboard.measurements.sys;

import org.jetlinks.community.timeseries.TimeSeriesData;
import org.jetlinks.community.timeseries.TimeSeriesManager;
import org.jetlinks.community.timeseries.TimeSeriesMetric;
import org.jetlinks.community.timeseries.TimeSeriesService;
import org.jetlinks.core.event.EventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemMonitorMeasurementProviderTest {

    @Mock
    private TimeSeriesManager timeSeriesManager;
    @Mock
    private TimeSeriesService timeSeriesService;
    @Mock
    private EventBus eventBus;
    @Mock
    private SystemMonitorService monitorService;

    private VirtualTimeScheduler scheduler;
    private SystemMonitorMeasurementProvider provider;

    @BeforeEach
    void setUp() {
        scheduler = VirtualTimeScheduler.create();
        when(monitorService.system()).thenReturn(Mono.just(systemInfo()));
        when(timeSeriesManager.registerMetadata(any())).thenReturn(Mono.empty());
        when(timeSeriesManager.getService(any(TimeSeriesMetric.class))).thenReturn(timeSeriesService);
        when(timeSeriesService.commit(any(TimeSeriesData.class))).thenReturn(Mono.empty());

        provider = new SystemMonitorMeasurementProvider(
            timeSeriesManager,
            eventBus,
            monitorService,
            Duration.ofMinutes(5),
            scheduler
        );
        provider.init();
    }

    @AfterEach
    void tearDown() {
        provider.destroy();
    }

    @Test
    void shouldCollectOnStartupAndEveryFiveMinutes() {
        verify(monitorService, times(1)).system();
        verify(timeSeriesService, times(1)).commit(any(TimeSeriesData.class));

        scheduler.advanceTimeBy(Duration.ofMinutes(4).plusSeconds(59));
        verify(monitorService, times(1)).system();

        scheduler.advanceTimeBy(Duration.ofSeconds(1));
        verify(monitorService, times(2)).system();
        verify(timeSeriesService, times(2)).commit(any(TimeSeriesData.class));

        scheduler.advanceTimeBy(Duration.ofMinutes(10));
        verify(monitorService, times(4)).system();
        verify(timeSeriesService, times(4)).commit(any(TimeSeriesData.class));
        verify(monitorService, never()).cpu();
        verify(monitorService, never()).memory();
        verify(monitorService, never()).disk();
    }

    @Test
    void shouldRejectNonPositiveInterval() {
        VirtualTimeScheduler invalidScheduler = VirtualTimeScheduler.create();
        try {
            assertThrows(
                IllegalArgumentException.class,
                () -> new SystemMonitorMeasurementProvider(
                    timeSeriesManager,
                    eventBus,
                    monitorService,
                    Duration.ZERO,
                    invalidScheduler
                )
            );
        } finally {
            invalidScheduler.dispose();
        }
    }

    private static SystemInfo systemInfo() {
        return SystemInfo.of(
            new CpuInfo(1F, 2F),
            new MemoryInfo(1024, 512, 256, 128, 4096, 2048),
            new DiskInfo(8192, 4096)
        );
    }
}
