package org.jetlinks.community.timescaledb.thing.counter;

import lombok.Data;

/** Product/property opt-in and durable progress, with epoch-millisecond times for the API. */
@Data
public class CounterConfig {
    private String productId;
    private String propertyId;
    private String rawTable;
    private boolean enabled;
    private long startTime;
    private long version;
    private String status;
    private Long coverageFrom;
    private Long coverageTo;
    private Long pendingFrom;
    private Long pendingTo;
    private Long lastSuccess;
    private Long lastFailure;
    private String lastError;
    private String taskId;
}
