package org.jetlinks.community.device.service.data;

import org.jetlinks.community.things.ThingsDataRepository;
import org.jetlinks.community.things.data.PropertyExtremes;
import org.jetlinks.community.things.data.operations.PropertyExtremesQuery;
import org.jetlinks.community.things.data.operations.QueryOperations;
import org.jetlinks.community.things.data.operations.ThingOperations;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeviceExtremesBatchTest {
    @Test
    void mergesSameStorageButNeverCrossesDifferentStorageKeys() {
        ThingsDataRepository repository = mock(ThingsDataRepository.class);
        List<List<PropertyExtremes.Target>> calls = new ArrayList<>();
        PropertyExtremesQuery shared = query("shared", calls);
        resolve(repository, "a", Optional.of(shared));
        resolve(repository, "b", Optional.of(query("shared", calls)));
        resolve(repository, "c", Optional.of(query("separate", calls)));
        resolve(repository, "d", Optional.empty());
        var targets = List.of(target("a", "p"), target("b", "q"), target("c", "p"), target("d", "p"));
        var result = new ThingsBridgingDeviceDataService(repository)
            .queryPropertyExtremes(targets, 1, 2).collectList().block();
        assertThat(calls).containsExactly(List.of(targets.get(0), targets.get(1)), List.of(targets.get(2)));
        assertThat(result).hasSize(4);
        assertThat(result).filteredOn(row -> row.thingId().equals("d"))
            .extracting(PropertyExtremes::status).containsExactly(PropertyExtremes.Status.UNSUPPORTED_STORAGE);
        verify(repository, times(4)).opsForThing(eq("device"), anyString());
    }

    @Test
    void splitsThousandCellBatchesAndLimitsConcurrencyAcrossAllTables() {
        ThingsDataRepository repository = mock(ThingsDataRepository.class);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Integer> sizes = new ArrayList<>();
        List<PropertyExtremes.Target> targets = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String device = "d" + i;
            resolve(repository, device, Optional.of(new PropertyExtremesQuery() {
                public Object storageKey() { return device; }
                public Flux<PropertyExtremes> query(List<PropertyExtremes.Target> batch, long start, long end) {
                    return Flux.defer(() -> {
                        sizes.add(batch.size());
                        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                        return Mono.delay(Duration.ofSeconds(1)).thenMany(Flux.fromIterable(batch))
                            .map(target -> PropertyExtremes.empty(target, PropertyExtremes.Status.NO_DATA))
                            .doOnComplete(active::decrementAndGet);
                    });
                }
            }));
            for (int j = 0; j < 1001; j++) targets.add(target(device, "p" + j));
        }
        StepVerifier.withVirtualTime(() -> new ThingsBridgingDeviceDataService(repository)
            .queryPropertyExtremes(targets, 1, 2).collectList())
            .thenAwait(Duration.ofSeconds(5))
            .assertNext(rows -> assertThat(rows).hasSize(8008)).verifyComplete();
        assertThat(sizes).hasSize(16).allMatch(size -> size <= 1000);
        assertThat(peak.get()).isEqualTo(5);
        assertThat(active.get()).isZero();
    }

    private static PropertyExtremes.Target target(String device, String property) {
        return new PropertyExtremes.Target(device, property);
    }

    private static PropertyExtremesQuery query(String key, List<List<PropertyExtremes.Target>> calls) {
        return new PropertyExtremesQuery() {
            public Object storageKey() { return key; }
            public Flux<PropertyExtremes> query(List<PropertyExtremes.Target> targets, long start, long end) {
                calls.add(targets);
                return Flux.fromIterable(targets).map(target -> PropertyExtremes.empty(target, PropertyExtremes.Status.NO_DATA));
            }
        };
    }

    private static void resolve(ThingsDataRepository repository, String device, Optional<PropertyExtremesQuery> query) {
        ThingOperations ops = mock(ThingOperations.class);
        QueryOperations queries = mock(QueryOperations.class);
        when(repository.opsForThing("device", device)).thenReturn(Mono.just(ops));
        when(ops.forQuery()).thenReturn(queries);
        when(queries.propertyExtremesQuery()).thenReturn(query);
    }
}
