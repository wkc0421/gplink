package org.jetlinks.community.device.web;

import org.hswebframework.ezorm.rdb.mapping.ReactiveUpdate;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.enums.DeviceState;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.web.response.GatewayDeviceInfo;
import org.jetlinks.core.ProtocolSupport;
import org.jetlinks.core.device.DeviceConfigKey;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GatewayDeviceBindingTest {
    final GatewayDeviceController controller = spy(new GatewayDeviceController());
    final LocalDeviceInstanceService devices = mock(LocalDeviceInstanceService.class);
    final DeviceRegistry registry = mock(DeviceRegistry.class);
    final DeviceOperator parent = mock(DeviceOperator.class);
    final ProtocolSupport protocol = mock(ProtocolSupport.class);

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(controller, "instanceService", devices);
        ReflectionTestUtils.setField(controller, "registry", registry);
        entity("parent", DeviceState.offline);
        when(registry.getDevice("parent")).thenReturn(Mono.just(parent));
        when(parent.getProtocol()).thenReturn(Mono.just(protocol));
        // A protocol need not consume the child Flux. Synchronization must still happen.
        when(protocol.onChildBind(eq(parent), any())).thenReturn(Mono.empty());
    }

    private void entity(String id, DeviceState state) {
        DeviceInstanceEntity entity = new DeviceInstanceEntity();
        entity.setId(id);
        entity.setState(state);
        entity.setParentId("parent".equals(id) ? null : "parent");
        when(devices.findById(id)).thenReturn(Mono.just(entity));
    }

    private DeviceOperator child(String id) {
        entity(id, DeviceState.offline);
        DeviceOperator child = mock(DeviceOperator.class);
        when(registry.getDevice(id)).thenReturn(Mono.just(child));
        return child;
    }

    @Test
    void repairsMissingRuntimeParentEvenWhenDatabaseAlreadyBoundAndProtocolIgnoresChildren() {
        DeviceOperator child = child("child");
        AtomicReference<String> storedParent = new AtomicReference<>();
        when(child.setConfig(DeviceConfigKey.parentGatewayId, "parent"))
            .thenReturn(Mono.fromSupplier(() -> { storedParent.set("parent"); return true; }));
        when(child.getSelfConfig(DeviceConfigKey.parentGatewayId))
            .thenAnswer(ignored -> Mono.justOrEmpty(storedParent.get()));

        StepVerifier.create(controller.synchronizeBindingsAndNotify("parent", Collections.singletonList("child"), true))
            .verifyComplete();

        assertEquals("parent", storedParent.get());
        verify(protocol).onChildBind(eq(parent), any());
        verify(child).getSelfConfig(DeviceConfigKey.parentGatewayId);
    }

    @Test
    void callbackWaitsForWriteAndReadBack() {
        DeviceOperator child = child("child");
        Sinks.One<Boolean> write = Sinks.one();
        Sinks.One<String> read = Sinks.one();
        when(child.setConfig(DeviceConfigKey.parentGatewayId, "parent")).thenReturn(write.asMono());
        when(child.getSelfConfig(DeviceConfigKey.parentGatewayId)).thenReturn(read.asMono());

        StepVerifier.create(controller.synchronizeBindingsAndNotify("parent", Collections.singletonList("child"), true))
            .then(() -> verify(protocol, never()).onChildBind(any(), any()))
            .then(() -> write.tryEmitValue(true))
            .then(() -> verify(protocol, never()).onChildBind(any(), any()))
            .then(() -> read.tryEmitValue("parent"))
            .verifyComplete();
        verify(protocol).onChildBind(eq(parent), any());
    }

    @Test
    void writeFalseCannotReportBindingSuccess() {
        DeviceOperator child = child("child");
        when(child.setConfig(DeviceConfigKey.parentGatewayId, "parent")).thenReturn(Mono.just(false));
        StepVerifier.create(controller.synchronizeBindingsAndNotify("parent", Collections.singletonList("child"), true))
            .expectErrorMessage("Failed to synchronize parent for device [child]").verify();
        verify(protocol, never()).onChildBind(any(), any());
        verify(child, never()).getSelfConfig(DeviceConfigKey.parentGatewayId);
    }

    @Test
    void missingOrWrongReadBackCannotReportBindingSuccess() {
        for (Mono<String> value : Arrays.asList(Mono.<String>empty(), Mono.just("another-parent"))) {
            DeviceOperator child = child("child");
            when(child.setConfig(DeviceConfigKey.parentGatewayId, "parent")).thenReturn(Mono.just(true));
            when(child.getSelfConfig(DeviceConfigKey.parentGatewayId)).thenReturn(value);
            StepVerifier.create(controller.synchronizeBindingsAndNotify("parent", Collections.singletonList("child"), true))
                .expectErrorMessage("Parent binding verification failed for device [child]").verify();
        }
        verify(protocol, never()).onChildBind(any(), any());
    }

    @Test
    void activeDeviceMissingFromRegistryFailsInBothModes() {
        entity("child", DeviceState.offline);
        when(registry.getDevice("child")).thenReturn(Mono.empty());
        for (boolean strict : new boolean[]{false, true}) {
            StepVerifier.create(controller.validateBindingDevices("parent", Collections.singletonList("child"), strict))
                .expectErrorMessage("Device [child] is not registered; activate it before binding").verify();
        }
    }

    @Test
    void inactiveDeviceCanStillBindNormallyButStrictWizardRequiresRegistration() {
        entity("child", DeviceState.notActive);
        when(registry.getDevice("child")).thenReturn(Mono.empty());
        StepVerifier.create(controller.validateBindingDevices("parent", Collections.singletonList("child"), false))
            .verifyComplete();
        StepVerifier.create(controller.synchronizeBindings("parent", Collections.singletonList("child"), false))
            .verifyComplete();
        StepVerifier.create(controller.validateBindingDevices("parent", Collections.singletonList("child"), true))
            .expectErrorMessage("Device [child] is not activated").verify();
    }

    @Test
    void strictWizardAlsoRequiresRegisteredParent() {
        entity("parent", DeviceState.notActive);
        when(registry.getDevice("parent")).thenReturn(Mono.empty());
        StepVerifier.create(controller.validateBindingDevices("parent", Collections.emptyList(), true))
            .expectErrorMessage("Device [parent] is not activated").verify();
    }

    @Test
    void staleRegistryEntryDoesNotMakeInactiveChildReadyForStrictBinding() {
        entity("child", DeviceState.notActive);
        when(registry.getDevice("child")).thenReturn(Mono.just(mock(DeviceOperator.class)));
        StepVerifier.create(controller.validateBindingDevices("parent", Collections.singletonList("child"), true))
            .expectErrorMessage("Device [child] is not activated").verify();
    }

    @Test
    void mixedNormalBatchStillSynchronizesActiveChildren() {
        entity("inactive", DeviceState.notActive);
        when(registry.getDevice("inactive")).thenReturn(Mono.empty());
        DeviceOperator child = child("active");
        when(child.setConfig(DeviceConfigKey.parentGatewayId, "parent")).thenReturn(Mono.just(true));
        when(child.getSelfConfig(DeviceConfigKey.parentGatewayId)).thenReturn(Mono.just("parent"));
        StepVerifier.create(controller.synchronizeBindingsAndNotify("parent", Arrays.asList("inactive", "active"), false))
            .verifyComplete();
        verify(child).setConfig(DeviceConfigKey.parentGatewayId, "parent");
        verify(protocol).onChildBind(eq(parent), any());
    }

    @Test
    void strictPublicSingleAndBatchEndpointsRejectUnregisteredChildBeforeDatabaseUpdate() {
        entity("child", DeviceState.offline);
        when(registry.getDevice("child")).thenReturn(Mono.empty());
        when(devices.checkCyclicDependency("child", "parent")).thenReturn(Mono.empty());
        doReturn(Mono.just(new GatewayDeviceInfo())).when(controller).getGatewayInfo("parent");

        StepVerifier.create(controller.bindDevice("parent", "child", true))
            .expectErrorMessage("Device [child] is not registered; activate it before binding").verify();
        StepVerifier.create(controller.bindDevice("parent", Mono.just(Collections.singletonList("child")), true))
            .expectErrorMessage("Device [child] is not registered; activate it before binding").verify();
        verify(devices, never()).createUpdate();
        verify(protocol, never()).onChildBind(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicBatchEndpointWaitsForDatabaseSyncReadBackAndProtocolBeforeResult() {
        List<String> steps = new ArrayList<>();
        DeviceOperator child = child("child");
        when(devices.checkCyclicDependency("child", "parent")).thenReturn(Mono.empty());
        ReactiveUpdate<DeviceInstanceEntity> update = mock(ReactiveUpdate.class, RETURNS_SELF);
        when(devices.createUpdate()).thenReturn(update);
        when(update.execute()).thenReturn(Mono.fromSupplier(() -> { steps.add("database"); return 1; }));
        when(child.setConfig(DeviceConfigKey.parentGatewayId, "parent"))
            .thenReturn(Mono.fromSupplier(() -> { steps.add("write"); return true; }));
        when(child.getSelfConfig(DeviceConfigKey.parentGatewayId))
            .thenReturn(Mono.fromSupplier(() -> { steps.add("read"); return "parent"; }));
        when(protocol.onChildBind(eq(parent), any()))
            .thenReturn(Mono.fromRunnable(() -> steps.add("protocol")));
        doReturn(Mono.fromSupplier(() -> { steps.add("result"); return new GatewayDeviceInfo(); }))
            .when(controller).getGatewayInfo("parent");

        StepVerifier.create(controller.bindDevice("parent", Mono.just(Arrays.asList("child", "child")), true))
            .expectNextCount(1).verifyComplete();
        assertEquals(Arrays.asList("database", "write", "read", "protocol", "result"), steps);
        verify(child, times(1)).setConfig(DeviceConfigKey.parentGatewayId, "parent");
    }
}
