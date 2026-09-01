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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.community.things.ThingsDataRepository;
import org.jetlinks.community.things.data.ThingPropertyDetail;
import org.jetlinks.community.things.data.operations.QueryOperations;
import org.jetlinks.community.things.data.operations.ThingOperations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisDeviceLatestRegressionTest {

    private RedisDeviceLatestDataService latestDataService;

    @AfterEach
    void tearDown() {
        if (latestDataService != null) {
            latestDataService.destroy();
        }
    }

    @Test
    void shouldWriteReadAndRefreshTwentyFourHourTtl() {
        ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveHashOperations<String, String, String> hashOperations =
            mock(ReactiveHashOperations.class);
        when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOperations);

        String key = "device:device-1:latest";
        when(hashOperations.putAll(eq(key), anyMap())).thenReturn(Mono.just(true));
        when(redisTemplate.expire(key, Duration.ofHours(24))).thenReturn(Mono.just(true));
        when(hashOperations.entries(key)).thenReturn(Flux.just(
            Map.entry("property:temperature", "{\"v\":26.5,\"ts\":1000}")
        ));

        RedisDeviceLatestService redisService = new RedisDeviceLatestService(
            redisTemplate,
            new ObjectMapper(),
            Duration.ofHours(24).toSeconds(),
            3000,
            new SimpleMeterRegistry()
        );

        StepVerifier.create(redisService.writeProperty("device-1", "temperature", 26.5, 1000))
            .expectNext(true)
            .verifyComplete();
        StepVerifier.create(redisService.readAllProperties("device-1"))
            .assertNext(properties -> assertThat(properties)
                .containsEntry("temperature", 26.5))
            .verifyComplete();

        verify(redisTemplate).expire(key, Duration.ofHours(24));
    }

    @Test
    void shouldBackfillRedisAfterTimescaleFallback() {
        RedisDeviceLatestService redisService = mock(RedisDeviceLatestService.class);
        ThingsDataRepository repository = mock(ThingsDataRepository.class);
        ThingOperations thingOperations = mock(ThingOperations.class);
        QueryOperations queryOperations = mock(QueryOperations.class);

        when(redisService.readAllProperties("device-2"))
            .thenReturn(Mono.just(Collections.emptyMap()));
        when(repository.opsForThing("device", "device-2"))
            .thenReturn(Mono.just(thingOperations));
        when(thingOperations.forQuery()).thenReturn(queryOperations);

        ThingPropertyDetail temperature = new ThingPropertyDetail();
        temperature.setProperty("temperature");
        temperature.setValue(27);
        temperature.setTimestamp(2000);
        when(queryOperations.queryEachProperty(any(QueryParamEntity.class), any(String[].class)))
            .thenReturn(Flux.just(temperature));
        when(redisService.writeProperties(eq("device-2"), anyMap(), eq(2000L)))
            .thenReturn(Mono.just(true));

        latestDataService = new RedisDeviceLatestDataService(
            redisService,
            repository,
            16,
            1,
            1,
            "drop-newest",
            new SimpleMeterRegistry()
        );

        StepVerifier.create(latestDataService.queryDeviceData("product-1", "device-2"))
            .assertNext(data -> assertThat(data)
                .containsEntry("id", "device-2")
                .containsEntry("temperature", 27))
            .verifyComplete();

        verify(redisService, timeout(3000)).writeProperties(
            eq("device-2"),
            eq(Map.of("temperature", 27)),
            eq(2000L)
        );
    }
}
