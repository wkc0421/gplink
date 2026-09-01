package org.jetlinks.community.standalone;

import org.jetlinks.community.device.service.data.DeviceDataStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsConfigurationTest {

    @Test
    void shouldUseFiveMinuteCollectionAndThirtyDayRetention() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"))
            .forEach(sources::addLast);
        PropertySourcesPropertyResolver properties = new PropertySourcesPropertyResolver(sources);

        assertThat(properties.getProperty("micrometer.time-series.metrics.default.step"))
            .isEqualTo("5m");
        assertThat(properties.getProperty("monitor.system.collector.interval"))
            .isEqualTo("5m");
        assertThat(new DeviceDataStorageProperties().getRedisLatest().getTtlHours())
            .isEqualTo(24);

        Map<String, String> retentionPolicies = new LinkedHashMap<>();
        for (int index = 0; ; index++) {
            String prefix = "timescaledb.time-series.retention-policies[" + index + "]";
            String table = properties.getProperty(prefix + ".table");
            if (table == null) {
                break;
            }
            retentionPolicies.put(table, properties.getProperty(prefix + ".interval"));
        }

        assertThat(retentionPolicies).containsExactly(
            Map.entry("device_session_metric", "30d"),
            Map.entry("device_metrics", "30d"),
            Map.entry("device_gateway_monitor", "30d"),
            Map.entry("system_monitor", "30d"),
            Map.entry("redis_latest", "30d")
        );
    }
}
