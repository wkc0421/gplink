package gp.saas.legacy.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gp.saas.legacy.dto.DeiceDataRequestEntity;
import gp.saas.legacy.dto.DeviceAggregationDataEntity;
import org.jetlinks.community.Interval;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.community.timeseries.query.Aggregation;
import org.jetlinks.community.timeseries.query.AggregationData;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyUnknownPropertyAggregationTest {

    @Test
    void mixedDevicesKeepLegacyDeviceTimeValueJsonIncludingEmptyBuckets() throws Exception {
        DeviceDataService service = mock(DeviceDataService.class);
        LegacyDeviceDataController controller = new LegacyDeviceDataController(service, null, null);
        when(service.aggregationPropertiesByDevice(eq("1P01"), any(), any()))
            .thenReturn(Flux.just(row("2026-10-22 14:00:00", null),
                row("2026-10-22 13:00:00", 387.5D)));
        when(service.aggregationPropertiesByDevice(eq("1P02"), any(), any()))
            .thenReturn(Flux.just(row("2026-10-22 14:00:00", null),
                row("2026-10-22 13:00:00", null)));

        DeviceDataService.AggregationRequest query = new DeviceDataService.AggregationRequest();
        query.setInterval(Interval.ofHours(1));
        query.setFrom(new Date(1792598400000L));
        query.setTo(new Date(1792771199999L));
        query.setLimit(1000);
        DeviceDataService.DevicePropertyAggregation column = new DeviceDataService.DevicePropertyAggregation();
        column.setProperty("Uab");
        column.setAlias("Uab");
        column.setAgg(Aggregation.FIRST);
        DeiceDataRequestEntity.AggregationRequestBody body = new DeiceDataRequestEntity.AggregationRequestBody();
        body.setQuery(query);
        body.setColumns(List.of(column));
        DeiceDataRequestEntity request = new DeiceDataRequestEntity();
        request.setDevices(List.of("1P01", "1P02"));
        request.setProperties(List.of("Uab"));
        request.setAggregationRequest(body);

        List<Map<String, List<DeviceAggregationDataEntity>>> result =
            controller.queryAggregation(request).collectList().block();
        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        JsonNode json = mapper.valueToTree(result);

        assertThat(json.size()).isEqualTo(2);
        for (JsonNode device : json) {
            assertThat(device.size()).isEqualTo(1);
            JsonNode values = device.elements().next();
            assertThat(values.size()).isEqualTo(2);
            assertThat(values.get(0).size()).isEqualTo(1);
            assertThat(values.get(0).get("time").asText()).isEqualTo("2026-10-22 14:00:00");
            if (device.has("1P01")) {
                assertThat(values.get(1).get("value").asDouble()).isEqualTo(387.5D);
            } else {
                assertThat(device.has("1P02")).isTrue();
                assertThat(values.get(1).has("value")).isFalse();
            }
        }
    }

    private static AggregationData row(String time, Object value) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("time", time);
        row.put("Uab", value);
        return AggregationData.of(row);
    }
}
