package org.jetlinks.community.timescaledb.thing;

import org.junit.jupiter.api.Test;
import org.jetlinks.community.timescaledb.TimescaleDBUtils;

import static org.assertj.core.api.Assertions.assertThat;

class RawRetentionStartupReconcilerTest {

    @Test
    void recognizesDefaultAndCustomProductPropertyPrefixes() {
        assertThat(ContinuousAggregateManager.rawPropertyTableCandidate(
            "properties_product123", TimescaleDBUtils.getTableName("properties_"))).isTrue();
        assertThat(ContinuousAggregateManager.rawPropertyTableCandidate(
            "meter_data_product123", TimescaleDBUtils.getTableName("meter_data_"))).isTrue();
        assertThat(ContinuousAggregateManager.rawPropertyTableCandidate(
            "device_properties_product123", TimescaleDBUtils.getTableName("properties_"))).isTrue();
    }

    @Test
    void excludesEventLogAndSnapshotTables() {
        String prefix = TimescaleDBUtils.getTableName("properties_");
        assertThat(ContinuousAggregateManager.rawPropertyTableCandidate("event_product123", prefix)).isFalse();
        assertThat(ContinuousAggregateManager.rawPropertyTableCandidate("device_log_product123", prefix)).isFalse();
        assertThat(ContinuousAggregateManager.rawPropertyTableCandidate("counter_snapshot_hour", prefix)).isFalse();
    }
}
