package org.jetlinks.community.gateway.supports;

import org.jetlinks.community.gateway.DeviceGateway;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DefaultDeviceGatewayManagerTest {
    @Test
    void initialReloadStartupFailureCleansCandidate() {
        DeviceGatewayPropertiesManager propertiesManager = mock(DeviceGatewayPropertiesManager.class);
        DeviceGatewayProvider provider = mock(DeviceGatewayProvider.class);
        DeviceGateway candidate = mock(DeviceGateway.class);
        DeviceGatewayProperties properties = new DeviceGatewayProperties();
        properties.setId("candidate");
        properties.setProvider("candidate-regression");
        when(provider.getId()).thenReturn(properties.getProvider());
        doReturn(Mono.just(candidate)).when(provider).createDeviceGateway(properties);
        when(propertiesManager.getProperties("candidate")).thenReturn(Mono.just(properties));
        IllegalStateException failure = new IllegalStateException("startup failed");
        when(candidate.startup()).thenReturn(Mono.error(failure));
        when(candidate.shutdown()).thenReturn(Mono.empty());
        DeviceGatewayProviders.register(provider);
        DefaultDeviceGatewayManager manager = new DefaultDeviceGatewayManager(propertiesManager);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> manager.reload("candidate").block()));
        verify(candidate).shutdown();
    }

    @Test
    void failedReloadMustRemainManagedAndReturnOriginalError() {
        DeviceGatewayPropertiesManager propertiesManager = mock(DeviceGatewayPropertiesManager.class);
        DeviceGatewayProvider provider = mock(DeviceGatewayProvider.class);
        DeviceGateway gateway = mock(DeviceGateway.class);
        DeviceGatewayProperties properties = new DeviceGatewayProperties();
        properties.setId("managed");
        properties.setProvider("reload-regression");
        when(provider.getId()).thenReturn(properties.getProvider());
        doReturn(Mono.just(gateway)).when(provider).createDeviceGateway(properties);
        when(propertiesManager.getProperties("managed")).thenReturn(Mono.just(properties));
        when(gateway.startup()).thenReturn(Mono.empty());
        when(gateway.shutdown()).thenReturn(Mono.empty());
        IllegalStateException failure = new IllegalStateException("invalid binding");
        doReturn(Mono.error(failure)).when(provider).reloadDeviceGateway(gateway, properties);
        DeviceGatewayProviders.register(provider);
        DefaultDeviceGatewayManager manager = new DefaultDeviceGatewayManager(propertiesManager);
        manager.start("managed").block();
        for (int i = 0; i < 3; i++) {
            assertSame(failure, assertThrows(IllegalStateException.class, () -> manager.reload("managed").block()));
            assertSame(gateway, manager.getGateway("managed").block());
        }
        verify(provider).createDeviceGateway(properties);
        manager.shutdown("managed").block();
        verify(gateway).shutdown();
    }
}
