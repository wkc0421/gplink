package gp.saas.legacy.web;

import gp.saas.legacy.dto.ProductLatestData.Column;
import gp.saas.legacy.dto.ProductLatestData.Property;
import gp.saas.legacy.dto.ProductLatestData.Row;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import reactor.core.publisher.Flux;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ProductLatestDataExcelWriterTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void writesOneRowPerDeviceWithStableColumnsAndPerPropertyTimes() throws Exception {
        long older = Instant.parse("2026-11-11T01:00:00.123Z").toEpochMilli();
        long newer = Instant.parse("2026-11-11T02:00:00.456Z").toEpochMilli();
        List<Column> columns = List.of(new Column("phase.a", "读数"), new Column("flag", "读数"),
            new Column("state", "状态"), new Column("formula", "formula"), new Column("missing", "缺失"));
        MockServerHttpResponse response = new MockServerHttpResponse();
        ProductLatestDataExcelWriter.write(response, "产品-A", columns, Flux.just(
            new Row("00012345678901234567", "设备一", newer, List.of(
                new Property("flag", false, newer), new Property("phase.a", 0, older),
                new Property("state", Map.of("running", true), older), new Property("formula", "=1+1", older))),
            new Row("device2", "从未上报", null, List.of()))).block(TIMEOUT);

        assertEquals("产品-A-latest-data.xlsx", response.getHeaders().getContentDisposition().getFilename());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            response.getHeaders().getContentType().toString());
        try (Workbook workbook = open(response)) {
            var sheet = workbook.getSheetAt(0);
            var header = sheet.getRow(0);
            var first = sheet.getRow(1);
            var second = sheet.getRow(2);
            var formatter = new DataFormatter();

            assertEquals(3, sheet.getPhysicalNumberOfRows());
            assertEquals("读数（phase.a）", header.getCell(3).getStringCellValue());
            assertEquals("读数（flag）上报时间", header.getCell(6).getStringCellValue());
            assertEquals(CellType.STRING, first.getCell(0).getCellType());
            assertEquals("00012345678901234567", first.getCell(0).getStringCellValue());
            assertEquals("2026-11-11 10:00:00.456 +08:00", first.getCell(2).getStringCellValue());
            assertEquals("0", first.getCell(3).getStringCellValue());
            assertEquals("2026-11-11 09:00:00.123 +08:00", first.getCell(4).getStringCellValue());
            assertEquals("false", first.getCell(5).getStringCellValue());
            assertEquals("2026-11-11 10:00:00.456 +08:00", first.getCell(6).getStringCellValue());
            assertEquals("{\"running\":true}", first.getCell(7).getStringCellValue());
            assertEquals(CellType.STRING, first.getCell(9).getCellType());
            assertEquals("=1+1", first.getCell(9).getStringCellValue());
            assertEquals("", formatter.formatCellValue(first.getCell(11)));
            assertEquals("device2", second.getCell(0).getStringCellValue());
            assertEquals("", formatter.formatCellValue(second.getCell(2)));
            assertEquals("", formatter.formatCellValue(second.getCell(3)));
        }
    }

    @Test
    void writesHeadersWhenProductHasNoDevices() throws Exception {
        MockServerHttpResponse response = new MockServerHttpResponse();
        ProductLatestDataExcelWriter.write(response, "product", List.of(new Column("temp", "温度")), Flux.empty())
            .block(TIMEOUT);

        try (Workbook workbook = open(response)) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(1, sheet.getPhysicalNumberOfRows());
            assertEquals(5, sheet.getRow(0).getLastCellNum());
            assertEquals("温度（temp）", sheet.getRow(0).getCell(3).getStringCellValue());
        }
    }

    private Workbook open(MockServerHttpResponse response) throws Exception {
        DataBuffer buffer = DataBufferUtils.join(response.getBody()).block(TIMEOUT);
        assertNotNull(buffer);
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return WorkbookFactory.create(new ByteArrayInputStream(bytes));
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}
