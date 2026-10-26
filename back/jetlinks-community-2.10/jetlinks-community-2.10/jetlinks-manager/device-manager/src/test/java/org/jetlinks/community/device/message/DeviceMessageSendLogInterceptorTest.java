package org.jetlinks.community.device.message;

import org.jetlinks.core.ProtocolSupport;
import org.jetlinks.core.Values;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.event.EventBus;
import org.jetlinks.core.message.ChildDeviceMessage;
import org.jetlinks.core.message.DeviceMessage;
import org.jetlinks.core.message.Headers;
import org.jetlinks.core.message.function.FunctionInvokeMessage;
import org.jetlinks.core.message.property.ReadPropertyMessage;
import org.jetlinks.core.message.property.ReadPropertyMessageReply;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeviceMessageSendLogInterceptorTest {

    @Test
    void modbusReadIsExcludedBeforePublishingButStillCompletesTheRequest() {
        Fixture fixture = new Fixture("modbus-rtu.v1");
        ReadPropertyMessage read = read();
        assertSame(read, fixture.interceptor.preSend(fixture.device, read).block());
        assertTrue(fixture.excludedWhenPublished.get());
        assertTrue(read.getHeaderOrDefault(Headers.ignoreStorage));
        assertEquals(Collections.singletonList("temperature"), read.getProperties());

        ReadPropertyMessageReply reply = read.newReply();
        assertSame(reply, fixture.interceptor.afterSent(fixture.device, read, Flux.just(reply)).blockFirst());
    }

    @Test
    void childReadMarksBothTheGatewayEnvelopeAndTheChildRequest() {
        Fixture fixture = new Fixture("modbus-rtu.v1");
        ReadPropertyMessage read = read();
        ChildDeviceMessage envelope = new ChildDeviceMessage();
        envelope.setDeviceId("gateway");
        envelope.setChildDeviceId("slave");
        envelope.setChildDeviceMessage(read);

        assertSame(envelope, fixture.interceptor.preSend(fixture.device, envelope).block());
        assertTrue(fixture.excludedWhenPublished.get());
        assertTrue(envelope.getHeaderOrDefault(Headers.ignoreStorage));
        assertTrue(read.getHeaderOrDefault(Headers.ignoreLog));
        assertTrue(read.getHeaderOrDefault(Headers.ignoreStorage));
    }

    @Test
    void otherProtocolsKeepReadRecords() {
        Fixture fixture = new Fixture("jetlinks.v1");
        ReadPropertyMessage read = read();
        fixture.interceptor.preSend(fixture.device, read).block();
        assertFalse(fixture.excludedWhenPublished.get());
        assertFalse(read.getHeaderOrDefault(Headers.ignoreLog));
        assertFalse(read.getHeaderOrDefault(Headers.ignoreStorage));
    }

    @Test
    void nonReadCommandsKeepTheirRecordsAndDoNotResolveTheProtocol() {
        Fixture fixture = new Fixture("modbus-rtu.v1");
        FunctionInvokeMessage function = new FunctionInvokeMessage();
        function.setDeviceId("slave");
        function.addHeader(Headers.force, true);
        fixture.interceptor.preSend(fixture.device, function).block();
        assertFalse(function.getHeaderOrDefault(Headers.ignoreLog));
        verify(fixture.device, never()).getProtocol();
    }

    @Test
    void protocolLookupFailureDoesNotPreventSendingOrLoggingTheRead() {
        Fixture fixture = new Fixture("modbus-rtu.v1");
        when(fixture.device.getProtocol()).thenReturn(Mono.error(new IllegalStateException("unavailable")));
        ReadPropertyMessage read = read();
        assertSame(read, fixture.interceptor.preSend(fixture.device, read).block());
        verify(fixture.eventBus).publish(anyString(), same(read));
        assertFalse(read.getHeaderOrDefault(Headers.ignoreLog));
    }

    private static ReadPropertyMessage read() {
        ReadPropertyMessage read = new ReadPropertyMessage();
        read.setDeviceId("slave");
        read.setMessageId("read-1");
        read.setProperties(Collections.singletonList("temperature"));
        return read;
    }

    private static final class Fixture {
        final DeviceOperator device = mock(DeviceOperator.class);
        final DeviceRegistry registry = mock(DeviceRegistry.class);
        final EventBus eventBus = mock(EventBus.class);
        final AtomicBoolean excludedWhenPublished = new AtomicBoolean();
        final DeviceMessageSendLogInterceptor interceptor = new DeviceMessageSendLogInterceptor(eventBus, registry);

        Fixture(String protocolId) {
            ProtocolSupport protocol = mock(ProtocolSupport.class);
            when(protocol.getId()).thenReturn(protocolId);
            when(device.getProtocol()).thenReturn(Mono.just(protocol));
            when(registry.getDevice(anyString())).thenReturn(Mono.just(device));
            when(device.getSelfConfigs(any(String[].class))).thenReturn(Mono.just(Values.of(Collections.emptyMap())));
            when(eventBus.publish(anyString(), any(DeviceMessage.class))).thenAnswer(call -> {
                DeviceMessage message = call.getArgument(1);
                excludedWhenPublished.set(message.getHeaderOrDefault(Headers.ignoreLog));
                return Mono.just(1L);
            });
        }
    }
}
