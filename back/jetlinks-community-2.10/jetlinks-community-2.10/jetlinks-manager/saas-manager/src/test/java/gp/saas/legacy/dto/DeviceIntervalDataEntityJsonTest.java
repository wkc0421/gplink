package gp.saas.legacy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceIntervalDataEntityJsonTest {

    private final ObjectMapper mapper = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Test
    void omitsNullBoundaryValuesForLegacyConsumers() throws Exception {
        DeviceIntervalDataEntity interval = new DeviceIntervalDataEntity(
            "device-1", "energy", 100L, 200L, null, null);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(interval));

        assertThat(json.has("startValue")).isFalse();
        assertThat(json.has("endValue")).isFalse();
    }

    @Test
    void retainsNonNullBoundaryValuesWithGlobalNonNullMapper() throws Exception {
        DeviceIntervalDataEntity interval = new DeviceIntervalDataEntity(
            "device-1", "energy", 100L, 200L, new BigDecimal("12.5"), "13.5");

        JsonNode json = mapper.readTree(mapper.writeValueAsString(interval));

        assertThat(json.get("startValue").decimalValue()).isEqualByComparingTo("12.5");
        assertThat(json.get("endValue").isTextual()).isTrue();
        assertThat(json.get("endValue").asText()).isEqualTo("13.5");
    }

    @Test
    void missingEndAlsoHidesStartFromGettersAndJson() throws Exception {
        DeviceIntervalDataEntity interval = new DeviceIntervalDataEntity(
            "device-1", "energy", 100L, 200L, new BigDecimal("554.4"), null);

        assertMissingPair(interval);
    }

    @Test
    void missingStartAlsoHidesEndFromGettersAndJson() throws Exception {
        DeviceIntervalDataEntity interval = new DeviceIntervalDataEntity(
            "device-1", "energy", 100L, 200L, null, new BigDecimal("554.4"));

        assertMissingPair(interval);
    }

    @Test
    void setterUpdatesExposeValuesOnlyWhenBothReadingsExist() throws Exception {
        DeviceIntervalDataEntity interval = new DeviceIntervalDataEntity();
        interval.setStartValue(10);
        assertMissingPair(interval);

        interval.setEndValue(20);
        assertThat(interval.getStartValue()).isEqualTo(10);
        assertThat(interval.getEndValue()).isEqualTo(20);

        interval.setEndValue(null);
        assertMissingPair(interval);

        interval.setEndValue(30);
        assertThat(interval.getStartValue()).isEqualTo(10);
        assertThat(interval.getEndValue()).isEqualTo(30);

        interval.setStartValue(null);
        assertMissingPair(interval);
    }

    @Test
    void actualZeroReadingsAreACompletePair() throws Exception {
        DeviceIntervalDataEntity interval = new DeviceIntervalDataEntity(
            "device-1", "energy", 100L, 200L, 0, 0);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(interval));

        assertThat(interval.getStartValue()).isEqualTo(0);
        assertThat(interval.getEndValue()).isEqualTo(0);
        assertThat(json.get("startValue").intValue()).isZero();
        assertThat(json.get("endValue").intValue()).isZero();
    }

    private void assertMissingPair(DeviceIntervalDataEntity interval) throws Exception {
        assertThat(interval.getStartValue()).isNull();
        assertThat(interval.getEndValue()).isNull();
        // Field presence must stay paired even when the global mapper includes nulls.
        ObjectMapper always = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.ALWAYS);
        JsonNode json = always.readTree(always.writeValueAsString(interval));
        assertThat(json.has("startValue")).isFalse();
        assertThat(json.has("endValue")).isFalse();
    }
}
