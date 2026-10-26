package org.jetlinks.community.device.web;

import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.device.service.data.DeviceDataStorageProperties;
import org.jetlinks.community.timescaledb.thing.counter.CounterSnapshotService;
import org.jetlinks.community.timescaledb.thing.counter.CounterTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceProductHistoryStatisticsControllerTest {

    @Test
    void rejectsCrossProductTaskReadAndCancellation() {
        LocalDeviceProductService products = mock(LocalDeviceProductService.class);
        CounterSnapshotService snapshots = mock(CounterSnapshotService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<CounterSnapshotService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(snapshots);
        when(products.findById("allowed")).thenReturn(Mono.just(new DeviceProductEntity()));
        CounterTask otherProductTask = new CounterTask();
        otherProductTask.setId("task-1");
        otherProductTask.setProductId("other");
        when(snapshots.task("task-1")).thenReturn(Mono.just(otherProductTask));
        DeviceProductHistoryStatisticsController controller = new DeviceProductHistoryStatisticsController(
            products, mock(LocalDeviceInstanceService.class), new DeviceDataStorageProperties(), provider);

        StepVerifier.create(controller.task("allowed", "task-1"))
            .expectError(ResponseStatusException.class)
            .verify();
        StepVerifier.create(controller.cancel("allowed", "task-1"))
            .expectError(ResponseStatusException.class)
            .verify();
        verify(snapshots, never()).cancel("task-1");
    }

    @Test
    void rejectsInvalidRebuildBeforeAccessingStorage() {
        LocalDeviceProductService products = mock(LocalDeviceProductService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<CounterSnapshotService> provider = mock(ObjectProvider.class);
        DeviceProductHistoryStatisticsController controller = new DeviceProductHistoryStatisticsController(
            products, mock(LocalDeviceInstanceService.class), new DeviceDataStorageProperties(), provider);
        DeviceProductHistoryStatisticsController.RebuildRequest request =
            new DeviceProductHistoryStatisticsController.RebuildRequest();
        request.setFrom(200L);
        request.setTo(100L);

        StepVerifier.create(controller.rebuild("product", "counter", request))
            .expectError(ResponseStatusException.class)
            .verify();
        verify(provider, never()).getIfAvailable();
    }
}
