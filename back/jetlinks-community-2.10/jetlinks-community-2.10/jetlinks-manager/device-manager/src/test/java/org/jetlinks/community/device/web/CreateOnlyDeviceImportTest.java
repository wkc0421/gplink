package org.jetlinks.community.device.web;

import org.hswebframework.ezorm.rdb.exception.DuplicateKeyException;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.mapping.defaults.SaveResult;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceTagEntity;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.web.excel.DeviceExcelInfo;
import org.jetlinks.community.device.web.response.DeviceDeployResult;
import org.jetlinks.community.device.web.response.ImportDeviceInstanceResult;
import org.jetlinks.community.io.excel.AbstractImporter;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CreateOnlyDeviceImportTest {
    final LocalDeviceInstanceService devices = mock(LocalDeviceInstanceService.class);
    @SuppressWarnings("unchecked")
    final ReactiveRepository<DeviceTagEntity, String> tags = mock(ReactiveRepository.class);
    final TransactionalOperator tx = mock(TransactionalOperator.class);

    @SuppressWarnings("unchecked")
    DeviceInstanceController controller() {
        when(tx.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        when(tags.save(any(Flux.class))).thenReturn(Mono.just(SaveResult.of(0, 0)));
        return new DeviceInstanceController(devices, null, null, null, tags, null, null,
            null, tx, null, WebClient.builder(), null, null, null);
    }

    DeviceExcelInfo device(String id) {
        DeviceExcelInfo row = new DeviceExcelInfo();
        row.setId(id); row.setName("New name");
        DeviceInstanceEntity entity = new DeviceInstanceEntity();
        entity.setId(id); entity.setProductId("new-product"); row.setDevice(entity);
        return row;
    }

    @Test
    void duplicateIncludingConcurrentInsertNeverCallsUpsertOrDeployment() {
        DeviceInstanceController controller = controller();
        DeviceExcelInfo row = device("existing-device");
        when(devices.insert(row.getDevice())).thenReturn(Mono.error(new DuplicateKeyException(true, java.util.Collections.emptyList(), new IllegalStateException("duplicate"))));
        when(devices.deploy(any(Flux.class))).thenReturn(Flux.just(DeviceDeployResult.success(1)));
        StepVerifier.create(controller.insertImportedDevice(row, true))
            .expectErrorMatches(error -> error.getMessage().contains("已存在"))
            .verify();
        verify(devices).insert(row.getDevice());
        verify(devices, never()).save(any(org.reactivestreams.Publisher.class));
        verify(devices, never()).deploy(any(Flux.class));
    }

    @Test
    void deploymentFailureIsNotSuccessfulImport() {
        DeviceInstanceController controller = controller();
        DeviceExcelInfo row = device("device1");
        when(devices.insert(row.getDevice())).thenReturn(Mono.just(1));
        when(devices.deploy(any(Flux.class))).thenReturn(Flux.just(DeviceDeployResult.error("bad configuration")));
        StepVerifier.create(controller.insertImportedDevice(row, true)).expectErrorMatches(error -> error.getMessage().contains("启用失败")).verify();
        verify(tx).transactional(any(Mono.class));
    }

    @Test
    void successfulCreateOnlyInsertWithoutDeployment() {
        DeviceInstanceController controller = controller();
        DeviceExcelInfo row = device("device2");
        when(devices.insert(row.getDevice())).thenReturn(Mono.just(1));
        StepVerifier.create(controller.insertImportedDevice(row, false)).verifyComplete();
        verify(devices, never()).save(any(org.reactivestreams.Publisher.class));
        verify(devices, never()).deploy(any(Flux.class));
    }

    AbstractImporter.ImportResult<DeviceExcelInfo> result(boolean success) {
        AbstractImporter.ImportResult<DeviceExcelInfo> result = new AbstractImporter.ImportResult<>();
        result.setType(AbstractImporter.ImportResultType.data); result.setSuccess(success);
        result.setRow(0); result.setData(device("device1")); return result;
    }

    @Test
    void summaryCountsOnlyRowsAndUsesSpreadsheetRowNumbers() {
        AbstractImporter.ImportResult<DeviceExcelInfo> file = new AbstractImporter.ImportResult<>();
        file.setType(AbstractImporter.ImportResultType.detailFile); file.setDetailFile("/result.xlsx");
        List<ImportDeviceInstanceResult> results = DeviceInstanceController.summarizeCreateOnlyImport(
            Flux.just(result(true), result(false), file)).collectList().block();
        assertEquals(4, results.size()); assertEquals(2L, results.get(0).getRow());
        assertEquals("detailFile", results.get(2).getType());
        assertEquals(1L, results.get(3).getSuccessCount()); assertEquals(1L, results.get(3).getFailureCount());
        assertFalse(results.get(3).isSuccess());
    }

    @Test
    void emptyFileCompletesWithZeroAndBrokenStreamHasNoCompletion() {
        ImportDeviceInstanceResult empty = DeviceInstanceController.summarizeCreateOnlyImport(Flux.empty()).blockLast();
        assertEquals("complete", empty.getType()); assertEquals(0L, empty.getSuccessCount()); assertFalse(empty.isSuccess());
        StepVerifier.create(DeviceInstanceController.summarizeCreateOnlyImport(
            Flux.concat(Flux.just(result(true)), Flux.error(new IllegalStateException("broken")))))
            .expectNextMatches(row -> "row".equals(row.getType()))
            .expectError(IllegalStateException.class).verify();
    }

    @Test
    void createOnlyIsOptionalAndLegacyResponseConstructorStillWorks() {
        boolean optional = Arrays.stream(DeviceInstanceController.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("doBatchImportByProductWithLog"))
            .flatMap(method -> Arrays.stream(method.getParameters()))
            .anyMatch(parameter -> parameter.getType() == boolean.class && parameter.getName().equals("createOnly")
                && "false".equals(parameter.getAnnotation(RequestParam.class).defaultValue()));
        assertTrue(optional);
        assertNull(new ImportDeviceInstanceResult(SaveResult.of(1, 0), true, null, null).getType());
    }
}
