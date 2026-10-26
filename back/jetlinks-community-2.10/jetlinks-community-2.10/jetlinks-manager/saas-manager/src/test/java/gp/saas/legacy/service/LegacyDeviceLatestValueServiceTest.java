package gp.saas.legacy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyDeviceLatestValueServiceTest {

    @Test
    void shouldReadRequestedPropertiesWithOneHmget() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveHashOperations<String, String, String> hash = mock(ReactiveHashOperations.class);
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        when(hash.multiGet(
            eq("device:device-1:latest"),
            eq(Arrays.asList("property:p2", "property:p1", "property:broken"))))
            .thenReturn(Mono.just(Arrays.asList(
                "{\"v\":2,\"ts\":200}",
                "{\"v\":1,\"ts\":100}",
                "not-json")));

        LegacyDeviceLatestValueService service = new LegacyDeviceLatestValueService(redis, new ObjectMapper());

        Map<String, LegacyDeviceLatestValueService.LatestValue> values = service
            .readProperties("device-1", Arrays.asList("p2", "p1", "p2", "broken"))
            .block();

        assertThat(values).containsOnlyKeys("p2", "p1", "broken");
        assertThat(values.get("p2").getValue()).isEqualTo(2);
        assertThat(values.get("p2").getTimestamp()).isEqualTo(200);
        assertThat(values.get("broken").getTimestamp()).isZero();
        verify(hash).multiGet(
            "device:device-1:latest",
            Arrays.asList("property:p2", "property:p1", "property:broken"));
    }

    @Test
    void shouldTreatRedisFailureAsAllPropertiesMissing() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveHashOperations<String, String, String> hash = mock(ReactiveHashOperations.class);
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        when(hash.multiGet(eq("device:device-1:latest"), eq(List.of("property:p1"))))
            .thenReturn(Mono.error(new IllegalStateException("redis unavailable")));

        LegacyDeviceLatestValueService service = new LegacyDeviceLatestValueService(redis, new ObjectMapper());

        assertThat(service.readProperties("device-1", List.of("p1")).block()).isEmpty();
    }
}
