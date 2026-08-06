package org.jetlinks.community.device.modbus;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModbusPollingConfigurationValidatorTest {

    private final ModbusPollingConfigurationValidator validator =
            new ModbusPollingConfigurationValidator(null);

    @Test
    void acceptsSecondLevelFixedDelayAndValidCron() {
        Map<String, Object> fixed = new HashMap<>();
        fixed.put("pollEnabled", true);
        fixed.put("pollIntervalMs", 1000);
        fixed.put("pollPropertyIds", Collections.singletonList("temperature"));
        assertDoesNotThrow(() -> validator.validateConfiguration(
                fixed, Collections.singletonList("temperature")));

        Map<String, Object> cron = new HashMap<>(fixed);
        cron.put("pollScheduleType", "CRON");
        cron.put("pollCron", "0/1 * * * * ?");
        assertDoesNotThrow(() -> validator.validateConfiguration(
                cron, Collections.singletonList("temperature")));
    }

    @Test
    void rejectsInvalidScheduleAndIntervals() {
        Map<String, Object> invalidType = new HashMap<>();
        invalidType.put("pollScheduleType", "UNKNOWN");
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateConfiguration(invalidType, Collections.emptyList()));

        Map<String, Object> invalidInterval = new HashMap<>();
        invalidInterval.put("pollIntervalMs", 999);
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateConfiguration(invalidInterval, Collections.emptyList()));

        Map<String, Object> invalidCron = new HashMap<>();
        invalidCron.put("pollScheduleType", "CRON");
        invalidCron.put("pollCron", "not-a-cron");
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateConfiguration(invalidCron, Collections.emptyList()));
    }

    @Test
    void rejectsOutOfRangeRetryWindowAndGap() {
        Map<String, Object> invalid = new HashMap<>();
        invalid.put("pollRetryCount", 11);
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateConfiguration(invalid, Collections.emptyList()));

        invalid.put("pollRetryCount", 0);
        invalid.put("maxReadAddressGap", 3);
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateConfiguration(invalid, Collections.emptyList()));

        invalid.put("maxReadAddressGap", 2);
        invalid.put("pollDeviceIntervalMs", 60001);
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateConfiguration(invalid, Collections.emptyList()));
    }
}
