package org.jetlinks.community.things.data.operations;

import org.jetlinks.community.timeseries.TimeSeriesData;
import org.jetlinks.core.message.ChildDeviceMessageReply;
import org.jetlinks.core.message.DeviceMessage;
import org.jetlinks.core.message.Headers;
import org.jetlinks.core.message.ThingMessage;
import org.jetlinks.core.message.property.ReadPropertyMessage;
import org.jetlinks.core.message.property.ReadPropertyMessageReply;
import org.jetlinks.core.message.property.ReportPropertyMessage;
import org.jetlinks.core.things.ThingsRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MessagePersistenceHeadersTest {

    @Test
    void internalReadRequestReplyAndChildEnvelopeProduceNoDatabaseWrites() {
        RecordingSaveOperations storage = new RecordingSaveOperations();
        ReadPropertyMessage request = new ReadPropertyMessage();
        request.setDeviceId("slave");
        request.setProperties(Collections.singletonList("value"));
        ReadPropertyMessageReply reply = reply();
        ChildDeviceMessageReply envelope = new ChildDeviceMessageReply();
        envelope.setDeviceId("gateway");
        envelope.setChildDeviceId("slave");
        envelope.setChildDeviceMessage(reply);
        for (DeviceMessage message : new DeviceMessage[]{request, reply, envelope}) {
            identify(message);
            message.addHeader(Headers.ignoreLog, true);
            message.addHeader(Headers.ignoreStorage, true);
        }

        storage.save(Flux.just(request, reply, envelope)).block();

        assertTrue(storage.metrics.isEmpty());
        assertEquals(0, storage.propertyConversions);
    }

    @Test
    void pollReportStillWritesBothPropertiesAndItsMessageRecord() {
        RecordingSaveOperations storage = new RecordingSaveOperations();
        ReportPropertyMessage report = new ReportPropertyMessage();
        report.setDeviceId("slave");
        identify(report);
        report.setProperties(Collections.singletonMap("value", 42));
        report.addHeader("pollSource", "MODBUS");

        storage.save(report).block();

        assertEquals(1, storage.propertyConversions);
        assertEquals(2, storage.metrics.size());
        assertTrue(storage.metrics.contains("property-data"));
        assertTrue(storage.metrics.contains("device_log_product"));
    }

    @Test
    void unmarkedReadRepliesRetainTheExistingStorageBehavior() {
        RecordingSaveOperations storage = new RecordingSaveOperations();
        ReadPropertyMessageReply reply = reply();
        identify(reply);

        storage.save(reply).block();

        assertEquals(1, storage.propertyConversions);
        assertEquals(2, storage.metrics.size());
    }

    private static ReadPropertyMessageReply reply() {
        ReadPropertyMessageReply reply = new ReadPropertyMessageReply();
        reply.setDeviceId("slave");
        reply.setSuccess(true);
        reply.setProperties(Collections.singletonMap("value", 42));
        return reply;
    }

    private static void identify(DeviceMessage message) {
        message.messageId("read-1");
        message.timestamp(1000);
        message.addHeader(Headers.productId, "product");
    }

    private static final class RecordingSaveOperations extends AbstractSaveOperations {
        final List<String> metrics = new ArrayList<>();
        int propertyConversions;

        RecordingSaveOperations() {
            super(mock(ThingsRegistry.class), MetricBuilder.DEFAULT, new DataSettings());
        }

        @Override
        protected Flux<Tuple2<String, TimeSeriesData>> convertProperties(
                String templateId, ThingMessage message, Map<String, Object> properties, Map<String, Long> sourceTimes) {
            propertyConversions++;
            return Flux.just(Tuples.of("property-data", TimeSeriesData.of(message.getTimestamp(), properties)));
        }

        @Override
        protected Mono<Void> doSave(String metric, TimeSeriesData data) {
            return Mono.fromRunnable(() -> metrics.add(metric));
        }

        @Override
        protected Mono<Void> doSave(String metric, Flux<TimeSeriesData> data) {
            return data.concatMap(row -> doSave(metric, row)).then();
        }
    }
}
