package org.jetlinks.community.device.web;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.device.service.data.DeviceDataStorageProperties;
import org.jetlinks.community.timescaledb.TimescaleDBUtils;
import org.jetlinks.community.timescaledb.thing.counter.CounterConfig;
import org.jetlinks.community.timescaledb.thing.counter.CounterSnapshotService;
import org.jetlinks.community.timescaledb.thing.counter.CounterTask;
import org.jetlinks.core.metadata.types.NumberType;
import org.jetlinks.supports.official.JetLinksDeviceMetadataCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** Management endpoint for explicitly opted-in cumulative counter properties. */
@RestController
@RequestMapping({"/device-product/{productId}/history-statistics", "/device/product/{productId}/history-statistics"})
@Resource(id = "device-product", name = "Device product")
@Authorize
public class DeviceProductHistoryStatisticsController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final LocalDeviceProductService products;
    private final LocalDeviceInstanceService devices;
    private final DeviceDataStorageProperties storageProperties;
    private final ObjectProvider<CounterSnapshotService> snapshots;

    public DeviceProductHistoryStatisticsController(LocalDeviceProductService products,
                                                    LocalDeviceInstanceService devices,
                                                    DeviceDataStorageProperties storageProperties,
                                                    ObjectProvider<CounterSnapshotService> snapshots) {
        this.products = products;
        this.devices = devices;
        this.storageProperties = storageProperties;
        this.snapshots = snapshots;
    }

    @GetMapping({"", "/"})
    @QueryAction
    public Flux<CounterConfig> list(@PathVariable String productId) {
        return requireProduct(productId)
            .thenMany(service().list(productId));
    }

    @PutMapping("/{propertyId}")
    @SaveAction
    public Mono<CounterConfig> upsert(@PathVariable String productId,
                                      @PathVariable String propertyId,
                                      @RequestBody UpdateRequest request) {
        if (request == null || request.getEnabled() == null) {
            return Mono.error(badRequest("enabled is required"));
        }
        return requireUpsertableProperty(productId, propertyId, request.getEnabled())
            .then(Authentication.currentReactive())
            .flatMap(auth -> service().upsert(
                productId,
                propertyId,
                request.getEnabled(),
                TimescaleDBUtils.getTableName(storageProperties.getMetric().getPropertyPrefix() + productId),
                auth.getUser().getId()))
            .flatMap(config -> config.isEnabled()
                ? devices.createQuery()
                    .where(DeviceInstanceEntity::getProductId, productId)
                    .fetch()
                    .map(DeviceInstanceEntity::getId)
                    .buffer(50)
                    .concatMap(ids -> service().registerDevices(productId, ids))
                    .then(Mono.just(config))
                : Mono.just(config));
    }

    @PostMapping("/{propertyId}/_rebuild")
    @SaveAction
    public Mono<Map<String, String>> rebuild(@PathVariable String productId,
                                              @PathVariable String propertyId,
                                              @RequestBody RebuildRequest request) {
        if (request == null || request.getFrom() == null || request.getTo() == null) {
            return Mono.error(badRequest("from and to epoch milliseconds are required"));
        }
        long now = System.currentTimeMillis();
        long oldest = LocalDate.now(ZONE).minusYears(5).atStartOfDay(ZONE).toInstant().toEpochMilli();
        if (request.getFrom() >= request.getTo() || request.getFrom() < oldest || request.getTo() > now) {
            return Mono.error(badRequest("rebuild range must be ordered, within five years, and not in the future"));
        }
        return requireNumericProperty(productId, propertyId)
            .then(Authentication.currentReactive())
            .flatMap(auth -> service().rebuild(productId,
                                               propertyId,
                                               request.getFrom(),
                                               request.getTo(),
                                               auth.getUser().getId()))
            .onErrorMap(IllegalArgumentException.class,
                        error -> badRequest(error.getMessage()))
            .map(id -> Map.of("taskId", id));
    }

    @GetMapping("/tasks/{taskId}")
    @QueryAction
    public Mono<CounterTask> task(@PathVariable String productId, @PathVariable String taskId) {
        return requireProduct(productId)
            .then(service().task(taskId))
            .filter(task -> productId.equals(task.getProductId()))
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found")));
    }

    @PostMapping("/tasks/{taskId}/_cancel")
    @SaveAction
    public Mono<CounterTask> cancel(@PathVariable String productId, @PathVariable String taskId) {
        // Check ownership before issuing the state-changing cancellation.
        return task(productId, taskId)
            .then(Mono.defer(() -> service().cancel(taskId)));
    }

    private Mono<DeviceProductEntity> requireProduct(String productId) {
        return products.findById(productId)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found")));
    }

    private Mono<Boolean> hasConfiguration(String productId, String propertyId) {
        return service().list(productId)
            .any(config -> propertyId.equals(config.getPropertyId()));
    }

    private Mono<Void> requireUpsertableProperty(String productId, String propertyId, boolean enabled) {
        if (enabled) {
            return requireNumericProperty(productId, propertyId);
        }
        // An existing configuration can always be stopped, even after its property becomes invalid.
        return requireProduct(productId)
            .then(hasConfiguration(productId, propertyId))
            .flatMap(exists -> exists ? Mono.<Void>empty()
                : requireNumericProperty(productId, propertyId));
    }

    private Mono<Void> requireNumericProperty(String productId, String propertyId) {
        return requireProduct(productId)
            .flatMap(product -> JetLinksDeviceMetadataCodec.getInstance().decode(product.getMetadata()))
            .flatMap(metadata -> Mono.justOrEmpty(metadata.getProperties()
                .stream()
                .filter(property -> propertyId.equals(property.getId()))
                .findFirst()))
            .filter(property -> property.getValueType() instanceof NumberType)
            .switchIfEmpty(Mono.error(badRequest("property must be a numeric property on this product")))
            .then();
    }

    private CounterSnapshotService service() {
        CounterSnapshotService service = snapshots.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TimescaleDB counter snapshots are unavailable");
        }
        return service;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    @Getter
    @Setter
    public static class UpdateRequest {
        private Boolean enabled;
    }

    @Getter
    @Setter
    public static class RebuildRequest {
        private Long from;
        private Long to;
    }
}
