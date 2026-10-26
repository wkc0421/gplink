package org.jetlinks.community.device.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeviceStateTest {

    @Test
    void shouldOnlyMapNoActiveToNotActive() {
        assertEquals(DeviceState.notActive,
                     DeviceState.of(org.jetlinks.core.device.DeviceState.noActive));
        assertEquals(DeviceState.online,
                     DeviceState.of(org.jetlinks.core.device.DeviceState.online));
        assertEquals(DeviceState.offline,
                     DeviceState.of(org.jetlinks.core.device.DeviceState.offline));
        assertEquals(DeviceState.offline,
                     DeviceState.of(org.jetlinks.core.device.DeviceState.unknown));
        assertEquals(DeviceState.offline,
                     DeviceState.of(org.jetlinks.core.device.DeviceState.timeout));
    }
}
