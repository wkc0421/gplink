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
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import org.jetlinks.community.network.tcp.TcpMessage;
import org.jetlinks.community.network.tcp.parser.PayloadParser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VertxTcpClientTest {

    @Test
    void multicastConsumersOwnIndependentPayloads() {
        VertxTcpClient client = new VertxTcpClient("multicast", false);
        List<TcpMessage> second = new ArrayList<>();
        Disposable firstSubscription = client.subscribe().subscribe(message -> {
            assertEquals(42, message.getPayload().readByte());
            message.getPayload().setByte(0, 99);
            message.getPayload().release();
        });
        Disposable secondSubscription = client.subscribe().subscribe(second::add);
        ByteBuf source = Unpooled.buffer().writeByte(42);
        client.received(new TcpMessage(source));
        assertEquals(0, source.refCnt());
        assertEquals(1, second.size());
        assertEquals(42, second.get(0).getPayload().readByte());
        assertEquals(1, second.get(0).getPayload().refCnt());
        second.get(0).getPayload().release();
        firstSubscription.dispose();
        secondSubscription.dispose();
        client.shutdown();
    }

    @Test
    void prebindingQueueOwnsNoByteBufAndDropsFramesFromClosedGeneration() {
        VertxTcpClient client = new VertxTcpClient("prebind", false);
        ByteBuf stale = Unpooled.buffer().writeByte(1);
        client.received(new TcpMessage(stale));
        assertEquals(0, stale.refCnt());
        client.shutdown();
        ByteBuf current = Unpooled.buffer().writeByte(2);
        client.received(new TcpMessage(current));
        assertEquals(0, current.refCnt());
        StepVerifier.create(client.subscribe().take(1))
            .assertNext(message -> {
                assertEquals(2, message.getPayload().readByte());
                message.getPayload().release();
            }).verifyComplete();
    }

    @Test
    void oversizedFrameClosesSocketAndDropsParserState() {
        VertxTcpClient client = new VertxTcpClient("bounded", false);
        NetSocket socket = mockSocket();
        PayloadParser parser = new org.jetlinks.community.network.tcp.parser.strateies.DelimitedPayloadParserBuilder()
            .build(org.jetlinks.community.ValueObject.of(java.util.Map.of("delimited", "\\n", "maxFrameLength", 4)));
        client.setRecordParser(parser);
        client.setSocket(socket);
        ArgumentCaptor<Handler<Buffer>> handler = ArgumentCaptor.forClass(Handler.class);
        verify(socket).handler(handler.capture());
        handler.getValue().handle(Buffer.buffer("1234"));
        assertTrue(client.isAlive());
        handler.getValue().handle(Buffer.buffer("5"));
        assertFalse(client.isAlive());
        org.junit.jupiter.api.Assertions.assertNull(client.payloadParser);
        verify(socket).close();
    }

    @Test
    void lateParserFailureCannotCloseReplacementConnection() {
        VertxTcpClient client = new VertxTcpClient("replacement", false);
        NetSocket oldSocket = mockSocket();
        NetSocket newSocket = mockSocket();
        PayloadParser oldParser = mock(PayloadParser.class);
        PayloadParser newParser = mock(PayloadParser.class);
        when(oldParser.handlePayload()).thenReturn(Flux.never());
        when(newParser.handlePayload()).thenReturn(Flux.never());
        client.setRecordParser(oldParser);
        client.setSocket(oldSocket);
        ArgumentCaptor<Handler<Buffer>> handler = ArgumentCaptor.forClass(Handler.class);
        verify(oldSocket).handler(handler.capture());
        doAnswer(invocation -> {
            client.setRecordParser(newParser);
            client.setSocket(newSocket);
            throw new IllegalArgumentException("late parsing failure");
        }).when(oldParser).handle(any());
        handler.getValue().handle(Buffer.buffer("old"));
        assertTrue(client.isAlive());
        org.junit.jupiter.api.Assertions.assertSame(newParser, client.payloadParser);
        verify(newSocket, never()).close();
        client.shutdown();
    }

    @Test
    void connectionStateShouldFollowAcceptedSocketLifecycle() {
        VertxTcpClient client = new VertxTcpClient("test-client", false);
        NetClient firstNetClient = mock(NetClient.class);
        NetClient secondNetClient = mock(NetClient.class);
        PayloadParser firstParser = mock(PayloadParser.class);
        PayloadParser secondParser = mock(PayloadParser.class);
        NetSocket firstSocket = mockSocket();
        NetSocket secondSocket = mockSocket();
        when(firstParser.handlePayload()).thenReturn(Flux.never());
        when(secondParser.handlePayload()).thenReturn(Flux.never());

        List<Boolean> states = new ArrayList<>();
        Disposable subscription = client.connectionState().subscribe(states::add);

        long firstVersion = client.replaceClient(firstNetClient);
        client.setRecordParser(firstParser);
        assertTrue(client.setSocket(firstSocket, firstVersion));

        long secondVersion = client.replaceClient(secondNetClient);
        client.setRecordParser(secondParser);
        assertTrue(client.setSocket(secondSocket, secondVersion));
        client.shutdown();

        assertEquals(Arrays.asList(false, true, false, true, false), states);
        subscription.dispose();
    }

    @Test
    void replaceClientShouldDrainDisconnectListenersExactlyOnce() {
        VertxTcpClient client = new VertxTcpClient("test-client", false);
        NetClient netClient = mock(NetClient.class);
        NetSocket socket = mockSocket();
        PayloadParser parser = mock(PayloadParser.class);
        AtomicInteger disconnected = new AtomicInteger();
        when(parser.handlePayload()).thenReturn(Flux.never());

        long version = client.replaceClient(netClient);
        client.setRecordParser(parser);
        assertTrue(client.setSocket(socket, version));

        for (int i = 1; i <= 1_000; i++) {
            client.onDisconnect(disconnected::incrementAndGet);
            version = client.replaceClient(netClient);
            assertEquals(i, disconnected.get());
            assertTrue(client.setSocket(socket, version));
        }

        client.shutdown();
        assertEquals(1_000, disconnected.get());
    }

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
        List<Boolean> states = new ArrayList<>();
        Disposable stateSubscription = client.connectionState().subscribe(states::add);

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
        assertEquals(Arrays.asList(false, true, false, true), states);
        stateSubscription.dispose();
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

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void outboundWriteShouldNotKeepSilentServerAlive() throws InterruptedException {
        VertxTcpClient client = new VertxTcpClient("test-client", false);
        NetClient netClient = mock(NetClient.class);
        NetSocket socket = mockSocket();
        PayloadParser parser = mock(PayloadParser.class);
        when(parser.handlePayload()).thenReturn(Flux.never());
        doAnswer(invocation -> {
            Handler handler = invocation.getArgument(1);
            handler.handle(Future.succeededFuture());
            return socket;
        }).when(socket).write(any(Buffer.class), any());

        long version = client.replaceClient(netClient);
        client.setRecordParser(parser);
        assertTrue(client.setSocket(socket, version));
        client.setKeepAliveTimeout(Duration.ofMillis(5));
        Thread.sleep(20);

        StepVerifier
            .create(client.sendMessage(new TcpMessage(Unpooled.buffer().writeByte(1))))
            .verifyComplete();

        assertFalse(client.isAlive());
        client.shutdown();
    }

    private NetSocket mockSocket() {
        NetSocket socket = mock(NetSocket.class);
        when(socket.closeHandler(any())).thenReturn(socket);
        when(socket.handler(any())).thenReturn(socket);
        return socket;
    }
}
