package gp.saas.legacy.web;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import gp.saas.legacy.dto.CustomizePropertyExcelInfo;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.io.excel.ImportExportService;
import org.jetlinks.core.device.DeviceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LegacyProductPropertyExportTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final List<String> HEADERS = List.of(
        "属性ID", "属性名称", "排序", "寄存器地址", "寄存器数量", "数据解析方法", "功能码",
        "来源", "操作类型", "数据类型", "精度", "单位", "公式"
    );

    @Test
    void exportedWorkbookUsesTemplateAndCanBeImported() throws Exception {
        LocalDeviceProductService service = mock(LocalDeviceProductService.class);
        DeviceRegistry registry = mock(DeviceRegistry.class);
        ImportExportService importer = mock(ImportExportService.class);
        DeviceProductEntity product = new DeviceProductEntity();
        product.setId("product");
        product.setMetadata("{\"properties\":[{\"id\":\"temperature\",\"name\":\"温度\",\"sortsIndex\":8,\"description\":\"模板不包含的说明\","
            + "\"valueType\":{\"type\":\"float\",\"scale\":2,\"unit\":\"custom-unit\"},"
            + "\"expands\":{\"registerAddr\":\"0010\",\"registerNum\":2,\"parseMethod\":12,\"functionCode\":3,"
            + "\"source\":\"device\",\"type\":[\"read\",\"report\"],\"formula\":\"x*2\",\"extraConfig\":\"not-in-template\"}}],"
            + "\"functions\":[{\"id\":\"keep\"}],\"events\":[],\"tags\":[]}");
        when(service.findById("product")).thenReturn(Mono.just(product));
        when(service.save(any(DeviceProductEntity.class))).thenReturn(Mono.empty());
        when(registry.getProduct("product")).thenReturn(Mono.empty());
        LegacyProductController controller = new LegacyProductController(service, null, registry, importer, null, null);

        MockServerHttpResponse exportResponse = new MockServerHttpResponse();
        controller.downloadProductPropertyMetadata("product", "xlsx", exportResponse).block(TIMEOUT);
        byte[] exported = body(exportResponse);
        MockServerHttpResponse templateResponse = new MockServerHttpResponse();
        controller.downloadProductPropertyMetadataTemplate("xlsx", templateResponse).block(TIMEOUT);
        byte[] template = body(templateResponse);

        try (Workbook actual = WorkbookFactory.create(new ByteArrayInputStream(exported));
             Workbook expected = WorkbookFactory.create(new ByteArrayInputStream(template))) {
            Sheet sheet = actual.getSheetAt(0);
            Sheet templateSheet = expected.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            List<String> values = List.of("temperature", "温度", "8", "0010", "2", "IEEC_浮点型_B2143", "3",
                "设备", "读,上报", "float", "2", "custom-unit", "x*2");
            assertEquals(HEADERS.size(), sheet.getRow(0).getLastCellNum());
            for (int i = 0; i < HEADERS.size(); i++) {
                assertEquals(HEADERS.get(i), formatter.formatCellValue(sheet.getRow(0).getCell(i)));
                assertEquals(HEADERS.get(i), formatter.formatCellValue(templateSheet.getRow(0).getCell(i)));
                assertEquals(values.get(i), formatter.formatCellValue(sheet.getRow(1).getCell(i)));
            }
            assertEquals(templateSheet.getDataValidations().size(), sheet.getDataValidations().size());
        }

        when(importer.getInputStream("https://example.test/properties.xlsx"))
            .thenReturn(Mono.just(new ByteArrayInputStream(exported)));
        String saved = controller.importPropertyMetadata("product", "https://example.test/properties.xlsx").block(TIMEOUT);
        JSONObject metadata = JSONObject.parseObject(saved);
        JSONObject property = metadata.getJSONArray("properties").getJSONObject(0);
        assertEquals("temperature", property.getString("id"));
        assertEquals("温度", property.getString("name"));
        assertEquals("8", property.getString("sortsIndex"));
        JSONObject valueType = property.getJSONObject("valueType");
        assertEquals("float", valueType.getString("type"));
        assertEquals("2", valueType.getString("scale"));
        assertEquals("custom-unit", valueType.getString("unit"));
        JSONObject expands = property.getJSONObject("expands");
        assertEquals("0010", expands.getString("registerAddr"));
        assertEquals("2", expands.getString("registerNum"));
        assertEquals(12, expands.getIntValue("parseMethod"));
        assertEquals("3", expands.getString("functionCode"));
        assertEquals("device", expands.getString("source"));
        assertEquals(JSONArray.parseArray("[\"read\",\"report\"]"), expands.getJSONArray("type"));
        assertEquals("x*2", expands.getString("formula"));
        assertFalse(property.containsKey("description"));
        assertFalse(expands.containsKey("extraConfig"));
        assertEquals("keep", metadata.getJSONArray("functions").getJSONObject(0).getString("id"));
    }

    @Test
    void exportPreservesStoredUnitTextWhileListAndImportRemainCompatible() throws Exception {
        LocalDeviceProductService service = mock(LocalDeviceProductService.class);
        DeviceProductEntity product = new DeviceProductEntity();
        product.setId("units");
        JSONArray properties = new JSONArray();
        String[] units = {"V", "A", "°C", "custom-unit", "伏特", "volt", ""};
        for (int i = 0; i < units.length; i++) {
            JSONObject property = new JSONObject();
            property.put("id", "unit-" + i);
            property.put("name", "单位测试" + i);
            JSONObject type = new JSONObject();
            type.put("type", "float");
            type.put("unit", units[i]);
            property.put("valueType", type);
            properties.add(property);
        }
        JSONObject missing = new JSONObject();
        missing.put("id", "unit-missing");
        missing.put("name", "无单位");
        missing.put("valueType", new JSONObject());
        properties.add(missing);
        JSONObject metadata = new JSONObject();
        metadata.put("properties", properties);
        product.setMetadata(metadata.toJSONString());
        when(service.findById("units")).thenReturn(Mono.just(product));
        LegacyProductController controller = new LegacyProductController(service, null, null, null, null, null);

        MockServerHttpResponse response = new MockServerHttpResponse();
        controller.downloadProductPropertyMetadata("units", "xlsx", response).block(TIMEOUT);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(body(response)))) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            for (int i = 0; i < units.length; i++) {
                assertEquals(units[i], formatter.formatCellValue(sheet.getRow(i + 1).getCell(11)), "unit row " + i);
            }
            assertEquals("", formatter.formatCellValue(sheet.getRow(units.length + 1).getCell(11)));
        }

        List<CustomizePropertyExcelInfo> listed = controller.getProductPropertyMetadataList("units").block(TIMEOUT);
        assertNotNull(listed);
        assertEquals("伏特", listed.get(0).getUnit());
        assertEquals("安培", listed.get(1).getUnit());
        assertEquals("伏特", listed.get(4).getUnit());
        assertEquals("伏特", listed.get(5).getUnit());
        assertEquals("volt", CustomizePropertyExcelInfo.parseUnit("V"));
        assertEquals("volt", CustomizePropertyExcelInfo.parseUnit("伏特"));
        assertEquals("custom-unit", CustomizePropertyExcelInfo.parseUnit("custom-unit"));
    }

    @Test
    void emptyPropertiesExportAsWorkbookWithOnlyTemplateHeader() throws Exception {
        LocalDeviceProductService service = mock(LocalDeviceProductService.class);
        DeviceProductEntity product = new DeviceProductEntity();
        product.setId("empty-product");
        product.setMetadata("{\"properties\":[],\"functions\":[],\"events\":[],\"tags\":[]}");
        when(service.findById("empty-product")).thenReturn(Mono.just(product));
        LegacyProductController controller = new LegacyProductController(service, null, null, null, null, null);

        MockServerHttpResponse response = new MockServerHttpResponse();
        controller.downloadProductPropertyMetadata("empty-product", "xlsx", response).block(TIMEOUT);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(body(response)))) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            assertEquals(1, sheet.getPhysicalNumberOfRows());
            assertEquals(HEADERS.size(), sheet.getRow(0).getLastCellNum());
            for (int i = 0; i < HEADERS.size(); i++) {
                assertEquals(HEADERS.get(i), formatter.formatCellValue(sheet.getRow(0).getCell(i)));
            }
        }
    }

    private byte[] body(MockServerHttpResponse response) {
        DataBuffer buffer = DataBufferUtils.join(response.getBody()).block(TIMEOUT);
        assertNotNull(buffer);
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        return bytes;
    }
}
