package org.jetlinks.community.network.manager.web;

import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.community.network.*;
import org.jetlinks.community.network.manager.entity.NetworkConfigEntity;
import org.jetlinks.community.network.manager.enums.NetworkConfigState;
import org.jetlinks.community.network.manager.service.NetworkConfigService;
import org.jetlinks.community.network.manager.web.response.NetworkRuntimeInfo;
import org.jetlinks.core.device.session.DeviceSessionManager;
import org.jetlinks.core.event.EventBus;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkRuntimeQueryTest {
    private final NetworkConfigService configs = mock(NetworkConfigService.class);
    private final NetworkConfigManager localConfigs = mock(NetworkConfigManager.class);
    private final ClusterNetworkManager networks = new ClusterNetworkManager(mock(EventBus.class), localConfigs);
    private final DeviceSessionManager sessions = mock(DeviceSessionManager.class);
    private final NetworkConfigController controller = new NetworkConfigController(configs, networks, null, null, sessions);

    @Test
    void reportsMissingInstanceWithoutStartingAnEnabledComponent() {
        NetworkConfigEntity config = config(NetworkConfigState.enabled);
        NetworkRuntimeInfo info = controller.getRuntime(config.getId()).block();
        assertEquals("node-a", info.getServerId());
        assertEquals("enabled", info.getConfigState());
        assertFalse(info.isAvailable());
        assertNull(info.getAlive());
        assertNull(info.getDetails().getConnected());
        verifyNoInteractions(localConfigs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsTheCachedInstanceEvenIfItsConfigurationIsDisabled() {
        NetworkConfigEntity config = config(NetworkConfigState.disabled);
        Network network = mock(Network.class);
        NetworkRuntimeDetails details = new NetworkRuntimeDetails();
        details.setListening(false);
        when(network.getRuntimeDetails()).thenReturn(details);
        when(network.isAlive()).thenReturn(true);
        NetworkProvider<Object> provider = mock(NetworkProvider.class);
        when(provider.getType()).thenReturn(DefaultNetworkType.TCP_SERVER);
        when(provider.createNetwork("options")).thenReturn(Mono.just(network));
        networks.createOrUpdate(provider, config.getId(), "options").block();

        NetworkRuntimeInfo info = controller.getRuntime(config.getId()).block();
        assertTrue(info.isAvailable());
        assertEquals("disabled", info.getConfigState());
        assertFalse(info.getAlive());
        assertSame(details, info.getDetails());
        verifyNoInteractions(localConfigs);
    }

    @Test
    void unknownConfigurationReturnsNotFound() {
        when(configs.findById("missing")).thenReturn(Mono.empty());
        assertThrows(NotFoundException.class, () -> controller.getRuntime("missing").block());
        verifyNoInteractions(localConfigs);
    }

    private NetworkConfigEntity config(NetworkConfigState state) {
        NetworkConfigEntity config = new NetworkConfigEntity();
        config.setId("server");
        config.setType(DefaultNetworkType.TCP_SERVER.getId());
        config.setState(state);
        when(configs.findById(config.getId())).thenReturn(Mono.just(config));
        when(sessions.getCurrentServerId()).thenReturn("node-a");
        return config;
    }
}
