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
package org.jetlinks.community.network.tcp.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import org.jetlinks.community.network.tcp.TcpMessage;
import org.jetlinks.community.network.tcp.parser.PayloadParser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.net.SocketException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VertxTcpClientTest {

    @Test
    void shutdownShouldReleaseResourcesEvenWithoutSocket() {
        VertxTcpClient client = new VertxTcpClient("test-client", false);
        NetClient netClient = mock(NetClient.class);
        PayloadParser parser = mock(PayloadParser.class);
        AtomicBoolean parserSubscriptionDisposed = new AtomicBoolean();
        AtomicInteger disconnected = new AtomicInteger();

        when(parser.handlePayload())
            .thenReturn(Flux.<Buffer>never().doOnCancel(() -> parserSubscriptionDisposed.set(true)));

        client.setClient(netClient);
        client.setRecordParser(parser);
        client.onDisconnect(disconnected::incrementAndGet);
        client.shutdown();

        verify(netClient).close();
        verify(parser).close();
        assertTrue(parserSubscriptionDisposed.get());
        assertEquals(1, disconnected.get());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void staleSocketCloseShouldNotShutdownReplacementConnection() {
        VertxTcpClient client = new VertxTcpClient("test-client", false);
        NetClient firstNetClient = mock(NetClient.class);
        NetClient secondNetClient = mock(NetClient.class);
        NetSocket firstSocket = mockSocket();
        NetSocket secondSocket = mockSocket();
        PayloadParser parser = mock(PayloadParser.class);
        when(parser.handlePayload()).thenReturn(Flux.never());

        long firstVersion = client.replaceClient(firstNetClient);
        client.setRecordParser(parser);
        client.setSocket(firstSocket, firstVersion);

        ArgumentCaptor<Handler<Void>> firstCloseHandler = ArgumentCaptor.forClass(Handler.class);
        verify(firstSocket).closeHandler(firstCloseHandler.capture());

        long secondVersion = client.replaceClient(secondNetClient);
        client.setSocket(secondSocket, secondVersion);
        firstCloseHandler.getValue().handle(null);

        verify(secondSocket, never()).close();
        verify(secondNetClient, never()).close();
    }

    @Test
    void reusableClientShouldKeepReceiveStreamAcrossShutdown() {
        VertxTcpClient client = new VertxTcpClient("test-client", false);
        ByteBuf payload = Unpooled.buffer().writeByte(1);

        StepVerifier
            .create(client.subscribe().take(1))
            .then(client::shutdown)
            .then(() -> client.received(new TcpMessage(payload)))
            .assertNext(message -> {
                assertEquals(1, message.getPayload().readByte());
                message.getPayload().release();
            })
            .verifyComplete();
    }

    @Test
    void sendShouldReleasePayloadWhenSocketIsUnavailable() {
        VertxTcpClient client = new VertxTcpClient("test-client", false);
        ByteBuf payload = Unpooled.buffer().writeByte(1);

        StepVerifier
            .create(client.sendMessage(new TcpMessage(payload)))
            .expectError(SocketException.class)
            .verify();

        assertEquals(0, payload.refCnt());
    }

    private NetSocket mockSocket() {
        NetSocket socket = mock(NetSocket.class);
        when(socket.closeHandler(any())).thenReturn(socket);
        when(socket.handler(any())).thenReturn(socket);
        return socket;
    }
}
