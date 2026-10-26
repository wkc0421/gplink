package org.jetlinks.community.device.service.data;

import org.hswebframework.web.crud.events.EntityCreatedEvent;
import org.hswebframework.web.crud.events.EntitySavedEvent;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.timescaledb.thing.counter.CounterSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CounterSnapshotMetadataSynchronizerTest {

    @Test
    void createdDeviceEventUsesTheSameRegistrationPathAsSavedEvent() throws Exception {
        CounterSnapshotService snapshots = mock(CounterSnapshotService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<CounterSnapshotService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(snapshots);
        when(snapshots.registerDevices("product-1", List.of("device-1")))
            .thenReturn(Mono.empty());
        CounterSnapshotMetadataSynchronizer synchronizer = new CounterSnapshotMetadataSynchronizer(
            mock(LocalDeviceProductService.class), provider);
        DeviceInstanceEntity device = new DeviceInstanceEntity();
        device.setId("device-1");
        device.setProductId("product-1");

        assertThat(CounterSnapshotMetadataSynchronizer.class
            .getMethod("onDeviceCreated", EntityCreatedEvent.class)
            .getAnnotation(EventListener.class)).isNotNull();

        EntityCreatedEvent<DeviceInstanceEntity> created = new EntityCreatedEvent<>(
            List.of(device), DeviceInstanceEntity.class);
        synchronizer.onDeviceCreated(created);
        StepVerifier.create(created.getAsync()).verifyComplete();

        EntitySavedEvent<DeviceInstanceEntity> saved = new EntitySavedEvent<>(
            List.of(device), DeviceInstanceEntity.class);
        synchronizer.onDeviceSaved(saved);
        StepVerifier.create(saved.getAsync()).verifyComplete();

        verify(snapshots, times(2)).registerDevices("product-1", List.of("device-1"));
    }
}
