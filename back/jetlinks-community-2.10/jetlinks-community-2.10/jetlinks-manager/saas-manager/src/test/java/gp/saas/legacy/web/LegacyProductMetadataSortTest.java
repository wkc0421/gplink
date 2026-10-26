package gp.saas.legacy.web;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import gp.saas.legacy.dto.CustomizePropertyExcelInfo;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.io.excel.ImportExportService;
import org.jetlinks.core.device.DeviceRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyProductMetadataSortTest {

    @Test
    void shouldPersistSortIndexesWhenCsvDoesNotContainSortColumn() {
        LocalDeviceProductService service = mock(LocalDeviceProductService.class);
        DeviceRegistry registry = mock(DeviceRegistry.class);
        ImportExportService importer = mock(ImportExportService.class);
        DeviceProductEntity product = product(service, registry);
        StringBuilder csv = new StringBuilder("id,name,registerAddr,registerNum,parseMethod,functionCode,source,type,valueType\n");
        for (int i = 0; i < 34; i++) {
            csv.append("p").append(i).append(",Point,").append(i * 2).append(",2,12,3,device,read,float\n");
        }
        when(importer.getInputStream("https://example.test/properties.csv"))
            .thenReturn(Mono.just(new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8))));
        LegacyProductController controller = new LegacyProductController(service, null, registry, importer, null, null);

        String saved = controller.importPropertyMetadata("product", "https://example.test/properties.csv")
            .block(Duration.ofSeconds(5));

        JSONArray properties = JSONObject.parseObject(saved).getJSONArray("properties");
        assertEquals(34, properties.size());
        for (int i = 0; i < properties.size(); i++) {
            assertEquals("p" + i, properties.getJSONObject(i).getString("id"));
            assertEquals(34 - i, properties.getJSONObject(i).getIntValue("sortsIndex"));
        }
        assertEquals(saved, product.getMetadata());
        assertEquals("keep", JSONObject.parseObject(saved).getJSONArray("functions").getJSONObject(0).getString("id"));
        verify(service).save(product);
    }

    @Test
    void shouldPreserveExplicitZeroAndNumericTextWhileFillingEmptyCells() {
        LocalDeviceProductService service = mock(LocalDeviceProductService.class);
        DeviceRegistry registry = mock(DeviceRegistry.class);
        product(service, registry);
        CustomizePropertyExcelInfo first = property("a", "0");
        CustomizePropertyExcelInfo second = property("b", "3");
        CustomizePropertyExcelInfo third = property("c", "  ");
        LegacyProductController controller = new LegacyProductController(service, null, registry, null, null, null);

        String saved = controller.updateProductPropertyMetadata("product", List.of(first, second, third))
            .block(Duration.ofSeconds(5));

        JSONArray properties = JSONObject.parseObject(saved).getJSONArray("properties");
        assertEquals("0", properties.getJSONObject(0).get("sortsIndex"));
        assertEquals("3", properties.getJSONObject(1).get("sortsIndex"));
        assertEquals(2, properties.getJSONObject(2).get("sortsIndex"));
        assertEquals("  ", third.getSortsIndex());
        assertTrue(JSONObject.parseObject(saved).getJSONArray("events").isEmpty());
    }

    private DeviceProductEntity product(LocalDeviceProductService service, DeviceRegistry registry) {
        DeviceProductEntity product = new DeviceProductEntity();
        product.setId("product");
        product.setMetadata("{\"properties\":[],\"functions\":[{\"id\":\"keep\"}],\"events\":[],\"tags\":[]}");
        when(service.findById("product")).thenReturn(Mono.just(product));
        when(service.save(any(DeviceProductEntity.class))).thenReturn(Mono.empty());
        when(registry.getProduct("product")).thenReturn(Mono.empty());
        return product;
    }

    private CustomizePropertyExcelInfo property(String id, String sortIndex) {
        CustomizePropertyExcelInfo property = new CustomizePropertyExcelInfo();
        property.setId(id);
        property.setName(id);
        property.setSortsIndex(sortIndex);
        property.setValueType("float");
        return property;
    }
}
