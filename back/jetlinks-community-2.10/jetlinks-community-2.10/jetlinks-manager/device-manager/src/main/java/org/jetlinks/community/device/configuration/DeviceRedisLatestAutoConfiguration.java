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
package org.jetlinks.community.device.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.jetlinks.community.device.service.data.DeviceDataStorageProperties;
import org.jetlinks.community.device.service.data.DeviceLatestDataService;
import org.jetlinks.community.device.service.data.RedisDeviceLatestDataService;
import org.jetlinks.community.device.service.data.RedisDeviceLatestService;
import org.jetlinks.community.micrometer.MeterRegistryManager;
import org.jetlinks.community.things.ThingsDataRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

@AutoConfiguration(after = RedisReactiveAutoConfiguration.class)
@EnableConfigurationProperties(DeviceDataStorageProperties.class)
public class DeviceRedisLatestAutoConfiguration {

    @Bean
    @ConditionalOnBean(ReactiveStringRedisTemplate.class)
    @ConditionalOnMissingBean(RedisDeviceLatestService.class)
    @ConditionalOnProperty(prefix = "jetlinks.device.storage.redis-latest", name = "enabled",
                           havingValue = "true", matchIfMissing = true)
    public RedisDeviceLatestService redisDeviceLatestService(
        ReactiveStringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        MeterRegistryManager meterRegistryManager,
        DeviceDataStorageProperties storageProperties) {
        DeviceDataStorageProperties.RedisLatest redisLatest = storageProperties.getRedisLatest();
        long ttlSeconds = redisLatest.getTtlHours() * 3600;
        MeterRegistry meterRegistry = meterRegistryManager.getMeterRegister("redis-latest");
        return new RedisDeviceLatestService(redisTemplate,
                                            objectMapper,
                                            ttlSeconds,
                                            redisLatest.getWriteTimeoutMs(),
                                            meterRegistry);
    }

    @Bean
    @ConditionalOnBean(RedisDeviceLatestService.class)
    @ConditionalOnProperty(prefix = "jetlinks.device.storage.redis-latest", name = "enabled",
                           havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(DeviceLatestDataService.class)
    public DeviceLatestDataService redisDeviceLatestDataService(
        RedisDeviceLatestService redisService,
        ThingsDataRepository thingsDataRepository,
        MeterRegistryManager meterRegistryManager,
        DeviceDataStorageProperties storageProperties) {
        DeviceDataStorageProperties.RedisLatest redisLatest = storageProperties.getRedisLatest();
        MeterRegistry meterRegistry = meterRegistryManager.getMeterRegister("redis-latest");
        return new RedisDeviceLatestDataService(redisService,
                                                thingsDataRepository,
                                                redisLatest.getQueueSize(),
                                                redisLatest.getWorkerConcurrency(),
                                                redisLatest.getBackfillConcurrency(),
                                                redisLatest.getOverflowStrategy(),
                                                meterRegistry);
    }
}
