package org.jetlinks.protocol.modbus;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jetlinks.core.Value;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceProductOperator;
import org.jetlinks.core.message.ChildDeviceMessage;
import org.jetlinks.core.message.ChildDeviceMessageReply;
import org.jetlinks.core.message.DeviceMessage;
import org.jetlinks.core.message.Headers;
import org.jetlinks.core.message.Message;
import org.jetlinks.core.message.codec.EncodedMessage;
import org.jetlinks.core.message.codec.FromDeviceMessageContext;
import org.jetlinks.core.message.codec.MessageEncodeContext;
import org.jetlinks.core.message.codec.ToDeviceMessageContext;
import org.jetlinks.core.message.property.ReadPropertyMessage;
import org.jetlinks.core.message.property.ReadPropertyMessageReply;
import org.jetlinks.core.message.property.WritePropertyMessageReply;
import org.jetlinks.core.server.session.DeviceSession;
import org.jetlinks.protocol.modbus.frame.ModbusCrc16;
import org.jetlinks.protocol.modbus.frame.ModbusFunctionCode;
import org.jetlinks.protocol.modbus.frame.ModbusRequest;
import org.jetlinks.protocol.modbus.mapping.RegisterMappingTable;
import org.junit.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.test.scheduler.VirtualTimeScheduler;

import static org.junit.Assert.*;

public class ModbusReadReplyPersistenceTest {

    private static final String MAPPING = "[{\"propertyId\":\"value\",\"functionCode\":3,"
            + "\"address\":0,\"dataType\":\"UINT16\"}]";

    @Test
    public void productionContextOwnsAndCancelsFirstFrameAndEmitsNoDuplicatePayload() {
        try (Fixture fixture = new Fixture()) {
            VirtualTimeScheduler clock = VirtualTimeScheduler.create();
            ModbusGatewayRequestExecutor executor = new ModbusGatewayRequestExecutor(clock);
            ModbusRtuCodec codec = new ModbusRtuCodec(executor);
            AtomicInteger sent = new AtomicInteger();
            List<ByteBuf> buffers = new ArrayList<>();
            ReadPropertyMessage read = new ReadPropertyMessage();
            read.setDeviceId("slave");
            read.setMessageId("initial");
            read.setProperties(Collections.singletonList("value"));
            ToDeviceMessageContext context = new ToDeviceMessageContext() {
                @Override public DeviceSession getSession() { return fixture.session; }
                @Override public Mono<DeviceSession> getSession(String id) { return Mono.just(fixture.session); }
                @Override public Mono<Void> disconnect() { return Mono.empty(); }
                @Override public Message getMessage() { return read; }
                @Override public DeviceOperator getDevice() { return fixture.slave; }
                @Override public Mono<DeviceOperator> getDevice(String id) { return Mono.just(fixture.slave); }
                @Override public Mono<Void> reply(Publisher<? extends DeviceMessage> messages) { return Flux.from(messages).then(); }
                @Override public Mono<Boolean> sendToDevice(EncodedMessage message) {
                    ByteBuf buffer = message.getPayload();
                    buffers.add(buffer);
                    return Mono.delay(Duration.ofSeconds(10), clock)
                            .map(ignore -> { sent.incrementAndGet(); return true; })
                            .doFinally(ignore -> buffer.release());
                }
            };
            List<? extends EncodedMessage> encoded = Flux.from(codec.encode(context)).collectList().block();
            assertEquals(1, encoded.size());
            assertEquals(0, encoded.get(0).getPayload().readableBytes());
            assertEquals(1, buffers.size());
            clock.advanceTimeBy(Duration.ofSeconds(20));
            assertEquals(0, sent.get());
            assertEquals(0, buffers.get(0).refCnt());
            assertEquals(0, executor.gatewayStateCount());
            codec.dispose();
            clock.dispose();
        }
    }

    @Test
    public void batchAndSingleReadsKeepTheirValuesAndCorrelationWithoutPersistence() {
        for (boolean batch : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture()) {
                fixture.submit(batch, false);
                List<?> decoded = fixture.codec.decodeForTest("gateway", response(), RegisterMappingTable.parse(MAPPING));
                ReadPropertyMessageReply reply = (ReadPropertyMessageReply) decoded.get(0);
                assertTrue(reply.isSuccess());
                assertEquals("read-1", reply.getMessageId());
                assertEquals("slave", reply.getDeviceId());
                assertEquals(42, ((Number) reply.getProperties().get("value")).intValue());
                assertExcluded(reply);
                assertNull(fixture.executor.peekInFlight("gateway"));
            }
        }
    }

    @Test
    public void protocolExceptionStillCompletesTheReadWithoutWritingAnErrorReplyRecord() {
        try (Fixture fixture = new Fixture()) {
            fixture.submit(false, false);
            byte[] error = ModbusCrc16.appended(new byte[]{1, (byte) 0x83, 2});
            ReadPropertyMessageReply reply = (ReadPropertyMessageReply) fixture.codec
                    .decodeForTest("gateway", error, RegisterMappingTable.parse(MAPPING)).get(0);
            assertFalse(reply.isSuccess());
            assertEquals("read-1", reply.getMessageId());
            assertNotNull(reply.getCode());
            assertExcluded(reply);
        }
    }

    @Test
    public void childReadExcludesBothTheGatewayEnvelopeAndTheDeviceReply() {
        try (Fixture fixture = new Fixture()) {
            fixture.submit(true, false);
            ByteBuf payload = Unpooled.wrappedBuffer(response());
            try {
                FromDeviceMessageContext context = new FromDeviceMessageContext() {
                    @Override public DeviceSession getSession() { return fixture.session; }
                    @Override public DeviceOperator getDevice() { return fixture.gateway; }
                    @Override public Mono<DeviceOperator> getDevice(String id) { return Mono.just(fixture.slave); }
                    @Override public EncodedMessage getMessage() { return EncodedMessage.simple(payload); }
                };
                ChildDeviceMessageReply envelope = (ChildDeviceMessageReply) Flux.from(fixture.codec.decode(context))
                        .single().block(Duration.ofSeconds(3));
                assertNotNull(envelope);
                assertEquals("gateway", envelope.getDeviceId());
                assertExcluded(envelope);
                ReadPropertyMessageReply reply = (ReadPropertyMessageReply) envelope.getChildDeviceMessage();
                assertEquals(42, ((Number) reply.getProperties().get("value")).intValue());
                assertExcluded(reply);
            } finally {
                payload.release();
            }
        }
    }

    @Test
    public void timeoutKeepsTheInternalFailureSignalWithoutPersistingEitherEnvelope() {
        assertLifecycleReply(false, "TIMEOUT");
    }

    @Test
    public void disconnectKeepsTheInternalFailureSignalWithoutPersistingEitherEnvelope() {
        assertLifecycleReply(true, "CONNECTION_INTERRUPTED");
    }

    @Test
    public void writeRepliesKeepTheirExistingPersistenceBehavior() {
        try (Fixture fixture = new Fixture()) {
            fixture.submit(false, true);
            byte[] response = ModbusCrc16.appended(new byte[]{1, 6, 0, 0, 0, 42});
            WritePropertyMessageReply reply = (WritePropertyMessageReply) fixture.codec
                    .decodeForTest("gateway", response, RegisterMappingTable.parse(MAPPING)).get(0);
            assertTrue(reply.isSuccess());
            assertFalse(reply.getHeaderOrDefault(Headers.ignoreLog));
            assertFalse(reply.getHeaderOrDefault(Headers.ignoreStorage));
        }
    }

    private static void assertLifecycleReply(boolean disconnect, String code) {
        try (Fixture fixture = new Fixture()) {
            fixture.timeout = disconnect ? 3000 : 30;
            ReadPropertyMessage read = new ReadPropertyMessage();
            read.setDeviceId("slave");
            read.setMessageId("read-1");
            read.setProperties(Collections.singletonList("value"));
            ChildDeviceMessage request = new ChildDeviceMessage();
            request.setDeviceId("gateway");
            request.setChildDeviceId("slave");
            request.setChildDeviceMessage(read);
            Sinks.Many<DeviceMessage> replies = Sinks.many().replay().all();
            MessageEncodeContext context = new MessageEncodeContext() {
                @Override public Message getMessage() { return request; }
                @Override public DeviceOperator getDevice() { return fixture.gateway; }
                @Override public Mono<DeviceOperator> getDevice(String id) { return Mono.just(fixture.slave); }
                @Override public Mono<Void> reply(Publisher<? extends DeviceMessage> messages) {
                    return Flux.from(messages).doOnNext(replies::tryEmitNext).then();
                }
            };
            Flux.from(fixture.codec.encode(context)).doOnNext(encoded -> encoded.getPayload().release())
                    .then().block(Duration.ofSeconds(3));
            if (disconnect) fixture.executor.drain("gateway", "connection closed");
            ChildDeviceMessageReply envelope = (ChildDeviceMessageReply) replies.asFlux().next().block(Duration.ofSeconds(3));
            assertNotNull(envelope);
            assertExcluded(envelope);
            ReadPropertyMessageReply reply = (ReadPropertyMessageReply) envelope.getChildDeviceMessage();
            assertFalse(reply.isSuccess());
            assertEquals("read-1", reply.getMessageId());
            assertEquals(code, reply.getCode());
            assertExcluded(reply);
        }
    }

    private static void assertExcluded(DeviceMessage message) {
        assertTrue("message log must not be persisted", message.getHeaderOrDefault(Headers.ignoreLog));
        assertTrue("property data must not be persisted", message.getHeaderOrDefault(Headers.ignoreStorage));
    }

    private static byte[] response() {
        return ModbusCrc16.appended(new byte[]{1, 3, 2, 0, 42});
    }

    private static final class Fixture implements AutoCloseable {
        final ModbusGatewayRequestExecutor executor = new ModbusGatewayRequestExecutor();
        final ModbusRtuCodec codec = new ModbusRtuCodec(executor);
        final DeviceProductOperator product;
        final DeviceOperator gateway;
        final DeviceOperator slave;
        final DeviceSession session;
        long timeout = 3000;

        Fixture() {
            product = proxy(DeviceProductOperator.class, (target, method, args) -> {
                if ("getConfig".equals(method.getName())) {
                    return ModbusRtuCodec.CONFIG_REGISTER_MAP.equals(args[0])
                            ? Mono.just(Value.simple(MAPPING)) : Mono.empty();
                }
                throw new UnsupportedOperationException(method.getName());
            });
            gateway = device("gateway");
            slave = device("slave");
            session = proxy(DeviceSession.class, (target, method, args) -> {
                switch (method.getName()) {
                    case "getId": case "getDeviceId": return "gateway";
                    case "getOperator": return gateway;
                    case "onClose": return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        DeviceOperator device(String id) {
            return proxy(DeviceOperator.class, (target, method, args) -> {
                switch (method.getName()) {
                    case "getDeviceId": return id;
                    case "getProduct": return Mono.just(product);
                    case "getParentDevice": return Mono.empty();
                    case "getConfig": return Mono.empty();
                    case "getSelfConfig":
                        return ModbusRtuCodec.CONFIG_RESPONSE_TIMEOUT_MS.equals(args[0])
                                ? Mono.just(Value.simple(timeout)) : Mono.empty();
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        void submit(boolean batch, boolean write) {
            ModbusGatewayRequestExecutor.PendingRequest.Builder builder =
                    ModbusGatewayRequestExecutor.PendingRequest.builder()
                            .gatewayId("gateway").deviceId("slave")
                            .messageId("read-1").replyMessageId("read-1")
                            .request(write ? ModbusRequest.writeSingleRegister(1, 0, 42)
                                    : ModbusRequest.read(1, ModbusFunctionCode.READ_HOLDING_REGISTERS, 0, 1))
                            .timeoutMillis(3000);
            if (batch) builder.batchPropertyIds(Collections.singletonList("value")).batchStartAddress(0);
            else builder.propertyId("value");
            executor.submit(builder.build());
        }

        @Override public void close() { executor.dispose(); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
