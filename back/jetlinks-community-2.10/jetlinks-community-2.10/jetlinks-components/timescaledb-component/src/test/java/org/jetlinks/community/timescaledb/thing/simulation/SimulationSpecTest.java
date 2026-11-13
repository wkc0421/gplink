package org.jetlinks.community.timescaledb.thing.simulation;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SimulationSpecTest {
    static long time(String t) { return LocalDateTime.parse(t).atZone(SimulationSpec.ZONE).toInstant().toEpochMilli(); }
    static SimulationSpec spec() {
        SimulationSpec s=new SimulationSpec(); s.deviceId="d";s.propertyId="p";s.type="double";s.scale=2;
        s.startTime=time("2024-01-01T00:00:00");s.endTime=time("2026-01-01T00:00:00");s.intervalMinutes=5;
        s.mode="INCREMENT";s.initial="0.10";s.increment="0.01";return s;
    }
    @Test void twoCalendarYearsIncludingLeapYearAndHalfOpenSamples() {
        SimulationSpec s=spec();s.validate(time("2026-02-01T00:00:00"),0);
        assertThat(s.count()).isEqualTo(210528);assertThat(s.time(s.count()-1)).isLessThan(s.endTime);
        s.endTime++;assertThatThrownBy(()->s.validate(time("2026-02-01T00:00:00"),0)).hasMessageContaining("两个日历年");
        s.startTime=time("2024-02-29T12:30:00");s.endTime=time("2026-02-28T12:30:00");s.validate(time("2026-03-01T00:00:00"),0);
    }
    @Test void exactIncrementAndPrecisionNeverSilentlyRound() {
        SimulationSpec s=spec();assertThat(s.value(19)).isEqualByComparingTo("0.29");
        s.increment="0.001";assertThatThrownBy(()->s.validate(System.currentTimeMillis(),0)).hasMessageContaining("精度");
        s.increment="0";assertThatThrownBy(()->s.validate(System.currentTimeMillis(),0)).hasMessageContaining("大于0");
    }
    @Test void randomInclusiveDeterministicAndIndexIndependent() {
        SimulationSpec s=spec();s.mode="RANDOM";s.min="-0.01";s.max="0.01";s.seed=77;s.validate(System.currentTimeMillis(),0);
        Set<BigDecimal> seen=new HashSet<>();
        for(int i=0;i<100;i++) { seen.add(s.value(i));assertThat(s.value(i)).isBetween(new BigDecimal("-0.01"),new BigDecimal("0.01")); }
        BigDecimal value=s.value(75);s.value(1);assertThat(s.value(75)).isEqualTo(value);assertThat(seen).hasSize(3);
        s.min="-0.01";s.max="-0.01";assertThat(s.value(999)).isEqualByComparingTo("-0.01");
    }
    @Test void overflowNonFiniteMetadataAndFutureRejected() {
        SimulationSpec s=spec();s.type="int";s.scale=0;s.initial="2147483647";s.increment="1";
        assertThatThrownBy(()->s.validate(System.currentTimeMillis(),0)).hasMessageContaining("int");
        s.type="double";s.initial="NaN";assertThatThrownBy(()->s.validate(System.currentTimeMillis(),0)).isInstanceOf(IllegalArgumentException.class);
        s.initial="1";s.upper="3";assertThatThrownBy(()->s.validate(System.currentTimeMillis(),0)).hasMessageContaining("上限");
        s.upper=null;s.endTime=System.currentTimeMillis()+100000;assertThatThrownBy(()->s.validate(System.currentTimeMillis(),0)).hasMessageContaining("未来");
    }
    @Test void precisionLossAndExponentInputsAreRejected() {
        SimulationSpec s=spec();s.mode="RANDOM";s.min="9007199254740993";s.max=s.min;s.type="long";s.scale=0;
        assertThatThrownBy(()->s.validate(System.currentTimeMillis(),0)).hasMessageContaining("精度");
        s.min="1e-100000000";s.max="1";
        assertThatThrownBy(()->s.validate(System.currentTimeMillis(),0)).isInstanceOf(IllegalArgumentException.class);
        s.type="float";s.scale=8;s.min="123.12345678";s.max=s.min;
        assertThatThrownBy(()->s.validate(System.currentTimeMillis(),0)).hasMessageContaining("float");
    }
    @Test void intervalsRetentionAndSnapshotEligibility() {
        SimulationSpec s=spec();for(int step:List.of(5,15,30,60)){s.intervalMinutes=step;s.validate(System.currentTimeMillis(),0);}
        s.intervalMinutes=7;assertThatThrownBy(()->s.validate(System.currentTimeMillis(),0)).hasMessageContaining("间隔");
        s.intervalMinutes=5;assertThatThrownBy(()->s.validate(System.currentTimeMillis(),s.startTime+1)).hasMessageContaining("保留");
        s.snapshots=true;assertThatThrownBy(()->s.validate(System.currentTimeMillis(),0)).hasMessageContaining("快照");
        assertThat(SimulationSpec.rowId("a",1)).isNotEqualTo(SimulationSpec.rowId("b",1)).isEqualTo(SimulationSpec.rowId("a",1));
    }
}
