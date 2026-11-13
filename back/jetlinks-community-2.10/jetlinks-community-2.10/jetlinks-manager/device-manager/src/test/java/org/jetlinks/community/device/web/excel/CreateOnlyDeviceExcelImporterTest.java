package org.jetlinks.community.device.web.excel;

import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.io.excel.AbstractImporter;
import org.jetlinks.community.io.excel.ExcelUtils;
import org.jetlinks.community.io.file.FileInfo;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreateOnlyDeviceExcelImporterTest {
    static DeviceExcelInfo row(String id, String name) { DeviceExcelInfo row = new DeviceExcelInfo(); row.setId(id); row.setName(name); return row; }
    static byte[] bytes(Flux<DataBuffer> buffers) {
        return DataBufferUtils.join(buffers).map(buffer -> {
            byte[] data = new byte[buffer.readableByteCount()]; buffer.read(data); DataBufferUtils.release(buffer); return data;
        }).defaultIfEmpty(new byte[0]).block();
    }
    static DeviceProductEntity product() { DeviceProductEntity p = new DeviceProductEntity(); p.setId("p1"); p.setName("Product"); p.setMetadata("{}"); return p; }
    static Authentication auth() { Authentication auth = mock(Authentication.class, RETURNS_DEEP_STUBS); when(auth.getUser().getId()).thenReturn("tester"); when(auth.getUser().getName()).thenReturn("Tester"); return auth; }

    static class Importer extends CreateOnlyDeviceExcelImporter {
        final byte[] input;
        byte[] report;
        Importer(byte[] input, Function<DeviceExcelInfo, Mono<Void>> insert) {
            super(null, null, product(), Collections.emptyList(), mock(LocalDeviceInstanceService.class), auth(), insert);
            this.input = input;
        }
        @Override protected Mono<InputStream> getInputStream(String url) { return Mono.just(new ByteArrayInputStream(input)); }
        @Override protected Mono<ImportResult<DeviceExcelInfo>> writeDetailFile(String url, String format, Flux<DataBuffer> buffer) {
            return DataBufferUtils.join(buffer).map(data -> {
                report = new byte[data.readableByteCount()]; data.read(report); DataBufferUtils.release(data);
                FileInfo file = new FileInfo(); file.setAccessUrl("/report." + format); return ImportResult.of(file);
            });
        }
    }

    void roundTrip(String format) {
        byte[] input = bytes(ExcelUtils.write(DeviceExcelInfo.class, Flux.just(
            row("existing", "overwrite"), row("fresh1", "first"), row("fresh1", "duplicate"),
            row("invalid1", ""), row("invalid1", "valid after invalid"), row("broken1", "fails in storage")), format));
        Map<String, String> database = new ConcurrentHashMap<>(); database.put("existing", "original-other-product");
        Importer importer = new Importer(input, row -> Mono.fromRunnable(() -> {
            if (row.getId().equals("broken1")) throw new IllegalStateException("storage failed");
            if (database.putIfAbsent(row.getId(), row.getName()) != null) throw new IllegalStateException("duplicate ID");
        }));
        List<AbstractImporter.ImportResult<DeviceExcelInfo>> events = importer.doImport("/input." + format, format).collectList().block();
        assertEquals(2, events.stream().filter(event -> event.getType() == AbstractImporter.ImportResultType.data && event.isSuccess()).count());
        assertEquals(4, events.stream().filter(event -> event.getType() == AbstractImporter.ImportResultType.data && !event.isSuccess()).count());
        assertEquals("original-other-product", database.get("existing")); assertEquals("first", database.get("fresh1"));
        assertEquals("valid after invalid", database.get("invalid1"));
        assertNotNull(importer.report); assertTrue(importer.report.length > 0);
        if (format.equals("csv")) assertTrue(new String(importer.report, StandardCharsets.UTF_8).contains("storage failed"));
    }

    @Test void csvRoundTripReportsValidationDuplicatesAndStorageFailures() { roundTrip("csv"); }
    @Test void xlsxRoundTripPreservesTextIdsAndSuccessfulRows() { roundTrip("xlsx"); }
    @Test void emptyTemplateHasNoDataRows() {
        byte[] input = bytes(ExcelUtils.write(DeviceExcelInfo.class, Flux.empty(), "xlsx"));
        Importer importer = new Importer(input, row -> Mono.error(new AssertionError("must not insert")));
        assertEquals(0, importer.doImport("/empty.xlsx", "xlsx").filter(event -> event.getType() == AbstractImporter.ImportResultType.data).count().block());
    }
    @Test void legacyImporterStillValidatesWithoutInserting() {
        LocalDeviceInstanceService service = mock(LocalDeviceInstanceService.class);
        DeviceExcelImporter importer = new DeviceExcelImporter(null, null, product(), Collections.emptyList(), service, auth());
        DeviceExcelInfo row = row("legacy1", "legacy");
        importer.handleData(Flux.just(row)).block();
        assertEquals("p1", row.getDevice().getProductId()); verifyNoInteractions(service);
    }
}
