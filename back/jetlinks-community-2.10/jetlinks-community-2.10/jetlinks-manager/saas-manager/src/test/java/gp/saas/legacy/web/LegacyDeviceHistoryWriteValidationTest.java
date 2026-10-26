package gp.saas.legacy.web;

import gp.saas.legacy.dto.SimpleDeviceProperty;
import org.hswebframework.ezorm.rdb.executor.reactive.ReactiveSqlExecutor;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.enums.DeviceProductState;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.message.DeviceMessage;
import org.jetlinks.core.message.property.ReportPropertyMessage;
import org.jetlinks.core.metadata.DeviceMetadata;
import org.jetlinks.core.metadata.PropertyMetadata;
import org.jetlinks.community.things.data.ThingsDataConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegacyDeviceHistoryWriteValidationTest {

    private LocalDeviceInstanceService devices;
    private LocalDeviceProductService products;
    private DeviceRegistry registry;
    private DeviceOperator operator;
    private DeviceMetadata metadata;
    private PropertyMetadata propertyMetadata;
    private DeviceDataService data;
    private LegacyDeviceController controller;

    @BeforeEach
    void setUp() {
        devices = mock(LocalDeviceInstanceService.class);
        products = mock(LocalDeviceProductService.class);
        registry = mock(DeviceRegistry.class);
        data = mock(DeviceDataService.class);
        controller = new LegacyDeviceController(devices,
                                                products,
                                                registry,
                                                data,
                                                mock(ReactiveSqlExecutor.class));
    }

    @Test
    void nullValueReturnsBadRequestBeforeWriting() {
        assertRejectedWithoutWriting(List.of(item(null, 1_700_000_000_000L)));
    }

    @Test
    void nullTimestampReturnsBadRequestBeforeWriting() {
        assertRejectedWithoutWriting(List.of(item(1, null)));
    }

    @Test
    void mixedBatchIsRejectedBeforeAnyItemIsWritten() {
        assertRejectedWithoutWriting(Arrays.asList(item(1, 1_700_000_000_000L),
                                                 item(null, 1_700_000_000_001L)));
    }

    @Test
    void undeployedProductReturnsConflictWithoutWriting() {
        stubDeviceAndProduct(DeviceProductState.unregistered.getValue());

        StepVerifier.create(controller.writeDeviceProperty("device-1", "counter",
                                                           List.of(item(1, 1_700_000_000_000L))))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
            })
            .verify();

        verifyNoInteractions(data);
    }

    @Test
    void deployedProductWithUnregisteredDeviceReturnsConflictWithoutWriting() {
        stubDeviceAndProduct(DeviceProductState.registered.getValue());
        when(registry.getDevice("device-1")).thenReturn(Mono.empty());

        StepVerifier.create(controller.writeDeviceProperty("device-1", "counter",
                                                           List.of(item(1, 1_700_000_000_000L))))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
            })
            .verify();

        verifyNoInteractions(data);
    }

    @Test
    void missingDevicePropertyReturnsBadRequestWithoutWriting() {
        stubDeviceAndProduct(DeviceProductState.registered.getValue());

        StepVerifier.create(controller.writeDeviceProperty("device-1", "missing_counter",
                                                           List.of(item(3.5, 1_700_000_000_000L))))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            })
            .verify();

        verifyNoInteractions(data);
    }

    @Test
    void ignoredStoragePropertyReturnsConflictWithoutWriting() {
        stubDeviceAndProduct(DeviceProductState.registered.getValue());
        when(propertyMetadata.getExpand(ThingsDataConstants.propertyStorageType))
            .thenReturn(Optional.of(ThingsDataConstants.propertyStorageTypeIgnore));

        StepVerifier.create(controller.writeDeviceProperty("device-1", "counter",
                                                           List.of(item(3.5, 1_700_000_000_000L))))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
            })
            .verify();

        verifyNoInteractions(data);
    }

    @Test
    void unavailableDeviceMetadataReturnsConflictWithoutWriting() {
        stubDeviceAndProduct(DeviceProductState.registered.getValue());
        when(operator.getMetadata()).thenReturn(Mono.empty());

        StepVerifier.create(controller.writeDeviceProperty("device-1", "counter",
                                                           List.of(item(3.5, 1_700_000_000_000L))))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
            })
            .verify();

        verifyNoInteractions(data);
    }

    @Test
    void deployedProductWritesCompleteBatchAndPropagatesStorageFailure() {
        stubDeviceAndProduct(DeviceProductState.registered.getValue());
        AtomicReference<List<DeviceMessage>> saved = new AtomicReference<>();
        when(data.saveDeviceMessage(any(Publisher.class)))
            .thenAnswer(invocation -> Flux.from((Publisher<DeviceMessage>) invocation.getArgument(0))
                .collectList()
                .doOnNext(saved::set)
                .then());

        StepVerifier.create(controller.writeDeviceProperty("device-1", "counter",
                                                           List.of(item(1, 1_700_000_000_000L),
                                                                   item(2, 1_700_000_000_001L))))
            .verifyComplete();

        assertThat(saved.get()).hasSize(2);
        assertThat(saved.get().get(0)).isInstanceOf(ReportPropertyMessage.class);
        assertThat(((ReportPropertyMessage) saved.get().get(0)).getProperties()).containsEntry("counter", 1);
        assertThat(((ReportPropertyMessage) saved.get().get(1)).getProperties()).containsEntry("counter", 2);
        verify(data).saveDeviceMessage(any(Publisher.class));
        verify(data, never()).saveDeviceMessage(any(DeviceMessage.class));

        RuntimeException storageFailure = new RuntimeException("storage failed");
        when(data.saveDeviceMessage(any(Publisher.class))).thenReturn(Mono.error(storageFailure));
        StepVerifier.create(controller.writeDeviceProperty("device-1", "counter",
                                                           List.of(item(3, 1_700_000_000_002L))))
            .expectErrorMatches(error -> error == storageFailure)
            .verify();
    }

    private void assertRejectedWithoutWriting(List<SimpleDeviceProperty> properties) {
        StepVerifier.create(controller.writeDeviceProperty("device-1", "counter", properties))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            })
            .verify();
        verifyNoInteractions(devices, products, registry, data);
    }

    private void stubDeviceAndProduct(Byte state) {
        DeviceInstanceEntity device = new DeviceInstanceEntity();
        device.setId("device-1");
        device.setProductId("product-1");
        when(devices.findById("device-1")).thenReturn(Mono.just(device));
        DeviceProductEntity product = new DeviceProductEntity();
        product.setState(state);
        when(products.findById("product-1")).thenReturn(Mono.just(product));
        if (DeviceProductState.registered.getValue().equals(state)) {
            operator = mock(DeviceOperator.class);
            metadata = mock(DeviceMetadata.class);
            propertyMetadata = mock(PropertyMetadata.class);
            when(registry.getDevice("device-1")).thenReturn(Mono.just(operator));
            when(operator.getMetadata()).thenReturn(Mono.just(metadata));
            when(metadata.getPropertyOrNull("counter")).thenReturn(propertyMetadata);
            when(propertyMetadata.getExpand(ThingsDataConstants.propertyStorageType))
                .thenReturn(Optional.empty());
        }
    }

    private SimpleDeviceProperty item(Object value, Long timestamp) {
        SimpleDeviceProperty item = new SimpleDeviceProperty();
        item.setValue(value);
        item.setTimestamp(timestamp);
        return item;
    }
}
