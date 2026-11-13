package org.jetlinks.community.device.service.data;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.crud.events.EntityCreatedEvent;
import org.hswebframework.web.crud.events.EntityDeletedEvent;
import org.hswebframework.web.crud.events.EntityModifyEvent;
import org.hswebframework.web.crud.events.EntitySavedEvent;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.timescaledb.thing.counter.CounterSnapshotService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps policy membership and eligibility current without deleting retained history. */
@Component
@Slf4j
public class CounterSnapshotMetadataSynchronizer {
    private final HistoryStatisticsMetadataService metadata;
    private final ObjectProvider<CounterSnapshotService> snapshots;
    private final AtomicBoolean reconciliationRunning = new AtomicBoolean();

    public CounterSnapshotMetadataSynchronizer(HistoryStatisticsMetadataService metadata,
                                               ObjectProvider<CounterSnapshotService> snapshots) {
        this.metadata = metadata;
        this.snapshots = snapshots;
    }

    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        metadata.ensureInitialized().subscribe(ignored -> { }, error -> log.warn("Unable to reconcile counter policy membership", error));
    }

    @Scheduled(initialDelay = 300000, fixedDelay = 300000)
    public void reconcileMembership() {
        if (!reconciliationRunning.compareAndSet(false, true)) return;
        metadata.ensureInitialized().then(metadata.reconcileExisting()).doFinally(signal -> reconciliationRunning.set(false)).subscribe(ignored -> { },
            error -> log.warn("Unable to reconcile counter policy membership", error));
    }

    @EventListener
    public void onSaved(EntitySavedEvent<DeviceProductEntity> event) { event.async(products(event.getEntity())); }

    @EventListener
    public void onModified(EntityModifyEvent<DeviceProductEntity> event) { event.async(products(event.getAfter())); }

    @EventListener
    public void onProductDeleted(EntityDeletedEvent<DeviceProductEntity> event) { event.async(products(event.getEntity())); }

    @EventListener
    public void onDeviceCreated(EntityCreatedEvent<DeviceInstanceEntity> event) { event.async(devices(event.getEntity())); }

    @EventListener
    public void onDeviceSaved(EntitySavedEvent<DeviceInstanceEntity> event) { event.async(devices(event.getEntity())); }

    @EventListener
    public void onDeviceModified(EntityModifyEvent<DeviceInstanceEntity> event) { event.async(devices(event.getAfter())); }

    @EventListener
    public void onDeviceDeleted(EntityDeletedEvent<DeviceInstanceEntity> event) {
        CounterSnapshotService service = snapshots.getIfAvailable();
        if (service != null) event.async(Flux.fromIterable(event.getEntity()).map(DeviceInstanceEntity::getId)
            .filter(Objects::nonNull).distinct().concatMap(service::markDeviceInactive).then());
    }

    private Mono<Void> devices(List<DeviceInstanceEntity> instances) {
        if (instances == null || instances.isEmpty()) return Mono.empty();
        return Flux.fromIterable(instances).map(DeviceInstanceEntity::getId).filter(Objects::nonNull).distinct()
            .concatMap(id -> metadata.syncDevice(id).onErrorResume(error -> {
                log.warn("Unable to reconcile counter device {}", id, error); return Mono.empty();
            }))
            .then();
    }

    private Mono<Void> products(List<DeviceProductEntity> products) {
        if (products == null || products.isEmpty()) return Mono.empty();
        return Flux.fromIterable(products).map(DeviceProductEntity::getId).filter(Objects::nonNull).distinct()
            .concatMap(id -> metadata.syncProduct(id).onErrorResume(error -> {
                log.warn("Unable to reconcile counter product {}", id, error); return Mono.empty();
            }))
            .then();
    }
}
