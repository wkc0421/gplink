package org.jetlinks.community.device.modbus;

import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.device.session.DeviceSessionEvent;
import org.jetlinks.core.device.session.DeviceSessionManager;
import org.jetlinks.core.event.EventBus;
import org.jetlinks.core.server.session.DeviceSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModbusPollingCoordinatorTest {

    private final DeviceSessionManager sessions = mock(DeviceSessionManager.class);
    private final ModbusPollingLeaseService leases = mock(ModbusPollingLeaseService.class);
    private final ModbusPollPlanResolver resolver = mock(ModbusPollPlanResolver.class);
    private final DeviceRegistry registry = mock(DeviceRegistry.class);
    private final EventBus eventBus = mock(EventBus.class);
    private final DeviceSession session = mock(DeviceSession.class);
    private final AtomicReference<Function<DeviceSessionEvent, Mono<Void>>> listener = new AtomicReference<>();
    private final ModbusPollingCoordinator coordinator;

    ModbusPollingCoordinatorTest() {
        when(sessions.getSessions()).thenReturn(Flux.empty());
        when(sessions.getCurrentServerId()).thenReturn("node-a");
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(0));
            return Disposables.single();
        }).when(sessions).listenEvent(any());
        when(session.getDeviceId()).thenReturn("gateway");
        when(resolver.isModbusGateway("gateway")).thenReturn(Mono.just(true));
        when(resolver.resolve("gateway")).thenReturn(Flux.empty());
        when(registry.getDevice(anyString())).thenReturn(Mono.empty());
        when(leases.acquire(anyString(), anyString(), any())).thenReturn(Mono.just(true));
        when(leases.renew(anyString(), anyString(), any())).thenReturn(Mono.just(true));
        when(leases.release(anyString(), anyString())).thenReturn(Mono.just(true));
        doReturn(Mono.empty()).when(eventBus).publish(anyString(), anyString());
        coordinator = new ModbusPollingCoordinator(sessions, leases, resolver, registry,
                                                    mock(ModbusPollReportPublisher.class), eventBus);
        coordinator.run();
    }

    @AfterEach
    void cleanup() {
        coordinator.destroy();
    }

    @Test
    void sessionLifecycleCreatesAWorkerOnlyWhileOnline() {
        event(DeviceSessionEvent.Type.register).block();
        assertEquals(1, coordinator.getReloadStatus().get("activeGatewayCount"));
        event(DeviceSessionEvent.Type.register).block();
        verify(leases).acquire(eq("gateway"), anyString(), any());

        event(DeviceSessionEvent.Type.unregister).block();
        assertEquals(0, coordinator.getReloadStatus().get("activeGatewayCount"));
        verify(leases).release(eq("gateway"), anyString());

        event(DeviceSessionEvent.Type.register).block();
        assertEquals(1, coordinator.getReloadStatus().get("activeGatewayCount"));
        verify(leases, times(2)).acquire(eq("gateway"), anyString(), any());
    }

    @Test
    void anOfflineEventDuringGatewayLookupPreventsLateStartup() {
        Sinks.One<Boolean> lookup = Sinks.one();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(resolver.isModbusGateway("gateway"))
                .thenReturn(lookup.asMono().doOnCancel(() -> cancelled.set(true)));
        event(DeviceSessionEvent.Type.register).subscribe();
        event(DeviceSessionEvent.Type.unregister).block();
        assertTrue(cancelled.get());
        lookup.tryEmitValue(true);

        assertEquals(0, coordinator.getReloadStatus().get("activeGatewayCount"));
        verify(leases, never()).acquire(anyString(), anyString(), any());
        verify(resolver, never()).resolve(anyString());
    }

    @Test
    void fastReconnectWaitsForThePreviousLeaseRelease() {
        event(DeviceSessionEvent.Type.register).block();
        Sinks.One<Boolean> released = Sinks.one();
        when(leases.release(anyString(), anyString())).thenReturn(released.asMono());
        event(DeviceSessionEvent.Type.unregister).subscribe();
        event(DeviceSessionEvent.Type.register).subscribe();

        verify(leases).acquire(eq("gateway"), anyString(), any());
        released.tryEmitValue(true);
        verify(leases, times(2)).acquire(eq("gateway"), anyString(), any());
        assertEquals(1, coordinator.getReloadStatus().get("activeGatewayCount"));
    }

    @Test
    void repeatedReconnectsKeepTheOldestPendingReleaseBarrier() {
        event(DeviceSessionEvent.Type.register).block();
        Sinks.One<Boolean> released = Sinks.one();
        when(leases.release(anyString(), anyString())).thenReturn(released.asMono());
        event(DeviceSessionEvent.Type.unregister).subscribe();
        event(DeviceSessionEvent.Type.register).subscribe();
        event(DeviceSessionEvent.Type.unregister).subscribe();
        event(DeviceSessionEvent.Type.register).subscribe();

        verify(leases).acquire(eq("gateway"), anyString(), any());
        released.tryEmitValue(true);

        verify(leases, times(2)).acquire(eq("gateway"), anyString(), any());
        assertEquals(1, coordinator.getReloadStatus().get("activeGatewayCount"));
    }

    @Test
    void reconnectWaitsForLateAcquisitionAndItsCompensatingRelease() {
        Sinks.One<Boolean> acquired = Sinks.one();
        Sinks.One<Boolean> released = Sinks.one();
        when(leases.acquire(anyString(), anyString(), any()))
                .thenReturn(acquired.asMono(), Mono.just(true));
        when(leases.release(anyString(), anyString())).thenReturn(released.asMono());
        event(DeviceSessionEvent.Type.register).subscribe();
        event(DeviceSessionEvent.Type.unregister).subscribe();
        event(DeviceSessionEvent.Type.register).subscribe();

        verify(leases).acquire(eq("gateway"), anyString(), any());
        verify(leases, never()).release(anyString(), anyString());
        acquired.tryEmitValue(true);
        verify(leases).release(eq("gateway"), anyString());
        verify(leases).acquire(eq("gateway"), anyString(), any());

        released.tryEmitValue(true);
        verify(leases, times(2)).acquire(eq("gateway"), anyString(), any());
        assertEquals(1, coordinator.getReloadStatus().get("activeGatewayCount"));
    }

    @Test
    void shutdownCoversRetiringWorkersAndTheirLateAcquisitions() {
        Sinks.One<Boolean> acquired = Sinks.one();
        when(leases.acquire(anyString(), anyString(), any())).thenReturn(acquired.asMono());
        event(DeviceSessionEvent.Type.register).subscribe();
        event(DeviceSessionEvent.Type.unregister).subscribe();
        clearInvocations(eventBus);

        coordinator.destroy();
        acquired.tryEmitValue(true);

        assertEquals(0, coordinator.getReloadStatus().get("activeGatewayCount"));
        verify(leases, never()).release(anyString(), anyString());
        verifyNoInteractions(eventBus);
    }

    @Test
    void shutdownCancelsTheInitialSessionEnumeration() {
        coordinator.destroy();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(sessions.getSessions())
                .thenReturn(Flux.<DeviceSession>never().doOnCancel(() -> cancelled.set(true)));
        ModbusPollingCoordinator pending = new ModbusPollingCoordinator(sessions, leases, resolver, registry,
                mock(ModbusPollReportPublisher.class), eventBus);
        pending.run();

        pending.destroy();

        assertTrue(cancelled.get());
        verifyNoInteractions(leases, registry, eventBus);
    }

    @Test
    void aChildOrNonModbusSessionDoesNotAcquireAPollingLease() {
        when(resolver.isModbusGateway("gateway")).thenReturn(Mono.just(false));
        event(DeviceSessionEvent.Type.register).block();

        assertEquals(0, coordinator.getReloadStatus().get("activeGatewayCount"));
        verifyNoInteractions(leases, registry);
    }

    private Mono<Void> event(DeviceSessionEvent.Type type) {
        return listener.get().apply(DeviceSessionEvent.of(type, session, false));
    }
}
