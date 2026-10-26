package org.jetlinks.protocol.modbus;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jetlinks.core.Value;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceProductOperator;
import org.jetlinks.core.device.DeviceStateInfo;
import org.jetlinks.core.message.BroadcastMessage;
import org.jetlinks.core.message.ChildDeviceMessage;
import org.jetlinks.core.message.ChildDeviceMessageReply;
import org.jetlinks.core.message.DeviceMessage;
import org.jetlinks.core.message.DeviceMessageReply;
import org.jetlinks.core.message.Headers;
import org.jetlinks.core.message.Message;
import org.jetlinks.core.message.codec.EncodedMessage;
import org.jetlinks.core.message.codec.FromDeviceMessageContext;
import org.jetlinks.core.message.codec.ToDeviceMessageContext;
import org.jetlinks.core.message.property.ReadPropertyMessage;
import org.jetlinks.core.message.property.ReadPropertyMessageReply;
import org.jetlinks.core.server.session.DeviceSession;
import org.jetlinks.protocol.modbus.frame.ModbusCrc16;
import org.jetlinks.supports.cluster.AbstractDeviceOperationBroker;
import org.junit.Test;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.*;

/** Exercise real platform reply completion, not just the protocol's split-frame list. */
public class ModbusMultiFrameReadTest {
    @Test
    public void manualReadCollectsAllThirtyFourFloatsBeforeCompleting() {
        assertCompleteRead(false);
    }

    @Test
    public void pollingReadCollectsAllThirtyFourFloatsBeforePublishing() {
        assertCompleteRead(true);
    }

    private void assertCompleteRead(boolean polling) {
        try (Fixture fixture = new Fixture(34, 60, polling)) {
            fixture.start();
            assertEquals(1, fixture.writes.size());
            assertRead(fixture.writes.get(0), 0, 60);
            assertEquals(1, fixture.executor.waitingCount("gateway"));

            fixture.respond(0, 30);
            assertEquals(30, fixture.received.size());
            fixture.clock.advanceTimeBy(Duration.ofMillis(100));
            assertEquals(2, fixture.writes.size());
            assertRead(fixture.writes.get(1), 60, 8);
            assertEquals("The first 30 properties must not complete a 34-property read",
                    0, fixture.completed.get());
            assertTrue(fixture.published.isEmpty());

            fixture.respond(30, 4);
            assertEquals(1, fixture.completed.get());
            assertEquals(34, fixture.published.size());
            for (int i = 0; i < 34; i++) {
                assertEquals((double) (i + 1), ((Number) fixture.published.get("p" + i)).doubleValue(), 0.0);
            }
            assertEquals(0, fixture.executor.gatewayStateCount());
            assertEquals(2, fixture.replies.size());
            fixture.replies.forEach(reply -> {
                assertEquals("read-34", reply.getHeader(Headers.fragmentBodyMessageId).orElse(null));
                assertEquals(Integer.valueOf(2), reply.getHeader(Headers.fragmentNumber).orElse(null));
            });
        }
    }

    @Test
    public void secondFrameTimeoutFinishesTheReadAfterItsFailureReply() {
        try (Fixture fixture = new Fixture(34, 60, true)) {
            fixture.start();
            fixture.respond(0, 30);
            assertEquals(0, fixture.completed.get());
            fixture.clock.advanceTimeBy(Duration.ofSeconds(1));
            assertEquals(1, fixture.completed.get());
            assertEquals(30, fixture.published.size());
            assertEquals(2, fixture.replies.size());
            assertFalse(fixture.replies.get(1).isSuccess());
            assertEquals("TIMEOUT", fixture.replies.get(1).getCode());
            assertEquals(0, fixture.executor.gatewayStateCount());
        }
    }

    @Test
    public void firstFrameTimeoutDoesNotDiscardTheSecondFrameReply() {
        try (Fixture fixture = new Fixture(34, 60, false)) {
            fixture.start();
            fixture.clock.advanceTimeBy(Duration.ofMillis(1000));
            assertEquals(0, fixture.completed.get());
            fixture.clock.advanceTimeBy(Duration.ofMillis(100));
            assertEquals(2, fixture.writes.size());
            fixture.respond(30, 4);
            assertEquals(1, fixture.completed.get());
            assertEquals(4, fixture.published.size());
            assertEquals(0, fixture.executor.gatewayStateCount());
        }
    }

    @Test
    public void disconnectCancelsDelayedSecondSendAndCompletesOnce() {
        try (Fixture fixture = new Fixture(34, 60, true)) {
            fixture.start();
            fixture.respond(0, 30);
            assertEquals(0, fixture.completed.get());
            fixture.executor.drain("gateway", "disconnected");
            fixture.clock.advanceTimeBy(Duration.ofSeconds(2));
            assertEquals(1, fixture.writes.size());
            assertEquals(1, fixture.completed.get());
            assertEquals(30, fixture.published.size());
            assertEquals(2, fixture.replies.size());
            assertEquals("CONNECTION_INTERRUPTED", fixture.replies.get(1).getCode());
            assertEquals(0, fixture.executor.gatewayStateCount());
        }
    }

    @Test
    public void aSixtyEightRegisterWindowReadsAllPropertiesInOneFrame() {
        try (Fixture fixture = new Fixture(34, 68, true)) {
            fixture.start();
            assertEquals(1, fixture.writes.size());
            assertRead(fixture.writes.get(0), 0, 68);
            fixture.respond(0, 34);
            assertEquals(1, fixture.completed.get());
            assertEquals(34, fixture.published.size());
            assertFalse(fixture.replies.get(0).getHeader(Headers.fragmentBodyMessageId).isPresent());
            assertEquals(0, fixture.executor.gatewayStateCount());
        }
    }

    @Test
    public void fragmentedTcpRepliesStillPublishAllThirtyFourProperties() {
        try (Fixture fixture = new Fixture(34, 60, true)) {
            fixture.start();
            // A valid 125-byte RTU response arrives in two TCP reads.
            fixture.respondInChunks(0, 30, 120);
            assertEquals(30, fixture.received.size());
            assertEquals(0, fixture.completed.get());
            fixture.clock.advanceTimeBy(Duration.ofMillis(100));
            assertEquals(2, fixture.writes.size());
            fixture.respondInChunks(30, 4, 7);
            assertEquals(1, fixture.completed.get());
            assertEquals(34, fixture.published.size());
            assertEquals(0, fixture.executor.gatewayStateCount());
        }
    }

    @Test
    public void bothResponseLengthsSurviveEveryTcpSplitBoundary() {
        for (int split = 1; split < 125; split++) {
            try (Fixture fixture = new Fixture(34, 60, true)) {
                fixture.start();
                fixture.respondInChunks(0, 30, split);
                assertEquals("first response split at " + split, 30, fixture.received.size());
                fixture.clock.advanceTimeBy(Duration.ofMillis(100));
                fixture.respond(30, 4);
                assertEquals(34, fixture.published.size());
            }
        }
        for (int split = 1; split < 21; split++) {
            try (Fixture fixture = new Fixture(34, 60, false)) {
                fixture.start();
                fixture.respond(0, 30);
                fixture.clock.advanceTimeBy(Duration.ofMillis(100));
                fixture.respondInChunks(30, 4, split);
                assertEquals("second response split at " + split, 34, fixture.published.size());
            }
        }
    }

    @Test
    public void disconnectAndProtocolUnloadReleasePartialResponseBuffers() {
        for (boolean unload : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture(34, 60, true)) {
                fixture.start();
                fixture.receive(java.util.Arrays.copyOf(fixture.responseBytes(0, 30), 50));
                assertEquals(50, fixture.codec.bufferedResponseBytes());
                assertEquals(1, fixture.codec.responseStreamCount());
                if (unload) fixture.codec.dispose();
                else new ArrayList<>(fixture.closeCallbacks).forEach(Runnable::run);
                assertEquals(0, fixture.codec.bufferedResponseBytes());
                assertEquals(0, fixture.codec.responseStreamCount());
                assertEquals(0, fixture.executor.gatewayStateCount());
                assertEquals(1, fixture.completed.get());
            }
        }
    }

    @Test
    public void aTimedOutPartialResponseCannotCorruptTheNextReadWindow() {
        try (Fixture fixture = new Fixture(34, 60, true)) {
            fixture.start();
            fixture.receive(java.util.Arrays.copyOf(fixture.responseBytes(0, 30), 50));
            assertEquals(50, fixture.codec.bufferedResponseBytes());
            fixture.clock.advanceTimeBy(Duration.ofMillis(1000));
            assertEquals(0, fixture.codec.bufferedResponseBytes());
            fixture.clock.advanceTimeBy(Duration.ofMillis(100));
            fixture.respond(30, 4);
            assertEquals(1, fixture.completed.get());
            assertEquals(4, fixture.published.size());
            assertEquals(0, fixture.codec.bufferedResponseBytes());
            assertEquals(0, fixture.executor.gatewayStateCount());
        }
    }

    private static void assertRead(byte[] request, int address, int quantity) {
        assertEquals(8, request.length);
        assertTrue(ModbusCrc16.validate(request));
        assertEquals(3, request[1]);
        assertEquals(address, ((request[2] & 255) << 8) | (request[3] & 255));
        assertEquals(quantity, ((request[4] & 255) << 8) | (request[5] & 255));
    }

    private static final class Fixture implements AutoCloseable {
        final VirtualTimeScheduler clock = VirtualTimeScheduler.create();
        final ModbusGatewayRequestExecutor executor = new ModbusGatewayRequestExecutor(clock);
        final ModbusRtuCodec codec = new ModbusRtuCodec(executor);
        final LocalBroker broker = new LocalBroker();
        final List<byte[]> writes = new ArrayList<>();
        final List<Runnable> closeCallbacks = new ArrayList<>();
        final List<ReadPropertyMessageReply> replies = new ArrayList<>();
        final Map<String, Object> received = new LinkedHashMap<>();
        final Map<String, Object> published = new LinkedHashMap<>();
        final AtomicInteger completed = new AtomicInteger();
        final ReadPropertyMessage read = new ReadPropertyMessage();
        final ChildDeviceMessage envelope = new ChildDeviceMessage();
        final DeviceOperator gateway;
        final DeviceOperator slave;
        final DeviceSession session;
        Disposable awaiting = Disposables.disposed();

        Fixture(int propertyCount, int registerWindow, boolean polling) {
            StringBuilder json = new StringBuilder("[");
            List<String> properties = new ArrayList<>();
            for (int i = 0; i < propertyCount; i++) {
                if (i > 0) json.append(',');
                json.append("{\"propertyId\":\"p").append(i)
                    .append("\",\"fc\":3,\"address\":").append(i * 2)
                    .append(",\"dataType\":\"float32\",\"byteOrder\":\"ABCD\"}");
                properties.add("p" + i);
            }
            json.append(']');
            DeviceProductOperator product = proxy(DeviceProductOperator.class, (target, method, args) -> {
                if ("getConfig".equals(method.getName())) {
                    return ModbusRtuCodec.CONFIG_REGISTER_MAP.equals(args[0])
                            ? Mono.just(Value.simple(json.toString())) : Mono.empty();
                }
                throw new UnsupportedOperationException(method.getName());
            });
            gateway = device("gateway", product, registerWindow);
            slave = device("slave", product, registerWindow);
            session = proxy(DeviceSession.class, (target, method, args) -> {
                switch (method.getName()) {
                    case "getId": case "getDeviceId": return "gateway";
                    case "getOperator": return gateway;
                    case "isAlive": return true;
                    case "onClose": closeCallbacks.add((Runnable) args[0]); return null;
                    case "send": return write((EncodedMessage) args[0]);
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
            executor.setSender(pending -> Mono.delay(Duration.ofMillis(100), clock)
                    .flatMap(ignore -> executor.sendIfCurrent(pending,
                            () -> session.send(EncodedMessage.simple(Unpooled.wrappedBuffer(pending.getRequest().toAdu()))))));
            read.setDeviceId("slave");
            read.setMessageId("read-34");
            read.setProperties(properties);
            if (polling) {
                read.addHeader(ModbusRtuCodec.HEADER_REQUEST_SOURCE, "POLLING");
                read.addHeader(ModbusRtuCodec.HEADER_POLL_LEASE_OWNER, "owner");
                read.addHeader(ModbusRtuCodec.HEADER_POLL_LEASE_EXPIRES_AT, System.currentTimeMillis() + 60000);
            }
            envelope.setDeviceId("gateway");
            envelope.setChildDeviceId("slave");
            envelope.setChildDeviceMessage(read);
        }

        DeviceOperator device(String id, DeviceProductOperator product, int registerWindow) {
            return proxy(DeviceOperator.class, (target, method, args) -> {
                switch (method.getName()) {
                    case "getDeviceId": return id;
                    case "getProduct": return Mono.just(product);
                    case "getParentDevice": return Mono.empty();
                    case "getConfig": return Mono.empty();
                    case "getSelfConfig":
                        if (ModbusRtuCodec.CONFIG_RESPONSE_TIMEOUT_MS.equals(args[0])) return Mono.just(Value.simple(1000));
                        if (ModbusRtuCodec.CONFIG_MAX_READ_REGISTERS.equals(args[0])) return Mono.just(Value.simple(registerWindow));
                        return Mono.empty();
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        void start() {
            awaiting = broker.handleReply("slave", "read-34", Duration.ofSeconds(10))
                    .cast(ReadPropertyMessageReply.class)
                    .doOnNext(reply -> {
                        replies.add(reply);
                        if (reply.isSuccess() && reply.getProperties() != null) received.putAll(reply.getProperties());
                    })
                    .doOnComplete(() -> {
                        published.putAll(received);
                        completed.incrementAndGet();
                    }).subscribe();
            ToDeviceMessageContext context = new ToDeviceMessageContext() {
                @Override public DeviceSession getSession() { return session; }
                @Override public Mono<DeviceSession> getSession(String id) { return Mono.just(session); }
                @Override public Mono<Void> disconnect() { return Mono.empty(); }
                @Override public Message getMessage() { return envelope; }
                @Override public DeviceOperator getDevice() { return gateway; }
                @Override public Mono<DeviceOperator> getDevice(String id) { return Mono.just(slave); }
                @Override public Mono<Void> reply(Publisher<? extends DeviceMessage> messages) {
                    return Flux.from(messages).concatMap(Fixture.this::deliver).then();
                }
                @Override public Mono<Boolean> sendToDevice(EncodedMessage message) { return write(message); }
            };
            Flux.from(codec.encode(context)).doOnNext(encoded -> encoded.getPayload().release()).then().block();
        }

        Mono<Boolean> write(EncodedMessage message) {
            return Mono.fromSupplier(() -> {
                ByteBuf buffer = message.getPayload();
                try {
                    byte[] bytes = new byte[buffer.readableBytes()];
                    buffer.getBytes(buffer.readerIndex(), bytes);
                    writes.add(bytes);
                    return true;
                } finally {
                    buffer.release();
                }
            });
        }

        Mono<Void> deliver(DeviceMessage message) {
            DeviceMessage reply = message instanceof ChildDeviceMessageReply
                    ? (DeviceMessage) ((ChildDeviceMessageReply) message).getChildDeviceMessage() : message;
            if (reply.getHeader(Headers.fragmentBodyMessageId).isPresent()) {
                assertEquals(reply.getHeader(Headers.fragmentBodyMessageId), message.getHeader(Headers.fragmentBodyMessageId));
                assertEquals(reply.getHeader(Headers.fragmentNumber), message.getHeader(Headers.fragmentNumber));
            }
            return broker.reply((DeviceMessageReply) reply).then();
        }

        void respond(int firstProperty, int count) {
            receive(responseBytes(firstProperty, count));
        }

        void respondInChunks(int firstProperty, int count, int split) {
            byte[] bytes = responseBytes(firstProperty, count);
            receive(java.util.Arrays.copyOfRange(bytes, 0, split));
            receive(java.util.Arrays.copyOfRange(bytes, split, bytes.length));
        }

        byte[] responseBytes(int firstProperty, int count) {
            ByteBuffer payload = ByteBuffer.allocate(3 + count * 4);
            payload.put((byte) 1).put((byte) 3).put((byte) (count * 4));
            for (int i = 0; i < count; i++) payload.putFloat(firstProperty + i + 1);
            return ModbusCrc16.appended(payload.array());
        }

        void receive(byte[] bytes) {
            ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
            FromDeviceMessageContext context = new FromDeviceMessageContext() {
                @Override public DeviceSession getSession() { return session; }
                @Override public DeviceOperator getDevice() { return gateway; }
                @Override public Mono<DeviceOperator> getDevice(String id) { return Mono.just(slave); }
                @Override public EncodedMessage getMessage() { return EncodedMessage.simple(buffer); }
            };
            try {
                Flux.from(codec.decode(context)).concatMap(this::deliver).then().block();
            } finally {
                buffer.release();
            }
        }

        @Override public void close() {
            awaiting.dispose();
            codec.dispose();
            clock.dispose();
        }
    }

    private static final class LocalBroker extends AbstractDeviceOperationBroker {
        @Override public Flux<DeviceStateInfo> getDeviceState(String server, Collection<String> ids) { return Flux.empty(); }
        @Override public Disposable handleGetDeviceState(String server, Function<Publisher<String>, Flux<DeviceStateInfo>> handler) { return Disposables.disposed(); }
        @Override public Mono<Integer> send(String server, Publisher<? extends Message> messages) { return Mono.just(0); }
        @Override public Mono<Integer> send(Publisher<? extends BroadcastMessage> messages) { return Mono.just(0); }
        @Override public Flux<Message> handleSendToDeviceMessage(String server) { return Flux.empty(); }
        @Override protected Mono<Void> doReply(DeviceMessageReply reply) { return Mono.empty(); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
