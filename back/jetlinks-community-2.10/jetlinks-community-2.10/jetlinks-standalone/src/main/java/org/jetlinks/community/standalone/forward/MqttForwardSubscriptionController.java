package org.jetlinks.community.standalone.forward;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/mqtt/forward/subscription")
@RequiredArgsConstructor
@Tag(name = "MQTT转发订阅管理")
public class MqttForwardSubscriptionController {

    private final MqttForwardSubscriptionService service;

    @GetMapping
    @Operation(summary = "查询订阅列表")
    public Flux<MqttForwardSubscriptionEntity> list(
        @RequestParam(required = false) String productId,
        @RequestParam(required = false) String state) {
        return service.createQuery()
            .when(productId != null, q -> q.where(MqttForwardSubscriptionEntity::getProductId, productId))
            .when(state != null, q -> q.where(MqttForwardSubscriptionEntity::getState, state))
            .fetch();
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据ID查询订阅")
    public Mono<MqttForwardSubscriptionEntity> getById(@PathVariable String id) {
        return service.findById(id);
    }

    @PostMapping
    @Operation(summary = "创建订阅")
    public Mono<MqttForwardSubscriptionEntity> create(@RequestBody MqttForwardSubscriptionEntity entity) {
        entity.setId(null);
        return service.save(entity).thenReturn(entity);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新订阅（先停止旧订阅，保存后自动重启）")
    public Mono<MqttForwardSubscriptionEntity> update(@PathVariable String id,
                                                       @RequestBody MqttForwardSubscriptionEntity entity) {
        entity.setId(id);
        return service.save(entity).thenReturn(entity);
    }

    @PostMapping("/batch")
    @Operation(summary = "批量创建订阅")
    public Flux<MqttForwardSubscriptionEntity> batchCreate(@RequestBody List<MqttForwardSubscriptionEntity> entities) {
        entities.forEach(e -> e.setId(null));
        return service.save(Flux.fromIterable(entities)).thenMany(Flux.fromIterable(entities));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除订阅")
    public Mono<Void> delete(@PathVariable String id) {
        return service.deleteById(id).then();
    }

    @DeleteMapping("/batch")
    @Operation(summary = "批量删除订阅")
    public Mono<Void> batchDelete(@RequestBody List<String> ids) {
        return service.deleteById(Flux.fromIterable(ids)).then();
    }

    @DeleteMapping("/all")
    @Operation(summary = "删除全部订阅")
    public Mono<Void> deleteAll() {
        return service.createDelete().execute().then();
    }

    @PostMapping("/by-devices")
    @Operation(summary = "按设备ID列表批量创建订阅（共用同一套配置）")
    public Flux<MqttForwardSubscriptionEntity> createByDevices(@RequestBody DeviceSubscribeRequest request) {
        return service.createByDevices(request);
    }

    @DeleteMapping("/by-devices")
    @Operation(summary = "按设备ID列表批量删除订阅")
    public Mono<Void> deleteByDevices(@RequestBody List<String> deviceIds) {
        return service.deleteByDeviceIds(deviceIds);
    }

    @GetMapping("/active")
    @Operation(summary = "查看当前运行中的订阅流（key=entityId_messageType, value=是否活跃）")
    public Mono<Map<String, Boolean>> getActiveSubscriptions() {
        return Mono.just(service.getActiveSubscriptions());
    }
}
