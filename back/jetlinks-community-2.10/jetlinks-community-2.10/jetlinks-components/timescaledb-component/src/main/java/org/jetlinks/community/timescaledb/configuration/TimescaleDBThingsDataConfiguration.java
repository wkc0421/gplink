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
package org.jetlinks.community.timescaledb.configuration;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.hswebframework.ezorm.rdb.executor.reactive.ReactiveSqlExecutor;
import org.jetlinks.core.things.ThingsRegistry;
import org.jetlinks.community.things.data.ThingsDataRepositoryStrategy;
import org.jetlinks.community.timescaledb.TimescaleDBOperations;
import org.jetlinks.community.timescaledb.thing.CaggMonitorService;
import org.jetlinks.community.timescaledb.thing.RawRetentionStartupReconciler;
import org.jetlinks.community.timescaledb.thing.TimescaleDBColumnModeStrategy;
import org.jetlinks.community.timescaledb.thing.TimescaleDBRowModeStrategy;
import org.jetlinks.community.timescaledb.thing.TimescaleDBThingsDataProperties;
import org.jetlinks.community.timescaledb.thing.counter.CounterSnapshotService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = TimescaleDBConfiguration.class)
@ConditionalOnBean({TimescaleDBOperations.class, ThingsRegistry.class})
@ConditionalOnClass(ThingsDataRepositoryStrategy.class)
@ConditionalOnProperty(prefix = "timescaledb.things-data", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TimescaleDBThingsDataProperties.class)
public class TimescaleDBThingsDataConfiguration {

    @Bean(initMethod = "init")
    public RawRetentionStartupReconciler rawRetentionStartupReconciler(
        TimescaleDBOperations operations, TimescaleDBThingsDataProperties properties,
        @Value("${jetlinks.device.storage.metric.property-prefix:properties_}") String propertyPrefix) {
        return new RawRetentionStartupReconciler(operations, properties, propertyPrefix);
    }

    @Bean(initMethod = "init", destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "timescaledb.things-data.counter-snapshot",
        name = "enabled", havingValue = "true", matchIfMissing = true)
    public CounterSnapshotService counterSnapshotService(TimescaleDBOperations operations,
                                                         TimescaleDBThingsDataProperties properties,
                                                         ObjectProvider<MeterRegistry> metrics) {
        return new CounterSnapshotService(operations, properties, metrics.getIfAvailable());
    }


    @Bean
    public TimescaleDBRowModeStrategy timescaleDBRowModeStrategy(ThingsRegistry registry,
                                                                 TimescaleDBOperations timescaleDBOperations,
                                                                 TimescaleDBThingsDataProperties properties) {
        return new TimescaleDBRowModeStrategy(registry, timescaleDBOperations, properties);
    }

    @Bean
    public TimescaleDBColumnModeStrategy timescaleDBColumnModeStrategy(ThingsRegistry registry,
                                                                       TimescaleDBOperations timescaleDBOperations,
                                                                       TimescaleDBThingsDataProperties properties) {
        return new TimescaleDBColumnModeStrategy(registry, timescaleDBOperations, properties);
    }

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(
        prefix = "timescaledb.things-data.cagg",
        name = "enabled", havingValue = "true")
    public CaggMonitorService caggMonitorService(TimescaleDBOperations operations,
                                                 MeterRegistry meterRegistry) {
        return new CaggMonitorService(operations.database().getMetadata().getFeatureNow(ReactiveSqlExecutor.ID),
                                      meterRegistry);
    }

}
