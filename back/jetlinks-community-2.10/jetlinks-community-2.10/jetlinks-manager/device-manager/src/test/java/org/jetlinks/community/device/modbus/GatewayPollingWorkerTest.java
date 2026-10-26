package org.jetlinks.community.device.modbus;

import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.event.EventBus;
import org.jetlinks.core.message.ReadPropertyMessageSender;
import org.jetlinks.core.message.property.ReadPropertyMessageReply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GatewayPollingWorkerTest {

    private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
    private final long epoch = System.currentTimeMillis() + 60000;
    private final ModbusPollingLeaseService leases = mock(ModbusPollingLeaseService.class);
    private final ModbusPollPlanResolver resolver = mock(ModbusPollPlanResolver.class);
    private final DeviceRegistry registry = mock(DeviceRegistry.class);
    private final EventBus eventBus = mock(EventBus.class);
    private final List<GatewayPollingWorker> workers = new ArrayList<>();
    private final List<String> requests = new ArrayList<>();

    GatewayPollingWorkerTest() {
        when(leases.acquire(anyString(), anyString(), any())).thenReturn(Mono.just(true));
        when(leases.renew(anyString(), anyString(), any())).thenReturn(Mono.just(true));
        when(leases.release(anyString(), anyString())).thenReturn(Mono.just(true));
        doReturn(Mono.empty()).when(eventBus).publish(anyString(), anyString());
    }

    @AfterEach
    void cleanup() {
        workers.forEach(GatewayPollingWorker::shutdownLocally);
        scheduler.dispose();
    }

    @Test
    void firstRoundRunsAsSoonAsAllPlansAreReady() {
        ModbusPollPlan plan = plan("product-a", "d1");
        Sinks.Empty<Void> ready = Sinks.empty();
        when(resolver.resolve("gateway"))
                .thenReturn(Flux.just(plan).concatWith(ready.asMono().thenMany(Flux.empty())));
        device("d1", Flux.empty());
        GatewayPollingWorker worker = worker();
        AtomicReference<Boolean> started = new AtomicReference<>();
        worker.start().subscribe(started::set);

        advance(Duration.ofSeconds(2));
        assertTrue(requests.isEmpty(), "must not poll a partially loaded set of plans");
        ready.tryEmitEmpty();
        advance(Duration.ZERO);

        assertEquals(Boolean.TRUE, started.get());
        assertEquals(Collections.singletonList("d1@2000"), requests);
    }

    @Test
    void ownershipIsRenewedWhilePlansAreStillLoading() {
        Sinks.Empty<Void> ready = Sinks.empty();
        when(resolver.resolve("gateway"))
                .thenReturn(Flux.just(plan("product-a", "d1")).concatWith(ready.asMono().thenMany(Flux.empty())));
        device("d1", Flux.empty());
        GatewayPollingWorker worker = worker();
        AtomicReference<Boolean> started = new AtomicReference<>();
        worker.start().subscribe(started::set);

        advance(Duration.ofSeconds(30));
        assertTrue(requests.isEmpty());
        verify(leases, times(6)).renew(eq("gateway"), anyString(), any());
        ready.tryEmitEmpty();
        advance(Duration.ZERO);

        assertEquals(Boolean.TRUE, started.get());
        assertEquals(Collections.singletonList("d1@30000"), requests);
    }

    @Test
    void fiveMinuteDelayStartsAfterAllProductsAndDevicesFinish() {
        ModbusPollPlan first = plan("product-a", "d1", "d2");
        ModbusPollPlan second = plan("product-b", "d3", "d4");
        when(resolver.resolve("gateway")).thenReturn(Flux.just(first, second));
        device("d1", Flux.empty());
        device("d2", Flux.empty());
        device("d3", Flux.empty());
        device("d4", Mono.delay(Duration.ofSeconds(7), scheduler).thenMany(Flux.empty()));
        assertTrue(worker().start().block());
        advance(Duration.ZERO);

        assertEquals(Arrays.asList("d1@0", "d2@0", "d3@0", "d4@0"), requests);
        assertEquals(Long.MAX_VALUE, first.getNextFireTime());
        advance(Duration.ofSeconds(7));
        assertEquals(epoch + 307000, first.getNextFireTime());
        assertEquals(first.getNextFireTime(), second.getNextFireTime());

        advance(Duration.ofMillis(299999));
        assertEquals(4, requests.size(), "earlier products must not restart before the whole-round delay expires");
        advance(Duration.ofMillis(1));
        assertEquals(Arrays.asList("d1@0", "d2@0", "d3@0", "d4@0",
                                   "d1@307000", "d2@307000", "d3@307000", "d4@307000"), requests);
    }

    @Test
    void offlineCancelsActiveAndQueuedReadsAndReconnectStartsImmediately() {
        when(resolver.resolve("gateway")).thenAnswer(ignore -> Flux.just(plan("product-a", "d1", "d2")));
        AtomicBoolean cancelled = new AtomicBoolean();
        device("d1", Flux.<ReadPropertyMessageReply>never().doOnCancel(() -> cancelled.set(true)));
        device("d2", Flux.empty());
        GatewayPollingWorker original = worker();
        original.start().block();
        advance(Duration.ZERO);
        assertEquals(Collections.singletonList("d1@0"), requests);

        original.stop().block();
        assertTrue(cancelled.get());
        assertFalse(original.isLeaseValid());
        advance(Duration.ofMinutes(10));
        assertEquals(Collections.singletonList("d1@0"), requests);
        verify(eventBus).publish(eq(ModbusPollingCoordinator.LEASE_LOST_TOPIC), startsWith("gateway|"));

        device("d1", Flux.empty());
        worker().start().block();
        advance(Duration.ZERO);
        assertEquals(Arrays.asList("d1@0", "d1@600000", "d2@600000"), requests);
    }

    @Test
    void aSlowRoundNeverOverlapsItself() {
        Sinks.Empty<Void> response = Sinks.empty();
        ModbusPollPlan plan = plan("product-a", "d1", "d2");
        when(resolver.resolve("gateway")).thenReturn(Flux.just(plan));
        device("d1", response.asMono().thenMany(Flux.empty()));
        device("d2", Flux.empty());
        worker().start().block();
        advance(Duration.ZERO);
        advance(Duration.ofMinutes(6));
        assertEquals(Collections.singletonList("d1@0"), requests);

        response.tryEmitEmpty();
        assertEquals(Arrays.asList("d1@0", "d2@360000"), requests);
        assertEquals(epoch + 660000, plan.getNextFireTime());
    }

    @Test
    void offlineDuringPlanLoadingCannotStartALatePollingLoop() {
        Sinks.Empty<Void> ready = Sinks.empty();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(resolver.resolve("gateway"))
                .thenReturn(Flux.just(plan("product-a", "d1"))
                        .concatWith(ready.asMono().thenMany(Flux.empty()))
                        .doOnCancel(() -> cancelled.set(true)));
        device("d1", Flux.empty());
        GatewayPollingWorker worker = worker();
        AtomicReference<Boolean> started = new AtomicReference<>();
        worker.start().subscribe(started::set);
        worker.stop().block();
        assertTrue(cancelled.get(), "stopping must cancel the pending plan query");
        ready.tryEmitEmpty();
        advance(Duration.ofMinutes(6));

        assertEquals(Boolean.FALSE, started.get());
        assertTrue(requests.isEmpty());
        verify(leases, never()).renew(anyString(), anyString(), any());
    }

    @Test
    void offlineDuringLeaseAcquisitionReleasesTheLateLeaseWithoutPolling() {
        Sinks.One<Boolean> acquired = Sinks.one();
        when(leases.acquire(anyString(), anyString(), any())).thenReturn(acquired.asMono());
        GatewayPollingWorker worker = worker();
        AtomicReference<Boolean> started = new AtomicReference<>();
        AtomicBoolean stopped = new AtomicBoolean();
        worker.start().subscribe(started::set);
        worker.stop().subscribe(null, error -> fail(error), () -> stopped.set(true));
        assertFalse(stopped.get(), "reconnect must wait for the in-flight acquisition outcome");
        verify(leases, never()).release(anyString(), anyString());
        acquired.tryEmitValue(true);
        advance(Duration.ofMinutes(6));

        assertEquals(Boolean.FALSE, started.get());
        assertTrue(stopped.get());
        assertTrue(requests.isEmpty());
        verifyNoInteractions(resolver);
        verify(leases).release(eq("gateway"), anyString());
    }

    @Test
    void shutdownDuringAcquisitionDoesNotReleaseOrNotifyAfterTheLateResult() {
        Sinks.One<Boolean> acquired = Sinks.one();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(leases.acquire(anyString(), anyString(), any()))
                .thenReturn(acquired.asMono().doOnCancel(() -> cancelled.set(true)));
        GatewayPollingWorker worker = worker();
        AtomicReference<Boolean> started = new AtomicReference<>();
        worker.start().subscribe(started::set);

        worker.shutdownLocally();
        assertTrue(cancelled.get());
        acquired.tryEmitValue(true);
        worker.stop().block();
        advance(Duration.ofMinutes(1));

        assertEquals(Boolean.FALSE, started.get());
        verify(leases, never()).release(anyString(), anyString());
        verify(leases, never()).renew(anyString(), anyString(), any());
        verifyNoInteractions(resolver, registry, eventBus);
    }

    @Test
    void shutdownCancelsAnEarlierStopBarrier() {
        Sinks.One<Boolean> acquired = Sinks.one();
        when(leases.acquire(anyString(), anyString(), any())).thenReturn(acquired.asMono());
        GatewayPollingWorker worker = worker();
        worker.start().subscribe();
        AtomicBoolean stopped = new AtomicBoolean();
        worker.stop().subscribe(null, error -> fail(error), () -> stopped.set(true));
        assertFalse(stopped.get());
        clearInvocations(eventBus);

        worker.shutdownLocally();
        assertTrue(stopped.get(), "local shutdown must not retain an unresolved stop barrier");
        acquired.tryEmitValue(true);
        verify(leases, never()).release(anyString(), anyString());
        verifyNoInteractions(eventBus);
    }

    @Test
    void cancellingStartupCancelsTheQueryAndReleasesTheLease() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(resolver.resolve("gateway"))
                .thenReturn(Flux.<ModbusPollPlan>never().doOnCancel(() -> cancelled.set(true)));
        GatewayPollingWorker worker = worker();
        reactor.core.Disposable starting = worker.start().subscribe();

        starting.dispose();
        advance(Duration.ofMinutes(1));

        assertTrue(cancelled.get());
        assertFalse(worker.isLeaseValid());
        verify(leases).release(eq("gateway"), anyString());
        verify(leases, never()).renew(anyString(), anyString(), any());
    }

    @Test
    void losingTheLeaseDuringStartupStillCompletesItsRelease() {
        Sinks.Empty<Void> ready = Sinks.empty();
        when(resolver.resolve("gateway"))
                .thenReturn(ready.asMono().thenMany(Flux.empty()));
        when(leases.renew(anyString(), anyString(), any())).thenReturn(Mono.just(false));
        GatewayPollingWorker worker = worker();
        AtomicReference<Boolean> started = new AtomicReference<>();
        worker.start().subscribe(started::set);
        advance(Duration.ofSeconds(5));

        ready.tryEmitEmpty();
        advance(Duration.ofMinutes(1));

        assertEquals(Boolean.FALSE, started.get());
        verify(leases).release(eq("gateway"), anyString());
        verify(leases).renew(eq("gateway"), anyString(), any());
        assertFalse(worker.isLeaseValid());
    }

    @Test
    void unchangedPlanReloadDoesNotRestartTheFirstRoundOrResetItsDelay() {
        when(resolver.resolve("gateway")).thenAnswer(ignore -> Flux.just(plan("product-a", "d1")));
        device("d1", Flux.empty());
        GatewayPollingWorker worker = worker();
        worker.start().block();
        advance(Duration.ZERO);
        advance(Duration.ofSeconds(1));
        worker.reloadPlans().block();
        advance(Duration.ZERO);
        assertEquals(Collections.singletonList("d1@0"), requests);

        advance(Duration.ofMillis(298999));
        assertEquals(1, requests.size());
        advance(Duration.ofMillis(1));
        assertEquals(Arrays.asList("d1@0", "d1@300000"), requests);
    }

    @Test
    void aNonOwnerDoesNotPoll() {
        when(leases.acquire(anyString(), anyString(), any())).thenReturn(Mono.just(false));
        assertFalse(worker().start().block());
        advance(Duration.ofMinutes(6));
        verifyNoInteractions(registry, resolver);
    }

    private GatewayPollingWorker worker() {
        GatewayPollingWorker worker = new GatewayPollingWorker(
                "gateway", "node-a", leases, resolver, registry, mock(ModbusPollReportPublisher.class),
                eventBus, scheduler, () -> epoch + scheduler.now(TimeUnit.MILLISECONDS));
        workers.add(worker);
        return worker;
    }

    private ModbusPollPlan plan(String id, String... devices) {
        return new ModbusPollPlan(id, ModbusPollPlan.ScheduleType.FIXED_DELAY, 300000, null,
                                  0, 0, 0, Collections.singletonList("_dev_status"), Arrays.asList(devices));
    }

    private void device(String id, Flux<ReadPropertyMessageReply> replies) {
        DeviceOperator device = mock(DeviceOperator.class, RETURNS_DEEP_STUBS);
        ReadPropertyMessageSender sender = mock(ReadPropertyMessageSender.class, RETURNS_SELF);
        when(device.messageSender().readProperty()).thenReturn(sender);
        when(registry.getDevice(id)).thenReturn(Mono.just(device));
        when(sender.send()).thenReturn(Flux.defer(() -> {
            requests.add(id + "@" + scheduler.now(TimeUnit.MILLISECONDS));
            return replies;
        }));
    }

    private void advance(Duration duration) {
        scheduler.advanceTimeBy(duration);
    }
}
