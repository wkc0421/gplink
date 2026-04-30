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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.community.device.entity.DeviceLatestData;
import org.jetlinks.community.gateway.DeviceMessageUtils;
import org.jetlinks.community.gateway.annotation.Subscribe;
import org.jetlinks.community.things.ThingsDataRepository;
import org.jetlinks.community.things.data.ThingPropertyDetail;
import org.jetlinks.community.timeseries.query.AggregationColumn;
import org.jetlinks.core.device.DeviceThingType;
import org.jetlinks.core.event.Subscription;
import org.jetlinks.core.message.DeviceMessage;
import org.jetlinks.core.message.HeaderKey;
import org.jetlinks.core.metadata.DeviceMetadata;
import org.springframework.beans.factory.DisposableBean;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis based latest device property data service.
 */
public class RedisDeviceLatestDataService implements DeviceLatestDataService, DisposableBean {

    private static final String OVERFLOW_DROP_NEWEST = "drop-newest";

    public static final HeaderKey<Boolean> ignoreCache = HeaderKey.of("ignoreCache", false, Boolean.class);

    private final RedisDeviceLatestService redisService;
    private final ThingsDataRepository thingsDataRepository;
    private final Sinks.Many<WriteTask> writeSink;
    private final Sinks.Many<WriteTask> backfillSink;
    private final Disposable writeDisposable;
    private final Disposable backfillDisposable;
    private final Counter queueDropCounter;

    public RedisDeviceLatestDataService(RedisDeviceLatestService redisService,
                                        ThingsDataRepository thingsDataRepository,
                                        int queueSize,
                                        int workerConcurrency,
                                        int backfillConcurrency,
                                        String overflowStrategy,
                                        MeterRegistry meterRegistry) {
        this.redisService = redisService;
        this.thingsDataRepository = thingsDataRepository;
        String resolvedOverflowStrategy = resolveOverflowStrategy(overflowStrategy);
        this.writeSink = createSink(queueSize, resolvedOverflowStrategy);
        this.backfillSink = createSink(queueSize, resolvedOverflowStrategy);
        this.queueDropCounter = Counter.builder("redis.latest.queue.drop")
                                       .description("Redis latest queue drop count")
                                       .register(meterRegistry);
        this.writeDisposable = writeSink
            .asFlux()
            .publishOn(Schedulers.parallel())
            .flatMap(task -> redisService.writeProperties(task.deviceId, task.properties, task.timestamp),
                     Math.max(workerConcurrency, 1))
            .subscribe();
        this.backfillDisposable = backfillSink
            .asFlux()
            .publishOn(Schedulers.parallel())
            .flatMap(task -> redisService.writeProperties(task.deviceId, task.properties, task.timestamp),
                     Math.max(backfillConcurrency, 1))
            .subscribe();
    }

    @Override
    @Subscribe(topics = "/device/**", features = Subscription.Feature.local)
    public Mono<Void> saveAsync(DeviceMessage message) {
        if (message.getHeaderOrDefault(ignoreCache)) {
            return Mono.empty();
        }
        Map<String, Object> properties = DeviceMessageUtils
            .tryGetProperties(message)
            .orElse(null);
        if (properties == null || properties.isEmpty()) {
            return Mono.empty();
        }

        enqueue(writeSink, new WriteTask(
            message.getDeviceId(),
            new LinkedHashMap<>(properties),
            message.getTimestamp()));
        return Mono.empty();
    }

    @Override
    public void save(DeviceMessage message) {
        saveAsync(message).subscribe();
    }

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
                return fallbackFromTimescaleDB(deviceId)
                    .switchIfEmpty(Mono.empty());
            });
    }

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

                    Map<String, Object> backfill = new LinkedHashMap<>(properties.size());
                    long timestamp = 0L;
                    for (ThingPropertyDetail prop : properties) {
                        data.put(prop.getProperty(), prop.getValue());
                        backfill.put(prop.getProperty(), prop.getValue() != null ? prop.getValue() : "");
                        timestamp = Math.max(timestamp, prop.getTimestamp());
                    }
                    enqueue(backfillSink, new WriteTask(deviceId, backfill, timestamp));
                    return Mono.just(data);
                })
            )
            .onErrorResume(e -> Mono.empty());
    }

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

    @Override
    public Mono<Void> upgradeMetadata(String productId, DeviceMetadata metadata) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> reloadMetadata(String productId, DeviceMetadata metadata) {
        return Mono.empty();
    }

    @Override
    public void destroy() {
        writeDisposable.dispose();
        backfillDisposable.dispose();
    }

    private void enqueue(Sinks.Many<WriteTask> sink, WriteTask task) {
        Sinks.EmitResult result;
        synchronized (sink) {
            result = sink.tryEmitNext(task);
        }
        if (result.isFailure()) {
            queueDropCounter.increment();
        }
    }

    private static Sinks.Many<WriteTask> createSink(int queueSize, String overflowStrategy) {
        if (OVERFLOW_DROP_NEWEST.equals(overflowStrategy)) {
            return Sinks.many().multicast().onBackpressureBuffer(Math.max(queueSize, 1), false);
        }
        throw new IllegalArgumentException("Unsupported Redis latest overflow strategy: " + overflowStrategy);
    }

    private static String resolveOverflowStrategy(String overflowStrategy) {
        String strategy = overflowStrategy == null || overflowStrategy.isBlank()
            ? OVERFLOW_DROP_NEWEST
            : overflowStrategy.trim().toLowerCase();
        if (OVERFLOW_DROP_NEWEST.equals(strategy)) {
            return strategy;
        }
        throw new IllegalArgumentException("Unsupported Redis latest overflow strategy: " + overflowStrategy);
    }

    private static class WriteTask {
        private final String deviceId;
        private final Map<String, Object> properties;
        private final long timestamp;

        private WriteTask(String deviceId, Map<String, Object> properties, long timestamp) {
            this.deviceId = deviceId;
            this.properties = properties;
            this.timestamp = timestamp;
        }
    }
}
