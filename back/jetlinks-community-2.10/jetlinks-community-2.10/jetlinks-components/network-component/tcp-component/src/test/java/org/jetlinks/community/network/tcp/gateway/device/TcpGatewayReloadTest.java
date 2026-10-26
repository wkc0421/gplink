package org.jetlinks.community.network.tcp.gateway.device;

import org.jetlinks.community.gateway.DeviceGateway;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TcpGatewayReloadTest {
    @Test
    void failedCandidateIsClosedAndOldGatewayRestartsWithOriginalError() {
        DeviceGateway previous = mock(DeviceGateway.class);
        DeviceGateway candidate = mock(DeviceGateway.class);
        IllegalStateException failure = new IllegalStateException("candidate startup failed");
        when(previous.shutdown()).thenReturn(Mono.empty());
        when(previous.startup()).thenReturn(Mono.empty());
        when(candidate.startup()).thenReturn(Mono.error(failure));
        when(candidate.shutdown()).thenReturn(Mono.empty());
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> TcpGatewayReload.replace(previous, Mono.just(candidate)).block()));
        verify(candidate).shutdown();
        verify(previous).startup();
    }

    @Test
    void cancelledCandidateIsClosedAndOldGatewayRestarts() {
        DeviceGateway previous = mock(DeviceGateway.class);
        DeviceGateway candidate = mock(DeviceGateway.class);
        when(previous.shutdown()).thenReturn(Mono.empty());
        when(previous.startup()).thenReturn(Mono.empty());
        when(candidate.startup()).thenReturn(Mono.never());
        when(candidate.shutdown()).thenReturn(Mono.empty());
        Disposable loading = TcpGatewayReload.replace(previous, Mono.just(candidate)).subscribe();
        loading.dispose();
        verify(candidate).shutdown();
        verify(previous).startup();
    }
}
