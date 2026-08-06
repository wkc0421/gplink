package org.jetlinks.community.device.modbus;

import org.jetlinks.core.message.property.ReportPropertyMessage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModbusPollReportPublisherTest {

    @Test
    void partialReportKeepsPerPropertyTimesAndCycleHeaders() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("running", true);
        Map<String, Long> sourceTimes = new LinkedHashMap<>();
        sourceTimes.put("running", 1200L);

        ModbusPollReportPublisher publisher =
                new ModbusPollReportPublisher(null, null);
        ReportPropertyMessage report = publisher.createReport(
                new ModbusPollReportPublisher.PollCycleResult(
                        "gateway-1",
                        "device-1",
                        "plan-1",
                        "cycle-1",
                        "message-1",
                        1000L,
                        1300L,
                        1200L,
                        2,
                        properties,
                        sourceTimes));

        assertEquals("message-1", report.getMessageId());
        assertEquals(1200L, report.getTimestamp());
        assertEquals(properties, report.getProperties());
        assertEquals(1200L, report.getPropertySourceTime("running").orElseThrow());
        assertEquals("MODBUS", report.getHeader("pollSource").orElse(null));
        assertEquals("gateway-1", report.getHeader("gatewayId").orElse(null));
        assertEquals("cycle-1", report.getHeader("pollCycleId").orElse(null));
        assertEquals("plan-1", report.getHeader("pollPlanId").orElse(null));
        assertEquals("partial", report.getHeader("pollResult").orElse(null));
        assertEquals(1000L, report.getHeader("pollCycleStartTime").orElse(null));
        assertEquals(1300L, report.getHeader("pollCycleCompleteTime").orElse(null));
        assertEquals(1, report.getHeader("pollSuccessCount").orElse(null));
        assertEquals(2, report.getHeader("pollFailedCount").orElse(null));
    }

    @Test
    void zeroFailedPropertiesProducesSuccessResult() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("temperature", 25.3D);
        Map<String, Long> sourceTimes = new LinkedHashMap<>();
        sourceTimes.put("temperature", 2000L);

        ModbusPollReportPublisher publisher =
                new ModbusPollReportPublisher(null, null);
        ReportPropertyMessage report = publisher.createReport(
                new ModbusPollReportPublisher.PollCycleResult(
                        "gateway-1",
                        "device-1",
                        "plan-1",
                        "cycle-1",
                        "message-1",
                        1900L,
                        2100L,
                        2000L,
                        0,
                        properties,
                        sourceTimes));

        assertEquals("success", report.getHeader("pollResult").orElse(null));
        assertEquals(0, report.getHeader("pollFailedCount").orElse(null));
    }
}
