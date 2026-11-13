package org.jetlinks.community.device.service.data;

import org.jetlinks.community.device.entity.DeviceDetail;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.timescaledb.thing.counter.CounterSnapshotService;
import org.jetlinks.core.metadata.DeviceMetadata;
import org.jetlinks.core.metadata.SimpleDeviceMetadata;
import org.jetlinks.core.metadata.types.NumberType;
import org.jetlinks.supports.official.JetLinksDeviceMetadataCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Read-only metadata resolution; unlike getDeviceDetail, this never probes or changes device state. */
@Service
public class HistoryStatisticsMetadataService {
    private final LocalDeviceProductService products;
    private final LocalDeviceInstanceService devices;
    private final ObjectProvider<CounterSnapshotService> snapshots;
    private volatile Mono<Void> initialization;

    public HistoryStatisticsMetadataService(LocalDeviceProductService products,
                                            LocalDeviceInstanceService devices,
                                            ObjectProvider<CounterSnapshotService> snapshots) {
        this.products = products;
        this.devices = devices;
        this.snapshots = snapshots;
    }

    public synchronized Mono<Void> ensureInitialized() {
        if (initialization == null) {
            initialization = Mono.defer(this::reconcileExisting)
                .doOnError(error -> initialization = null).cache();
        }
        return initialization;
    }

    /** Queries may await startup reconciliation, but never initiate a control-table write themselves. */
    public Mono<Void> awaitInitialized() {
        Mono<Void> current = initialization;
        return current == null ? Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "历史统计成员正在初始化，请稍后重试")) : current;
    }

    public static DeviceMetadata productMetadata(DeviceProductEntity product) {
        return decode(product == null ? null : product.getMetadata());
    }

    public static DeviceMetadata effectiveMetadata(DeviceProductEntity product, DeviceInstanceEntity device) {
        DeviceDetail detail = new DeviceDetail().with(product).with(device);
        return decode(StringUtils.hasText(detail.getMetadata()) ? detail.getMetadata() : detail.getProductMetadata());
    }

    private static DeviceMetadata decode(String metadata) {
        return StringUtils.hasText(metadata)
            ? JetLinksDeviceMetadataCodec.getInstance().doDecode(metadata) : new SimpleDeviceMetadata();
    }

    public Mono<DeviceProductEntity> product(String id) {
        return products.findById(id).switchIfEmpty(Mono.error(
            new ResponseStatusException(HttpStatus.NOT_FOUND, "产品不存在")));
    }

    public Mono<DeviceInstanceEntity> device(String id) {
        return devices.findById(id).switchIfEmpty(Mono.error(
            new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在")));
    }

    public Mono<DeviceMetadata> effectiveMetadata(String id) {
        return device(id).flatMap(device -> product(device.getProductId())
            .map(product -> effectiveMetadata(product, device)));
    }

    public Mono<Void> syncDevice(String deviceId) {
        CounterSnapshotService service = snapshots.getIfAvailable();
        if (service == null) return Mono.empty();
        return devices.findById(deviceId)
            .flatMap(device -> products.findById(device.getProductId())
                .flatMap(product -> syncDevice(service, product, device).thenReturn(true))
                .switchIfEmpty(Mono.defer(() -> service.markDeviceInactive(deviceId).thenReturn(false))))
            .switchIfEmpty(Mono.defer(() -> service.markDeviceInactive(deviceId).thenReturn(false))).then();
    }

    private Mono<Void> syncDevice(CounterSnapshotService service, DeviceProductEntity product,
                                  DeviceInstanceEntity device) {
        Map<String, Boolean> properties = new LinkedHashMap<>();
        effectiveMetadata(product, device).getProperties()
            .forEach(property -> properties.put(property.getId(), property.getValueType() instanceof NumberType));
        return service.syncDeviceProperties(product.getId(), device.getId(), properties);
    }

    public Mono<Void> syncProduct(String productId) {
        CounterSnapshotService service = snapshots.getIfAvailable();
        if (service == null) return Mono.empty();
        return products.findById(productId).flatMap(product -> {
            List<String> numeric = productMetadata(product).getProperties().stream()
                .filter(property -> property.getValueType() instanceof NumberType)
                .map(property -> property.getId()).collect(Collectors.toList());
            return service.syncProductProperties(productId, numeric)
                .thenMany(devices.createQuery().where(DeviceInstanceEntity::getProductId, productId).fetch())
                .concatMap(device -> syncDevice(service, product, device)).then(Mono.just(true));
        }).switchIfEmpty(Mono.defer(() -> service.markProductInactive(productId).thenReturn(false))).then();
    }

    /** Reconcile old rosters without copying names or models into the TimescaleDB control tables. */
    public Mono<Void> reconcileExisting() {
        CounterSnapshotService service = snapshots.getIfAvailable();
        if (service == null) return Mono.empty();
        return service.configuredProductIds().concatMap(this::syncProduct).thenMany(
            service.configuredDeviceIds().buffer(200).concatMap(ids -> devices.createQuery()
                .in(DeviceInstanceEntity::getId, ids).fetch().map(DeviceInstanceEntity::getId).collectList()
                .flatMapMany(current -> Flux.fromIterable(ids).filter(id -> !current.contains(id))
                    .concatMap(service::markDeviceInactive))))
            .then();
    }
}
