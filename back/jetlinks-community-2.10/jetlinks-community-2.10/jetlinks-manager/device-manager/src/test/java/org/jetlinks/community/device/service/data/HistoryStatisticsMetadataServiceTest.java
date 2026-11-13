package org.jetlinks.community.device.service.data;

import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.timescaledb.thing.counter.CounterSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HistoryStatisticsMetadataServiceTest {
    @Test
    void validDeviceSyncDoesNotFallThroughToInactivePathAfterVoidCompletion() {
        Fixture fixture = new Fixture();
        DeviceInstanceEntity device = new DeviceInstanceEntity(); device.setId("d"); device.setProductId("p");
        DeviceProductEntity product = new DeviceProductEntity(); product.setId("p");
        product.setMetadata("{\"properties\":[{\"id\":\"energy\",\"valueType\":{\"type\":\"double\"}}]}");
        when(fixture.devices.findById("d")).thenReturn(Mono.just(device));
        when(fixture.products.findById("p")).thenReturn(Mono.just(product));
        when(fixture.counter.syncDeviceProperties("p", "d", Map.of("energy", true))).thenReturn(Mono.empty());
        fixture.service.syncDevice("d").block();
        verify(fixture.counter).syncDeviceProperties("p", "d", Map.of("energy", true));
        verify(fixture.counter, never()).markDeviceInactive(anyString());
        verify(fixture.devices, never()).getDeviceDetail(anyString());
    }

    @Test
    void removedDeviceMarksMembershipInactiveAndPreservesHistory() {
        Fixture fixture = new Fixture();
        when(fixture.devices.findById("deleted")).thenReturn(Mono.empty());
        when(fixture.counter.markDeviceInactive("deleted")).thenReturn(Mono.empty());
        fixture.service.syncDevice("deleted").block();
        verify(fixture.counter).markDeviceInactive("deleted");
        verify(fixture.counter, never()).upsertDevice(anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void initializationIsSharedAndReconcilesExistingMembershipOnlyOnce() {
        Fixture fixture = new Fixture();
        when(fixture.counter.configuredProductIds()).thenReturn(Flux.empty());
        when(fixture.counter.configuredDeviceIds()).thenReturn(Flux.empty());
        fixture.service.ensureInitialized().block(); fixture.service.ensureInitialized().block();
        verify(fixture.counter, times(1)).configuredProductIds(); verify(fixture.counter, times(1)).configuredDeviceIds();
    }

    private static class Fixture {
        final LocalDeviceProductService products = mock(LocalDeviceProductService.class);
        final LocalDeviceInstanceService devices = mock(LocalDeviceInstanceService.class);
        final CounterSnapshotService counter = mock(CounterSnapshotService.class);
        final HistoryStatisticsMetadataService service;
        Fixture() {
            ObjectProvider<CounterSnapshotService> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(counter);
            service = new HistoryStatisticsMetadataService(products, devices, provider);
        }
    }
}
