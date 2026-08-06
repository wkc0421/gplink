package org.jetlinks.community.network.mqtt.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jetlinks.core.message.codec.SimpleMqttMessage;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class VertxMqttClientTest {

    @Test
    void shouldReleasePendingPayloadWhenSubscriberCancels() {
        VertxMqttClient client = new VertxMqttClient("pending-cancel-test");
        client.setLoading(true);
        ByteBuf payload = Unpooled.buffer().writeByte(1);

        Disposable subscription = client.publish(message(payload)).subscribe();
        assertEquals(1, payload.refCnt());

        subscription.dispose();

        assertEquals(0, payload.refCnt());
    }

    @Test
    void shouldReleasePendingPayloadWhenClientShutsDown() {
        VertxMqttClient client = new VertxMqttClient("pending-shutdown-test");
        client.setLoading(true);
        ByteBuf payload = Unpooled.buffer().writeByte(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        client.publish(message(payload)).subscribe(null, error::set);
        client.shutdown();

        assertEquals(0, payload.refCnt());
        assertNotNull(error.get());
    }

    private SimpleMqttMessage message(ByteBuf payload) {
        SimpleMqttMessage message = new SimpleMqttMessage();
        message.setTopic("test/topic");
        message.setPayload(payload);
        return message;
    }
}
