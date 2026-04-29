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
package org.jetlinks.community.device.service.data;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.community.device.entity.DeviceLatestData;
import org.jetlinks.community.things.ThingsDataRepository;
import org.jetlinks.community.things.data.ThingPropertyDetail;
import org.jetlinks.community.timeseries.query.AggregationColumn;
import org.jetlinks.core.device.DeviceThingType;
import org.jetlinks.core.event.Subscription;
import org.jetlinks.core.message.DeviceMessage;
import org.jetlinks.core.message.HeaderKey;
import org.jetlinks.core.metadata.DeviceMetadata;
import org.jetlinks.core.things.ThingProperty;
import org.jetlinks.community.gateway.DeviceMessageUtils;
import org.jetlinks.community.gateway.annotation.Subscribe;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 基于 Redis 的设备最新属性数据服务。
 * <p>
 * 写入：订阅设备属性上报消息，通过 {@link RedisDeviceLatestService} 写入 Redis Hash。<br>
 * 读取：{@link #queryDeviceData} 先查 Redis，miss 时回源 TimescaleDB 并回填。
 * <p>
 * 对于 {@link #query} 等需要关系型过滤的方法，当前返回空（与 {@link NonDeviceLatestDataService} 行为一致），
 * 后续可视需求引入 TimescaleDB 聚合层提供支持。
 *
 * @see RedisDeviceLatestService
 */
@Slf4j
public class RedisDeviceLatestDataService implements DeviceLatestDataService {

    public static final HeaderKey<Boolean> ignoreCache = HeaderKey.of("ignoreCache", false, Boolean.class);

    private final RedisDeviceLatestService redisService;
    private final ThingsDataRepository thingsDataRepository;

    public RedisDeviceLatestDataService(RedisDeviceLatestService redisService,
                                        ThingsDataRepository thingsDataRepository) {
        this.redisService = redisService;
        this.thingsDataRepository = thingsDataRepository;
        log.warn("Redis latest data service initialized, subscribe topic=/device/**");
    }

    // -----------------------------------------------------------------------
    // 写入：订阅设备消息，提取属性后写入 Redis
    // -----------------------------------------------------------------------

    @Override
    @Subscribe(topics = "/device/**", features = Subscription.Feature.local)
    public Mono<Void> saveAsync(DeviceMessage message) {
        log.warn("Redis latest subscriber received message: type={}, device={}, timestamp={}, ignoreCache={}",
                 message.getMessageType(), message.getDeviceId(), message.getTimestamp(),
                 message.getHeaderOrDefault(ignoreCache));
        if (message.getHeaderOrDefault(ignoreCache)) {
            log.warn("Redis latest write skipped by ignoreCache: type={}, device={}",
                     message.getMessageType(), message.getDeviceId());
            return Mono.empty();
        }
        Map<String, Object> properties = DeviceMessageUtils
            .tryGetProperties(message)
            .orElse(null);
        if (properties == null || properties.isEmpty()) {
            log.warn("Redis latest write skipped because message has no properties: type={}, device={}, messageClass={}",
                     message.getMessageType(), message.getDeviceId(), message.getClass().getName());
            return Mono.empty();
        }
        long timestamp = message.getTimestamp();
        String deviceId = message.getDeviceId();
        log.warn("Redis latest writing properties: device={}, propertyCount={}, properties={}",
                 deviceId, properties.size(), properties.keySet());

        return Flux
            .fromIterable(properties.entrySet())
            .flatMap(entry -> redisService
                .writeProperty(deviceId, entry.getKey(), entry.getValue(), timestamp)
                .doOnNext(success -> log.warn(
                    "Redis latest write result: device={}, property={}, success={}",
                    deviceId, entry.getKey(), success))
                .onErrorResume(e -> {
                    log.warn("Redis latest saveAsync write failed: device={}, property={}", deviceId, entry.getKey(), e);
                    return Mono.just(false);
                }), 4)
            .then();
    }

    @Override
    public void save(DeviceMessage message) {
        saveAsync(message).subscribe();
    }

    // -----------------------------------------------------------------------
    // 读取：Redis first → miss 回源 TimescaleDB → 回填 Redis
    // -----------------------------------------------------------------------

    /**
     * 查询单个设备最新属性数据。
     * <ol>
     *   <li>Redis HGETALL → 有数据则直接返回</li>
     *   <li>Redis miss → 回源 TimescaleDB queryEachProperty → 回填 Redis</li>
     * </ol>
     */
    @Override
    public Mono<DeviceLatestData> queryDeviceData(String productId, String deviceId) {
        return redisService
            .readAllProperties(deviceId)
            .flatMap(redisMap -> {
                if (!redisMap.isEmpty()) {
                    DeviceLatestData data = new DeviceLatestData(redisMap);
                    data.put("id", deviceId);
                    return Mono.just(data);
                }
                // Redis miss → 回源 TimescaleDB
                return fallbackFromTimescaleDB(deviceId)
                    .switchIfEmpty(Mono.empty());
            });
    }

    /**
     * 回源 TimescaleDB：查询该设备所有属性的最新值，并回填 Redis。
     */
    private Mono<DeviceLatestData> fallbackFromTimescaleDB(String deviceId) {
        return thingsDataRepository
            .opsForThing(DeviceThingType.device.getId(), deviceId)
            .flatMap(ops -> ops
                .forQuery()
                .queryEachProperty(QueryParamEntity.of(), new String[0])
                .collectList()
                .flatMap(properties -> {
                    if (properties.isEmpty()) {
                        return Mono.empty();
                    }
                    DeviceLatestData data = new DeviceLatestData();
                    data.put("id", deviceId);
                    for (ThingPropertyDetail prop : properties) {
                        data.put(prop.getProperty(), prop.getValue());
                    }
                    // 异步回填 Redis，不阻塞当前响应
                    Flux.fromIterable(properties)
                        .flatMap(prop -> redisService.writeProperty(
                            deviceId,
                            prop.getProperty(),
                            prop.getValue() != null ? prop.getValue() : "",
                            prop.getTimestamp()
                        ).onErrorResume(e -> Mono.just(false)))
                        .subscribeOn(Schedulers.parallel())
                        .subscribe(null, err -> log.warn(
                            "Redis latest backfill failed: device={}", deviceId, err));
                    return Mono.just(data);
                })
            )
            .onErrorResume(e -> {
                log.warn("TimescaleDB fallback failed for device={}", deviceId, e);
                return Mono.empty();
            });
    }

    // -----------------------------------------------------------------------
    // 以下方法需要关系型查询能力，当前 Redis 层不支持，返回空（与 NonDeviceLatestDataService 一致）
    // -----------------------------------------------------------------------

    @Override
    public Flux<DeviceLatestData> query(String productId, QueryParamEntity param) {
        return Flux.empty();
    }

    @Override
    public Mono<Integer> count(String productId, QueryParamEntity param) {
        return Mono.empty();
    }

    @Override
    public Mono<Map<String, Object>> aggregation(String productId,
                                                  List<AggregationColumn> columns,
                                                  QueryParamEntity paramEntity) {
        return Mono.empty();
    }

    @Override
    public Flux<Map<String, Object>> aggregation(Flux<QueryProductLatestDataRequest> param, boolean merge) {
        return Flux.empty();
    }

    // -----------------------------------------------------------------------
    // DDL：Redis 不需要 schema 管理
    // -----------------------------------------------------------------------

    @Override
    public Mono<Void> upgradeMetadata(String productId, DeviceMetadata metadata) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> reloadMetadata(String productId, DeviceMetadata metadata) {
        return Mono.empty();
    }
}
