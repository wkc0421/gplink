package org.jetlinks.community.device.modbus;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModbusPollPlanTest {

    @Test
    void fixedDelayStartsAfterCompletion() {
        ModbusPollPlan plan = new ModbusPollPlan(
                "p",
                ModbusPollPlan.ScheduleType.FIXED_DELAY,
                30000,
                null,
                100,
                100,
                0,
                Collections.singletonList("temp"),
                Collections.singletonList("d1"));
        long first = plan.getNextFireTime();

        assertEquals(first, plan.claimFire(first));
        assertFalse(plan.isDue(first + 60000));

        long completed = first + 5000;
        plan.complete(completed);
        assertEquals(completed + 30000, plan.getNextFireTime());
    }

    @Test
    void cronTriggersDuringExecutionCollapseToOnePendingRun() {
        ModbusPollPlan plan = new ModbusPollPlan(
                "p",
                ModbusPollPlan.ScheduleType.CRON,
                30000,
                "*/1 * * * * *",
                100,
                100,
                0,
                Collections.singletonList("temp"),
                Collections.singletonList("d1"));
        long first = plan.getNextFireTime();
        plan.claimFire(first);

        long completed = first + 5000;
        plan.mergeCronTrigger(completed);
        plan.mergeCronTrigger(completed);
        plan.complete(completed);

        assertTrue(plan.isDue(completed));
        plan.claimFire(completed);
        plan.complete(completed + 100);
        assertFalse(plan.isDue(completed + 100));
    }
}
