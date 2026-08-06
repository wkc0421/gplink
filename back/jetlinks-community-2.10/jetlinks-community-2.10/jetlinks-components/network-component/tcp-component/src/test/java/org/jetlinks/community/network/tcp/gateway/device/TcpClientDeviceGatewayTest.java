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
package org.jetlinks.community.network.tcp.gateway.device;

import org.jetlinks.community.gateway.monitor.DeviceGatewayMonitor;
import org.jetlinks.community.network.tcp.TcpMessage;
import org.jetlinks.community.network.tcp.client.TcpClient;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.device.session.DeviceSessionManager;
import org.jetlinks.core.message.codec.DefaultTransport;
import org.jetlinks.supports.server.DecodedClientMessageHandler;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TcpClientDeviceGatewayTest {

    @Test
    void startupAfterShutdownShouldCreateActiveSubscriptions() throws InterruptedException {
        TcpClient tcpClient = mock(TcpClient.class);
        AtomicInteger cancellations = new AtomicInteger();
        Semaphore subscriptions = new Semaphore(0);
        Semaphore cancelled = new Semaphore(0);
        when(tcpClient.getId()).thenReturn("test-client");
        when(tcpClient.subscribe())
            .thenAnswer(ignore -> Flux
                .<TcpMessage>never()
                .doOnSubscribe(subscription -> subscriptions.release())
                .doOnCancel(() -> {
                    cancellations.incrementAndGet();
                    cancelled.release();
                }));

        TcpClientDeviceGateway gateway = new TcpClientDeviceGateway(
            "test-gateway",
            Mono.empty(),
            mock(DeviceRegistry.class),
            mock(DecodedClientMessageHandler.class),
            mock(DeviceSessionManager.class),
            tcpClient,
            null);

        gateway.startup().block();
        assertTrue(subscriptions.tryAcquire(5, TimeUnit.SECONDS));
        gateway.shutdown().block();
        assertTrue(cancelled.tryAcquire(5, TimeUnit.SECONDS));
        assertEquals(1, cancellations.get());

        gateway.startup().block();
        assertTrue(subscriptions.tryAcquire(5, TimeUnit.SECONDS));
        assertEquals(1, cancellations.get());

        gateway.shutdown().block();
        assertTrue(cancelled.tryAcquire(5, TimeUnit.SECONDS));
        assertEquals(2, cancellations.get());
    }

    @Test
    void sharedClientSessionShouldNotShutdownNetworkWhenChildSessionCloses() {
        TcpClient tcpClient = mock(TcpClient.class);
        TcpDeviceSession session = new TcpDeviceSession(
            mock(DeviceOperator.class),
            tcpClient,
            DefaultTransport.TCP,
            mock(DeviceGatewayMonitor.class),
            false);

        session.close();
        verify(tcpClient, never()).shutdown();

        session.setShutdownClientOnClose(true);
        session.close();
        verify(tcpClient).shutdown();
    }
}
