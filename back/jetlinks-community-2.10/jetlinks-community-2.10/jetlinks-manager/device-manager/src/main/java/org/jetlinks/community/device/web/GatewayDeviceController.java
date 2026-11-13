/*
 * Copyright 2025 JetLinks https://www.jetlinks.cn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetlinks.community.device.web;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.collections4.CollectionUtils;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryOperation;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.enums.DeviceState;
import org.jetlinks.community.device.enums.DeviceType;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.device.web.response.GatewayDeviceInfo;
import org.jetlinks.core.ProtocolSupport;
import org.jetlinks.core.device.DeviceConfigKey;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.function.Function3;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 网关设备接口
 *
 * @author zhouhao
 * @since 1.0
 */
@RestController
@RequestMapping("/device/gateway")
@Resource(id = "device-gateway-manager", name = "网关设备管理")
@Authorize
@Tag(name = "网关设备管理")
public class GatewayDeviceController {

    @Autowired
    private LocalDeviceInstanceService instanceService;

    @Autowired
    private LocalDeviceProductService productService;

    @Autowired
    private DeviceRegistry registry;

    @SuppressWarnings("all")
    private Mono<List<String>> getGatewayProductList() {
        return productService
            .createQuery()
            .select(DeviceProductEntity::getId)
            .where(DeviceProductEntity::getDeviceType, DeviceType.gateway)
            .fetch()
            .map(DeviceProductEntity::getId)
            .collectList()
            .filter(CollectionUtils::isNotEmpty);
    }

    @GetMapping("/_query")
    @QueryAction
    @QueryOperation(summary = "查询网关设备详情")
    public Mono<PagerResult<GatewayDeviceInfo>> queryGatewayDevice(@Parameter(hidden = true) QueryParamEntity param) {
        return this
            .getGatewayProductList()
            .flatMap(productIdList -> param
                .toNestQuery(query -> query.in(DeviceInstanceEntity::getProductId, productIdList))
                .execute(instanceService::queryPager)
                .filter(r -> r.getTotal() > 0)
                .flatMap(result -> {
                    Map<String, DeviceInstanceEntity> mapping =
                        result.getData()
                              .stream()
                              .collect(Collectors.toMap(DeviceInstanceEntity::getId, Function.identity()));

                    //查询所有子设备并按父设备ID分组
                    return instanceService
                        .createQuery()
                        .where()
                        .in(DeviceInstanceEntity::getParentId, mapping.keySet())
                        .fetch()
                        .groupBy(DeviceInstanceEntity::getParentId, Integer.MAX_VALUE)
                        .flatMap(group -> {
                            String parentId = group.key();
                            return group
                                .collectList()
                                //将父设备和分组的子设备合并在一起
                                .map(children -> GatewayDeviceInfo.of(mapping.get(parentId), children));
                        })
                        .collectMap(GatewayDeviceInfo::getId)//收集所有有子设备的网关设备信息
                        .defaultIfEmpty(Collections.emptyMap())
                        .flatMapMany(map -> Flux
                            .fromIterable(mapping.values())
                            .flatMap(ins -> Mono
                                .justOrEmpty(map.get(ins.getId()))
                                //处理没有子设备的网关信息
                                .switchIfEmpty(Mono.fromSupplier(() -> GatewayDeviceInfo.of(ins, Collections.emptyList())))))
                        .collectList()
                        .map(list -> PagerResult.of(result.getTotal(), list, param));
                }))
            .defaultIfEmpty(PagerResult.empty());
    }

    @GetMapping("/{id}")
    @QueryAction
    @QueryOperation(summary = "获取单个网关设备详情")
    public Mono<GatewayDeviceInfo> getGatewayInfo(@PathVariable String id) {
        return Mono.zip(
            instanceService.findById(id),
            instanceService
                .createQuery()
                .where()
                .is(DeviceInstanceEntity::getParentId, id)
                .fetch()
                .collectList()
                .defaultIfEmpty(Collections.emptyList()),
            GatewayDeviceInfo::of);
    }


    @PostMapping("/{gatewayId}/bind/{deviceId}")
    @SaveAction
    @QueryOperation(summary = "绑定单个子设备到网关设备")
    @Transactional
    public Mono<GatewayDeviceInfo> bindDevice(@PathVariable @Parameter(description = "网关设备ID") String gatewayId,
                                              @PathVariable @Parameter(description = "子设备ID") String deviceId,
                                              @RequestParam(name = "validateRuntime", defaultValue = "false") boolean validateRuntime) {
        return bindDevices(gatewayId, Mono.just(Collections.singletonList(deviceId)), validateRuntime);
    }

    @PostMapping("/{gatewayId}/bind")
    @SaveAction
    @QueryOperation(summary = "绑定多个子设备到网关设备")
    @Transactional
    public Mono<GatewayDeviceInfo> bindDevice(@PathVariable @Parameter(description = "网关设备ID") String gatewayId,
                                              @RequestBody @Parameter(description = "子设备ID集合") Mono<List<String>> deviceId,
                                              @RequestParam(name = "validateRuntime", defaultValue = "false") boolean validateRuntime) {
        return bindDevices(gatewayId, deviceId, validateRuntime);
    }

    private Mono<GatewayDeviceInfo> bindDevices(String gatewayId, Mono<List<String>> deviceId,
                                               boolean validateRuntime) {
        return deviceId
            .flatMapIterable(Function.identity())
            .distinct()
            .flatMap(childId -> instanceService
                .checkCyclicDependency(childId, gatewayId)
                .thenReturn(childId))
            .collectList()
            .filter(CollectionUtils::isNotEmpty)
            .flatMap(deviceIdList -> validateBindingDevices(gatewayId, deviceIdList, validateRuntime)
                .then(Mono.defer(() -> instanceService
                    .createUpdate()
                    .set(DeviceInstanceEntity::getParentId, gatewayId)
                    .where()
                    .in(DeviceInstanceEntity::getId, deviceIdList)
                    .execute()))
                // Do not rely on entity events or the protocol consuming the child Flux.
                .then(synchronizeBindingsAndNotify(gatewayId, deviceIdList, validateRuntime))
            ).then(getGatewayInfo(gatewayId));
    }

    Mono<Void> validateBindingDevices(String gatewayId, List<String> childIds, boolean validateRuntime) {
        return Flux.concat(Flux.just(gatewayId), Flux.fromIterable(childIds))
            .concatMap(id -> instanceService.findById(id)
                .switchIfEmpty(Mono.error(new IllegalStateException("Device [" + id + "] does not exist")))
                .flatMap(entity -> registeredForBinding(entity, validateRuntime)))
            .then();
    }

    private Mono<DeviceOperator> registeredForBinding(DeviceInstanceEntity entity, boolean validateRuntime) {
        if (validateRuntime && entity.getState() != DeviceState.offline && entity.getState() != DeviceState.online) {
            return Mono.error(new IllegalStateException("Device [" + entity.getId() + "] is not activated"));
        }
        return Mono.defer(() -> registry.getDevice(entity.getId()))
            .switchIfEmpty(Mono.defer(() -> !validateRuntime && entity.getState() == DeviceState.notActive
                ? Mono.empty()
                : Mono.error(new IllegalStateException("Device [" + entity.getId()
                    + "] is not registered; activate it before binding"))));
    }

    Mono<Void> synchronizeBindings(String gatewayId, List<String> childIds, boolean validateRuntime) {
        return Flux.fromIterable(childIds)
            .concatMap(id -> instanceService.findById(id)
                .switchIfEmpty(Mono.error(new IllegalStateException("Device [" + id + "] does not exist")))
                .flatMap(entity -> registeredForBinding(entity, validateRuntime))
                .flatMap(child -> child.setConfig(DeviceConfigKey.parentGatewayId, gatewayId)
                    .filter(Boolean.TRUE::equals)
                    .switchIfEmpty(Mono.error(new IllegalStateException("Failed to synchronize parent for device [" + id + "]")))
                    .then(Mono.defer(() -> child.getSelfConfig(DeviceConfigKey.parentGatewayId)))
                    .filter(gatewayId::equals)
                    .switchIfEmpty(Mono.error(new IllegalStateException("Parent binding verification failed for device [" + id + "]")))))
            .then();
    }

    Mono<Void> synchronizeBindingsAndNotify(String gatewayId, List<String> childIds, boolean validateRuntime) {
        return synchronizeBindings(gatewayId, childIds, validateRuntime)
            .then(handleBindUnbind(gatewayId, Flux.fromIterable(childIds), ProtocolSupport::onChildBind));
    }

    @PostMapping("/{gatewayId}/unbind/{deviceId}")
    @SaveAction
    @QueryOperation(summary = "从网关设备中解绑单个子设备")
    @Transactional
    public Mono<GatewayDeviceInfo> unBindDevice(@PathVariable @Parameter(description = "网关设备ID") String gatewayId,
                                                @PathVariable @Parameter(description = "自设备ID") String deviceId) {
        return instanceService
            .createUpdate()
            .setNull(DeviceInstanceEntity::getParentId)
            .where(DeviceInstanceEntity::getId, deviceId)
            .and(DeviceInstanceEntity::getParentId, gatewayId)
            .execute()
            .filter(i -> i > 0)
            .then(registry.getDevice(gatewayId)
                .flatMap(gwOperator -> gwOperator.getProtocol()
                    .flatMap(protocolSupport -> protocolSupport.onChildUnbind(gwOperator,
                        Flux.from(registry.getDevice(deviceId)))
                    )
                )
            )
            .then(getGatewayInfo(gatewayId));
    }

    @PostMapping("/{gatewayId}/unbind")
    @SaveAction
    @QueryOperation(summary = "从网关设备中解绑多个子设备")
    @Transactional
    public Mono<Void> unBindDevice(@PathVariable @Parameter(description = "网关设备ID") String gatewayId,
                                   @RequestBody @Parameter(description = "子设备ID集合") Mono<List<String>> deviceId) {
        Mono<List<String>> deviceIdCache = deviceId.cache();

        return deviceId
            .flatMap(deviceIdList -> instanceService
                .createUpdate()
                .setNull(DeviceInstanceEntity::getParentId)
                .in(DeviceInstanceEntity::getId, deviceIdList)
                .and(DeviceInstanceEntity::getParentId, gatewayId)
                .execute()
            )
            .then(handleBindUnbind(gatewayId, deviceIdCache.flatMapIterable(Function.identity()), ProtocolSupport::onChildUnbind));
    }

    private Mono<Void> handleBindUnbind(String gatewayId,
                                        Flux<String> childId,
                                        Function3<ProtocolSupport, DeviceOperator, Flux<DeviceOperator>, Mono<Void>> operator) {
        return registry
            .getDevice(gatewayId)
            .flatMap(gateway -> gateway
                .getProtocol()
                .flatMap(protocol -> operator.apply(protocol, gateway, childId.flatMap(registry::getDevice)))
            );
    }


}
