package org.jetlinks.community.saas.changeproperty.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.core.event.EventBus;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ChangePropertyConfigServiceTest {

    private static final String CONFIG_INDEX_KEY = "gplink:device:change:config:index";
    private static final String LATEST_KEY = "gplink:device:change:latest";
    private static final String LAST_KEY = "gplink:device:change:last";

    private final ReactiveRedisOperations<String, String> redis = mock(ReactiveRedisOperations.class);
    private final ReactiveHashOperations<String, String, String> hash = mock(ReactiveHashOperations.class);
    private ChangePropertyConfigService service;

    @BeforeEach
    void setUp() {
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        service = new ChangePropertyConfigService(
            mock(LocalDeviceInstanceService.class),
            mock(LocalDeviceProductService.class),
            mock(DeviceDataService.class),
            mock(EventBus.class),
            mock(ChangePropertyMqttPublisher.class),
            redis,
            new ObjectMapper());
        ReflectionTestUtils.setField(service, "monitorScanCount", 2);
    }

    @Test
    void shouldFinishWhenNoConfigExists() {
        when(hash.keys(CONFIG_INDEX_KEY)).thenReturn(Flux.empty());
        when(hash.scan(eq(LATEST_KEY), any(ScanOptions.class))).thenReturn(Flux.never());

        StepVerifier
            .create(service.monitorOnce())
            .assertNext(result -> assertEquals(Map.of(
                "latestCount", 0,
                "configCount", 0,
                "initializedCount", 0,
                "missingCount", 0,
                "changedCount", 0,
                "failedDevices", 0), result))
            .verifyComplete();

        verify(hash, never()).scan(eq(LATEST_KEY), any(ScanOptions.class));
    }

    @Test
    void shouldProcessConfiguredPairsInFiniteBatches() {
        List<String> pairs = List.of(
            "device-1|p1",
            "device-1|p2",
            "device-2|p1");
        when(hash.keys(CONFIG_INDEX_KEY)).thenReturn(Flux.fromIterable(pairs));
        when(hash.scan(eq(LATEST_KEY), any(ScanOptions.class))).thenReturn(Flux.never());
        when(hash.multiGet(eq(CONFIG_INDEX_KEY), any())).thenAnswer(invocation ->
            Mono.just(repeat("{}", invocation.<Collection<String>>getArgument(1).size())));
        when(hash.multiGet(eq(LATEST_KEY), any())).thenAnswer(invocation ->
            Mono.just(repeat(
                "{\"timestamp\":100,\"value\":\"1\"}",
                invocation.<Collection<String>>getArgument(1).size())));
        when(hash.multiGet(eq(LAST_KEY), any())).thenAnswer(invocation ->
            Mono.just(nullValues(invocation.<Collection<String>>getArgument(1).size())));
        when(hash.putAll(eq(LAST_KEY), anyMap())).thenReturn(Mono.just(true));

        StepVerifier
            .create(service.monitorOnce())
            .assertNext(result -> assertEquals(Map.of(
                "latestCount", 3,
                "configCount", 3,
                "initializedCount", 3,
                "missingCount", 0,
                "changedCount", 0,
                "failedDevices", 0), result))
            .verifyComplete();

        ArgumentCaptor<Collection<String>> batches = ArgumentCaptor.forClass(Collection.class);
        verify(hash, org.mockito.Mockito.times(2)).multiGet(eq(CONFIG_INDEX_KEY), batches.capture());
        assertEquals(List.of(2, 1), batches
            .getAllValues()
            .stream()
            .map(Collection::size)
            .toList());
        verify(hash, never()).scan(eq(LATEST_KEY), any(ScanOptions.class));
    }

    private static List<String> repeat(String value, int count) {
        return new ArrayList<>(Collections.nCopies(count, value));
    }

    private static List<String> nullValues(int count) {
        return new ArrayList<>(Collections.nCopies(count, null));
    }
}
