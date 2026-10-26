package org.jetlinks.community.timescaledb.thing;

import lombok.extern.slf4j.Slf4j;
import org.jetlinks.community.timescaledb.TimescaleDBOperations;

import java.time.Duration;

/** Protects dormant product property hypertables from an outdated retention job. */
@Slf4j
public final class RawRetentionStartupReconciler {

    private final TimescaleDBOperations operations;
    private final TimescaleDBThingsDataProperties properties;
    private final String propertyPrefix;

    public RawRetentionStartupReconciler(TimescaleDBOperations operations,
                                         TimescaleDBThingsDataProperties properties,
                                         String propertyPrefix) {
        this.operations = operations;
        this.properties = properties;
        this.propertyPrefix = propertyPrefix;
    }

    public void init() {
        // This is deliberately synchronous: a former 3-year job must not remain active
        // after the application starts merely because its product has no current writes.
        Long checked = new ContinuousAggregateManager(operations.database(), properties, "deviceId")
            .reconcileExistingRawRetention(propertyPrefix)
            .block(Duration.ofMinutes(5));
        log.info("TimescaleDB raw property retention reconciled for {} hypertables", checked);
    }
}
