package org.jetlinks.community.device.message.writer;

import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.community.things.data.ThingsDataWriter;
import org.jetlinks.core.message.Headers;
import org.jetlinks.core.message.property.ReadPropertyMessageReply;
import org.jetlinks.core.message.property.ReportPropertyMessage;
import org.jetlinks.core.things.ThingProperty;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TimeSeriesMessageWriterConnectorTest {

    @Test
    void internalReadReplyReachesNeitherHistoryNorTheLocalPropertyWriter() {
        DeviceDataService history = mock(DeviceDataService.class);
        ThingsDataWriter properties = mock(ThingsDataWriter.class);
        ReadPropertyMessageReply reply = new ReadPropertyMessageReply();
        reply.setDeviceId("slave");
        reply.setProperties(Collections.singletonMap("value", 42));
        reply.addHeader(Headers.ignoreLog, true);
        reply.addHeader(Headers.ignoreStorage, true);

        new TimeSeriesMessageWriterConnector(history, properties).writeDeviceMessageToTs(reply).block();

        verifyNoInteractions(history, properties);
    }

    @Test
    void propertyReportStillReachesBothWriters() {
        DeviceDataService history = mock(DeviceDataService.class);
        ThingsDataWriter properties = mock(ThingsDataWriter.class);
        ReportPropertyMessage report = new ReportPropertyMessage();
        report.setDeviceId("slave");
        report.setTimestamp(1000);
        report.setProperties(Collections.singletonMap("value", 42));
        when(history.saveDeviceMessage(report)).thenReturn(Mono.empty());
        when(properties.updateProperty(eq("device"), eq("slave"), any(ThingProperty.class)))
            .thenReturn(Mono.empty());

        new TimeSeriesMessageWriterConnector(history, properties).writeDeviceMessageToTs(report).block();

        verify(history).saveDeviceMessage(report);
        verify(properties).updateProperty(eq("device"), eq("slave"), any(ThingProperty.class));
    }

    @Test
    void suppressingOnlyTheLogDoesNotSkipPropertyStorage() {
        DeviceDataService history = mock(DeviceDataService.class);
        ThingsDataWriter properties = mock(ThingsDataWriter.class);
        ReadPropertyMessageReply reply = new ReadPropertyMessageReply();
        reply.setDeviceId("slave");
        reply.setProperties(Collections.singletonMap("value", 42));
        reply.addHeader(Headers.ignoreLog, true);
        when(history.saveDeviceMessage(reply)).thenReturn(Mono.empty());
        when(properties.updateProperty(eq("device"), eq("slave"), any(ThingProperty.class)))
            .thenReturn(Mono.empty());

        new TimeSeriesMessageWriterConnector(history, properties).writeDeviceMessageToTs(reply).block();

        verify(history).saveDeviceMessage(reply);
        verify(properties).updateProperty(eq("device"), eq("slave"), any(ThingProperty.class));
    }
}
