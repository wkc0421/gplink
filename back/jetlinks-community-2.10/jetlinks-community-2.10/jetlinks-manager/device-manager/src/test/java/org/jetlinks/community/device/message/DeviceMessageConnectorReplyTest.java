package org.jetlinks.community.device.message;

import org.jetlinks.core.Values;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.device.DeviceStateInfo;
import org.jetlinks.core.device.session.DeviceSessionManager;
import org.jetlinks.core.event.EventBus;
import org.jetlinks.core.message.BroadcastMessage;
import org.jetlinks.core.message.ChildDeviceMessage;
import org.jetlinks.core.message.ChildDeviceMessageReply;
import org.jetlinks.core.message.DeviceMessage;
import org.jetlinks.core.message.DeviceMessageReply;
import org.jetlinks.core.message.Headers;
import org.jetlinks.core.message.Message;
import org.jetlinks.core.message.property.ReadPropertyMessageReply;
import org.jetlinks.supports.cluster.AbstractDeviceOperationBroker;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeviceMessageConnectorReplyTest {

    @Test
    void directChildReadWaitsForBothFramesAndReportsAllThirtyFourProperties() {
        assertCompleteRead(false);
    }

    @Test
    void parentRoutedChildReadCompletesBothAwaitersOnlyAfterBothFrames() {
        assertCompleteRead(true);
    }

    private void assertCompleteRead(boolean awaitGateway) {
        Fixture fixture = new Fixture();
        try (Awaiting child = fixture.await("slave");
             Awaiting gateway = awaitGateway ? fixture.await("gateway") : null) {
            fixture.receive(envelope("gateway", "slave", reply(0, 30, 2)));
            assertEquals(1, child.replies.size(), "The broker already handles the nested child reply");
            assertEquals(30, child.properties.size());
            assertEquals(0, child.completed.get(), "The first frame must not complete the logical read");
            if (gateway != null) {
                assertEquals(1, gateway.replies.size());
                assertEquals(0, gateway.completed.get());
            }

            fixture.receive(envelope("gateway", "slave", reply(30, 4, 2)));
            assertEquals(2, child.replies.size());
            assertEquals(1, child.completed.get());
            assertEquals(34, child.properties.size());
            for (int i = 0; i < 34; i++) {
                assertEquals(i + 1.25, child.properties.get("p" + i));
            }
            if (gateway != null) {
                assertEquals(2, gateway.replies.size());
                assertEquals(1, gateway.completed.get());
            }
            assertEquals(0, fixture.broker.pendingCount());
        }
    }

    @Test
    void terminalErrorForSecondFramePreservesTheFirstFrameAndCompletesOnce() {
        Fixture fixture = new Fixture();
        try (Awaiting child = fixture.await("slave")) {
            fixture.receive(envelope("gateway", "slave", reply(0, 30, 2)));
            ReadPropertyMessageReply timeout = reply(30, 0, 2);
            timeout.setSuccess(false);
            timeout.setCode("TIMEOUT");
            fixture.receive(envelope("gateway", "slave", timeout));
            assertEquals(2, child.replies.size());
            assertFalse(child.replies.get(1).isSuccess());
            assertEquals(30, child.properties.size());
            assertEquals(1, child.completed.get());
            assertEquals(0, fixture.broker.pendingCount());
        }
    }

    @Test
    void singleFrameChildReplyIsDeliveredOnce() {
        Fixture fixture = new Fixture();
        try (Awaiting child = fixture.await("slave")) {
            fixture.receive(envelope("gateway", "slave", reply(0, 34, 1)));
            assertEquals(1, child.replies.size());
            assertEquals(34, child.properties.size());
            assertEquals(1, child.completed.get());
            assertTrue(fixture.broker.forwarded.stream().noneMatch(reply -> "slave".equals(reply.getDeviceId())),
                "A completed child reply must not be sent to other nodes a second time");
        }
    }

    @Test
    void fourFramesDoNotCompleteAfterTheSecondFrame() {
        Fixture fixture = new Fixture();
        try (Awaiting child = fixture.await("slave")) {
            for (int frame = 0; frame < 4; frame++) {
                fixture.receive(envelope("gateway", "slave", reply(frame * 10, frame == 3 ? 4 : 10, 4)));
                assertEquals(frame + 1, child.replies.size());
                assertEquals(frame == 3 ? 1 : 0, child.completed.get());
            }
            assertEquals(34, child.properties.size());
            assertEquals(0, fixture.broker.pendingCount());
        }
    }

    @Test
    void consecutivePollingRoundsReleaseTheirAwaitersAndCollectAllProperties() {
        Fixture fixture = new Fixture();
        for (int round = 0; round < 10; round++) {
            try (Awaiting child = fixture.await("slave")) {
                fixture.receive(envelope("gateway", "slave", reply(0, 30, 2)));
                assertEquals(0, child.completed.get());
                fixture.receive(envelope("gateway", "slave", reply(30, 4, 2)));
                assertEquals(34, child.properties.size());
                assertEquals(2, child.replies.size());
                assertEquals(1, child.completed.get());
                assertEquals(0, fixture.broker.pendingCount());
            }
        }
    }

    @Test
    void replyInsideANonReplyChildEnvelopeStillReachesTheChild() {
        Fixture fixture = new Fixture();
        try (Awaiting child = fixture.await("slave")) {
            ChildDeviceMessage message = new ChildDeviceMessage();
            message.setDeviceId("gateway");
            message.setChildDeviceId("slave");
            message.setChildDeviceMessage(reply(0, 34, 1));
            fixture.receive(message);
            assertEquals(1, child.replies.size());
            assertEquals(1, child.completed.get());
        }
    }

    @Test
    void ordinaryDeviceReplyStillReachesItsAwaiter() {
        Fixture fixture = new Fixture();
        try (Awaiting child = fixture.await("slave")) {
            fixture.receive(reply(0, 34, 1));
            assertEquals(1, child.replies.size());
            assertEquals(1, child.completed.get());
        }
    }

    @Test
    void nestedGatewayReplyRecursesOnceAtEveryLevel() {
        Fixture fixture = new Fixture();
        try (Awaiting child = fixture.await("slave");
             Awaiting gateway = fixture.await("gateway");
             Awaiting root = fixture.await("root")) {
            for (int frame = 0; frame < 2; frame++) {
                ReadPropertyMessageReply body = reply(frame * 30, frame == 0 ? 30 : 4, 2);
                fixture.receive(envelope("root", "gateway", envelope("gateway", "slave", body)));
                assertEquals(frame + 1, child.replies.size());
                assertEquals(frame + 1, gateway.replies.size());
                assertEquals(frame + 1, root.replies.size());
            }
            assertEquals(34, child.properties.size());
            assertEquals(1, child.completed.get());
            assertEquals(1, gateway.completed.get());
            assertEquals(1, root.completed.get());
        }
    }

    @Test
    void unidentifiedGatewayEnvelopeStillDeliversAnIdentifiedChildReplyOnce() {
        for (String missingId : new String[]{null, ""}) {
            Fixture fixture = new Fixture();
            try (Awaiting child = fixture.await("slave")) {
                for (int frame = 0; frame < 2; frame++) {
                    ChildDeviceMessageReply message = envelope("gateway", "slave", reply(frame * 30, frame == 0 ? 30 : 4, 2));
                    message.setMessageId(missingId);
                    fixture.receive(message);
                    assertEquals(frame + 1, child.replies.size());
                    assertEquals(frame, child.completed.get());
                }
                assertEquals(34, child.properties.size());
            }
        }
    }

    @Test
    void brokerCorrelatesAnUnidentifiedGatewayEnvelopeAndDeliversItsChildOnce() {
        Fixture fixture = new Fixture();
        ChildDeviceMessage request = new ChildDeviceMessage();
        request.setDeviceId("gateway");
        request.setMessageId("read-34");
        request.setTimestamp(1000);
        request.addHeader(Headers.replyNoMessageId, true);
        try (Awaiting child = fixture.await("slave");
             Awaiting gateway = new Awaiting(fixture.broker, request)) {
            ChildDeviceMessageReply message = envelope("gateway", "slave", reply(0, 34, 1));
            message.setMessageId(null);
            message.setTimestamp(2000);
            fixture.receive(message);
            assertEquals("read-34", message.getMessageId());
            assertEquals(1, gateway.replies.size());
            assertEquals(1, child.replies.size());
            assertEquals(1, child.completed.get());
            assertEquals(34, child.properties.size());
            assertEquals(1, gateway.completed.get());
            assertEquals(0, fixture.broker.pendingCount());
        }
    }

    @Test
    void unidentifiedOuterEnvelopeDoesNotDuplicateAnIdentifiedNestedGatewayReply() {
        Fixture fixture = new Fixture();
        try (Awaiting child = fixture.await("slave");
             Awaiting gateway = fixture.await("gateway")) {
            for (int frame = 0; frame < 2; frame++) {
                ChildDeviceMessageReply middle = envelope("gateway", "slave", reply(frame * 30, frame == 0 ? 30 : 4, 2));
                ChildDeviceMessageReply message = envelope("root", "gateway", middle);
                message.setMessageId(null);
                fixture.receive(message);
                assertEquals(frame + 1, gateway.replies.size());
                assertEquals(frame + 1, child.replies.size());
                assertEquals(frame, child.completed.get());
            }
            assertEquals(34, child.properties.size());
            assertEquals(1, gateway.completed.get());
        }
    }

    private static ReadPropertyMessageReply reply(int first, int count, int frames) {
        ReadPropertyMessageReply reply = new ReadPropertyMessageReply();
        reply.setDeviceId("slave");
        reply.setMessageId("read-34");
        reply.setSuccess(true);
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = first; i < first + count; i++) properties.put("p" + i, i + 1.25);
        reply.setProperties(properties);
        if (frames > 1) {
            reply.addHeader(Headers.fragmentBodyMessageId, "read-34");
            reply.addHeader(Headers.fragmentNumber, frames);
        }
        return reply;
    }

    private static ChildDeviceMessageReply envelope(String gateway, String child, DeviceMessage body) {
        ChildDeviceMessageReply reply = new ChildDeviceMessageReply();
        reply.setDeviceId(gateway);
        reply.setChildDeviceId(child);
        reply.setMessageId(body.getMessageId());
        reply.setChildDeviceMessage(body);
        body.getHeader(Headers.fragmentBodyMessageId).ifPresent(id -> reply.addHeader(Headers.fragmentBodyMessageId, id));
        body.getHeader(Headers.fragmentNumber).ifPresent(count -> reply.addHeader(Headers.fragmentNumber, count));
        return reply;
    }

    private static final class Fixture {
        final LocalBroker broker = new LocalBroker();
        final DeviceMessageConnector connector;

        Fixture() {
            EventBus eventBus = mock(EventBus.class);
            DeviceRegistry registry = mock(DeviceRegistry.class);
            DeviceOperator device = mock(DeviceOperator.class);
            when(registry.getDevice(anyString())).thenReturn(Mono.just(device));
            when(device.getSelfConfigs(any(String[].class))).thenReturn(Mono.just(Values.of(Collections.emptyMap())));
            when(eventBus.publish(anyString(), any(Message.class))).thenReturn(Mono.just(1L));
            connector = new DeviceMessageConnector(eventBus, registry, broker, mock(DeviceSessionManager.class));
        }

        Awaiting await(String device) { return new Awaiting(broker, device); }

        void receive(DeviceMessage message) {
            assertEquals(Boolean.TRUE, connector.handleMessage(null, message).block(Duration.ofSeconds(2)));
        }
    }

    private static final class Awaiting implements AutoCloseable {
        final List<DeviceMessageReply> replies = new ArrayList<>();
        final Map<String, Object> properties = new LinkedHashMap<>();
        final AtomicInteger completed = new AtomicInteger();
        final Disposable subscription;

        Awaiting(LocalBroker broker, String device) {
            this(broker.handleReply(device, "read-34", Duration.ofSeconds(10)));
        }

        Awaiting(LocalBroker broker, DeviceMessage request) {
            this(broker.handleReply(request, Duration.ofSeconds(10)));
        }

        private Awaiting(Flux<DeviceMessageReply> responses) {
            subscription = responses
                .doOnNext(reply -> {
                    replies.add(reply);
                    if (reply instanceof ReadPropertyMessageReply && reply.isSuccess()) {
                        properties.putAll(((ReadPropertyMessageReply) reply).getProperties());
                    }
                })
                .doOnComplete(completed::incrementAndGet)
                .subscribe();
        }

        @Override public void close() { subscription.dispose(); }
    }

    private static final class LocalBroker extends AbstractDeviceOperationBroker {
        final List<DeviceMessageReply> forwarded = new ArrayList<>();
        int pendingCount() { return replyProcessor.size(); }
        @Override public Flux<DeviceStateInfo> getDeviceState(String server, Collection<String> ids) { return Flux.empty(); }
        @Override public Disposable handleGetDeviceState(String server, Function<Publisher<String>, Flux<DeviceStateInfo>> handler) { return Disposables.disposed(); }
        @Override public Mono<Integer> send(String server, Publisher<? extends Message> messages) { return Mono.just(0); }
        @Override public Mono<Integer> send(Publisher<? extends BroadcastMessage> messages) { return Mono.just(0); }
        @Override public Flux<Message> handleSendToDeviceMessage(String server) { return Flux.empty(); }
        @Override protected Mono<Void> doReply(DeviceMessageReply reply) {
            return Mono.fromRunnable(() -> forwarded.add(reply));
        }
    }
}
