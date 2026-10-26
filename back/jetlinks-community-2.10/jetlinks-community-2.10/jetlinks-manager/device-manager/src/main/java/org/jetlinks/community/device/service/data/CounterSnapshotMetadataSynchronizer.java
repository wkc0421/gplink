package org.jetlinks.community.device.service.data;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.crud.events.EntityCreatedEvent;
import org.hswebframework.web.crud.events.EntityModifyEvent;
import org.hswebframework.web.crud.events.EntitySavedEvent;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.timescaledb.thing.counter.CounterConfig;
import org.jetlinks.community.timescaledb.thing.counter.CounterSnapshotService;
import org.jetlinks.core.metadata.types.NumberType;
import org.jetlinks.supports.official.JetLinksDeviceMetadataCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Pauses counters when their product property is no longer eligible. */
@Component
@Slf4j
public class CounterSnapshotMetadataSynchronizer {

    private final LocalDeviceProductService products;
    private final ObjectProvider<CounterSnapshotService> snapshots;

    public CounterSnapshotMetadataSynchronizer(LocalDeviceProductService products,
                                               ObjectProvider<CounterSnapshotService> snapshots) {
        this.products = products;
        this.snapshots = snapshots;
    }

    @EventListener
    public void onSaved(EntitySavedEvent<DeviceProductEntity> event) {
        event.async(reconcile(event.getEntity()));
    }

    @EventListener
    public void onModified(EntityModifyEvent<DeviceProductEntity> event) {
        event.async(reconcile(event.getBefore()));
    }

    @EventListener
    public void onDeviceCreated(EntityCreatedEvent<DeviceInstanceEntity> event) {
        event.async(registerDevices(event.getEntity()));
    }

    @EventListener
    public void onDeviceSaved(EntitySavedEvent<DeviceInstanceEntity> event) {
        event.async(registerDevices(event.getEntity()));
    }

    @EventListener
    public void onDeviceModified(EntityModifyEvent<DeviceInstanceEntity> event) {
        event.async(registerDevices(event.getAfter()));
    }

    private Mono<Void> registerDevices(List<DeviceInstanceEntity> instances) {
        CounterSnapshotService service = snapshots.getIfAvailable();
        if (service == null || instances == null || instances.isEmpty()) return Mono.empty();
        Map<String, List<String>> byProduct = instances.stream()
            .filter(instance -> instance.getId() != null && instance.getProductId() != null)
            .collect(Collectors.groupingBy(DeviceInstanceEntity::getProductId,
                Collectors.mapping(DeviceInstanceEntity::getId, Collectors.toList())));
        return Flux.fromIterable(byProduct.entrySet())
            .concatMap(entry -> service.registerDevices(entry.getKey(), entry.getValue())
                .onErrorResume(error -> {
                    log.warn("Unable to register new counter snapshot devices for product {}", entry.getKey(), error);
                    return Mono.empty();
                }))
            .then();
    }

    private Mono<Void> reconcile(List<DeviceProductEntity> productsInEvent) {
        CounterSnapshotService service = snapshots.getIfAvailable();
        if (service == null || productsInEvent == null || productsInEvent.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(productsInEvent)
            .map(DeviceProductEntity::getId)
            .filter(id -> id != null && !id.isEmpty())
            .distinct()
            .concatMap(productId -> products.findById(productId)
                .flatMap(product -> eligibleProperties(product)
                    .flatMapMany(eligible -> service.list(productId)
                        .filter(CounterConfig::isEnabled)
                        .filter(config -> !eligible.contains(config.getPropertyId()))
                        .concatMap(config -> service.suspend(productId,
                            config.getPropertyId(),
                            "Property removed or changed to a non-numeric type")))
                    .then())
                .onErrorResume(error -> {
                    log.warn("Unable to reconcile counter snapshot metadata for product {}", productId, error);
                    return Mono.empty();
                }))
            .then();
    }

    private Mono<Set<String>> eligibleProperties(DeviceProductEntity product) {
        if (product.getMetadata() == null) {
            return Mono.just(Set.of());
        }
        return JetLinksDeviceMetadataCodec.getInstance()
            .decode(product.getMetadata())
            .map(metadata -> metadata.getProperties().stream()
                .filter(property -> property.getValueType() instanceof NumberType)
                .map(property -> property.getId())
                .collect(Collectors.toSet()));
    }
}
