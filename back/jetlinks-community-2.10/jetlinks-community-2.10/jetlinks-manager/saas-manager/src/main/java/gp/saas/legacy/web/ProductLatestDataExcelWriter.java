package gp.saas.legacy.web;

import com.alibaba.fastjson.JSON;
import gp.saas.legacy.dto.ProductLatestData.Column;
import gp.saas.legacy.dto.ProductLatestData.Property;
import gp.saas.legacy.dto.ProductLatestData.Row;
import org.hswebframework.reactor.excel.ExcelHeader;
import org.hswebframework.reactor.excel.ReactorExcel;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hswebframework.reactor.excel.CellDataType.STRING;

final class ProductLatestDataExcelWriter {
    private static final MediaType XLSX = MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final DateTimeFormatter REPORT_TIME = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS XXX")
        .withZone(ZoneId.of("Asia/Shanghai"));

    private ProductLatestDataExcelWriter() {
    }

    static Mono<Void> write(ServerHttpResponse response, String productId, List<Column> columns, Flux<Row> rows) {
        List<ExcelHeader> headers = new ArrayList<>(List.of(
            new ExcelHeader("deviceId", "设备ID", STRING),
            new ExcelHeader("deviceName", "设备名称", STRING),
            new ExcelHeader("latestTimestamp", "最近上报时间", STRING)));
        for (Column column : columns) {
            String title = column.name() == null || column.name().isBlank() || column.id().equals(column.name())
                ? column.id()
                : column.name() + "（" + column.id() + "）";
            headers.add(new ExcelHeader("value:" + column.id(), title, STRING));
            headers.add(new ExcelHeader("time:" + column.id(), title + "上报时间", STRING));
        }

        response.getHeaders().setContentType(XLSX);
        response.getHeaders().setContentDisposition(ContentDisposition.attachment()
            .filename(productId + "-latest-data.xlsx", StandardCharsets.UTF_8)
            .build());
        return response.writeWith(ReactorExcel.<Row>writer("xlsx")
            .headers(headers)
            .converter(ProductLatestDataExcelWriter::toCells)
            .writeBuffer(rows.publishOn(Schedulers.boundedElastic(), 16), 512 * 1024)
            .subscribeOn(Schedulers.boundedElastic())
            .map(response.bufferFactory()::wrap));
    }

    private static Map<String, Object> toCells(Row row) {
        Map<String, Object> cells = new HashMap<>();
        cells.put("deviceId", row.deviceId());
        cells.put("deviceName", row.deviceName());
        if (row.latestTimestamp() != null) {
            cells.put("latestTimestamp", formatTime(row.latestTimestamp()));
        }
        for (Property property : row.properties()) {
            cells.put("value:" + property.property(), formatValue(property.value()));
            cells.put("time:" + property.property(), formatTime(property.timestamp()));
        }
        return cells;
    }

    private static String formatTime(long timestamp) {
        return REPORT_TIME.format(Instant.ofEpochMilli(timestamp));
    }

    private static String formatValue(Object value) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return JSON.toJSONString(value);
    }
}
