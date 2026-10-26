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
package org.jetlinks.community.network;

import org.jetlinks.core.event.EventBus;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClusterNetworkManagerTest {

    @Test
    @SuppressWarnings("unchecked")
    void reloadFailureShouldKeepNetworkAvailableForNextRetry() {
        NetworkConfigManager configManager = mock(NetworkConfigManager.class);
        ClusterNetworkManager manager = new ClusterNetworkManager(mock(EventBus.class), configManager);
        NetworkProvider<Object> provider = mock(NetworkProvider.class);
        NetworkType type = NetworkType.of("test-reconnect");
        Network network = mock(Network.class);
        Object config = new Object();

        when(provider.getType()).thenReturn(type);
        when(provider.createNetwork(config)).thenReturn(Mono.just(network));
        when(provider.reload(network, config)).thenReturn(Mono.error(new IllegalStateException("reload failed")));

        assertSame(network, manager.createOrUpdate(provider, "test", config).block());
        assertSame(network, manager.createOrUpdate(provider, "test", config).block());
        assertSame(network, manager.getNetworks().single().block());
        verify(provider).reload(network, config);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tcpReloadShouldPropagateFailureWithoutReusingItOnTheNextSubscription() {
        ClusterNetworkManager manager = new ClusterNetworkManager(mock(EventBus.class), mock(NetworkConfigManager.class));
        NetworkProvider<Object> provider = mock(NetworkProvider.class);
        Network network = mock(Network.class);
        Object config = new Object();
        IllegalStateException failure = new IllegalStateException("occupied port");
        when(provider.getType()).thenReturn(DefaultNetworkType.TCP_SERVER);
        when(provider.createNetwork(config)).thenReturn(Mono.just(network));
        when(provider.reload(network, config)).thenReturn(Mono.error(failure), Mono.just(network));
        assertSame(network, manager.createOrUpdate(provider, "server", config).block());

        Mono<Network> reload = manager.createOrUpdate(provider, "server", config);
        assertSame(failure, assertThrows(IllegalStateException.class, reload::block));
        assertSame(network, manager.getNetworks().single().block());
        assertSame(network, reload.block());
    }

    @Test
    void shutdownShouldAwaitTheNetworkCloseCompletion() {
        assertCloseCompletion(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void synchronousReloadFailureShouldKeepTheCachedNetwork() {
        ClusterNetworkManager manager = new ClusterNetworkManager(mock(EventBus.class), mock(NetworkConfigManager.class));
        NetworkProvider<Object> provider = mock(NetworkProvider.class);
        Network network = mock(Network.class);
        Object config = new Object();
        when(provider.getType()).thenReturn(DefaultNetworkType.TCP_SERVER);
        when(provider.createNetwork(config)).thenReturn(Mono.just(network));
        when(provider.reload(network, config)).thenThrow(new IllegalStateException("invalid reload"));
        manager.createOrUpdate(provider, "server", config).block();

        assertThrows(IllegalStateException.class, () -> manager.createOrUpdate(provider, "server", config).block());
        assertSame(network, manager.getNetworks().single().block());
    }

    @Test
    void destroyShouldAwaitTheNetworkCloseCompletion() {
        assertCloseCompletion(true);
    }

    @SuppressWarnings("unchecked")
    private void assertCloseCompletion(boolean destroy) {
        ClusterNetworkManager manager = new ClusterNetworkManager(mock(EventBus.class), mock(NetworkConfigManager.class));
        NetworkProvider<Object> provider = mock(NetworkProvider.class);
        Network network = mock(Network.class);
        Object config = new Object();
        Sinks.Empty<Void> closed = Sinks.empty();
        when(provider.getType()).thenReturn(DefaultNetworkType.TCP_SERVER);
        when(provider.createNetwork(config)).thenReturn(Mono.just(network));
        when(network.shutdownAsync()).thenReturn(closed.asMono());
        manager.createOrUpdate(provider, "server", config).block();
        AtomicBoolean completed = new AtomicBoolean();

        (destroy ? manager.doDestroy(DefaultNetworkType.TCP_SERVER.getId(), "server")
                 : manager.doShutdown(DefaultNetworkType.TCP_SERVER.getId(), "server"))
                .doOnSuccess(ignore -> completed.set(true)).subscribe();
        verify(network).shutdownAsync();
        assertFalse(completed.get());
        closed.tryEmitEmpty();
        assertTrue(completed.get());
        if (destroy) {
            assertFalse(manager.getNetworks().hasElements().block());
        } else {
            assertSame(network, manager.getNetworks().single().block());
        }
    }
}
