package gp.saas.legacy.web;

import gp.saas.apitoken.service.ApiTokenScopeService;
import org.jetlinks.community.device.entity.*;
import org.jetlinks.community.device.service.*;
import org.jetlinks.community.device.service.data.*;
import org.jetlinks.community.timescaledb.thing.simulation.*;
import org.jetlinks.core.metadata.*;
import org.jetlinks.core.metadata.types.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DeviceDataSimulationControllerTest {
    LocalDeviceInstanceService devices=mock(LocalDeviceInstanceService.class);
    LocalDeviceProductService products=mock(LocalDeviceProductService.class);
    ApiTokenScopeService scope=mock(ApiTokenScopeService.class);
    SimulationTaskService service=mock(SimulationTaskService.class);
    DeviceDataService data=mock(DeviceDataService.class);
    DeviceMetadata effective=mock(DeviceMetadata.class), productMetadata=mock(DeviceMetadata.class);
    PropertyMetadata number=mock(PropertyMetadata.class), text=mock(PropertyMetadata.class);
    DeviceProductEntity product=new DeviceProductEntity();
    DeviceDataSimulationController controller;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        DeviceInstanceEntity device=new DeviceInstanceEntity();device.setId("d");device.setProductId("p");
        product.setId("p");product.setStorePolicy("timescaledb-row");
        when(devices.findById("d")).thenReturn(Mono.just(device));
        when(products.findById("p")).thenReturn(Mono.just(product));
        when(devices.getMetadata("d")).thenReturn(Mono.just(effective));
        when(products.getMetadata("p")).thenReturn(Mono.just(productMetadata));
        when(scope.requireDevice("d","p")).thenReturn(Mono.empty());
        DoubleType numeric=new DoubleType();numeric.setScale(3);
        when(number.getId()).thenReturn("E");when(number.getValueType()).thenReturn(numeric);
        when(text.getId()).thenReturn("label");when(text.getValueType()).thenReturn(new StringType());
        when(effective.getProperties()).thenReturn(List.of(number,text));
        when(effective.getPropertyOrNull("E")).thenReturn(number);when(effective.getPropertyOrNull("label")).thenReturn(text);
        when(productMetadata.getPropertyOrNull("E")).thenReturn(number);
        ObjectProvider<SimulationTaskService> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(service);
        controller=new DeviceDataSimulationController(devices,products,data,new DeviceDataStorageProperties(),scope,provider);
    }
    DeviceDataSimulationController.Request request(String property) {
        var r=new DeviceDataSimulationController.Request();r.deviceId="d";r.propertyId=property;return r;
    }
    @Test void effectiveNumericPropertiesIncludeDeviceCustomSnapshotCandidates() {
        List<SimulationSpec> result=controller.properties("d").collectList().block();
        assertThat(result).hasSize(1);assertThat(result.get(0).scale).isEqualTo(3);assertThat(result.get(0).snapshotEligible).isTrue();
        when(productMetadata.getPropertyOrNull("E")).thenReturn(null);
        assertThat(controller.properties("d").single().block().snapshotEligible).isTrue();
    }
    @Test void rejectsNonNumericAndWrongStorageBeforeAccessingSimulationData() {
        assertThatThrownBy(()->controller.preview(request("label")).block()).hasMessageContaining("数值");
        product.setStorePolicy("other");
        assertThatThrownBy(()->controller.preview(request("E")).block()).hasMessageContaining("行模式");
        verify(service,never()).preview(any());verifyNoInteractions(data);
    }
    @Test void scopeDenialPreventsPropertiesAndPreflight() {
        when(scope.requireDevice("d","p")).thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)));
        assertThatThrownBy(()->controller.properties("d").collectList().block()).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.preview(request("E")).block()).isInstanceOf(ResponseStatusException.class);
        verify(service,never()).preview(any());
    }
    @Test void idempotencyKeyIsCheckedBeforeAnyStorageInitialization() {
        assertThatThrownBy(()->controller.submit(request("E")).block()).hasMessageContaining("幂等");
        verifyNoInteractions(data);verify(service,never()).submit(any(),any());
    }
}
