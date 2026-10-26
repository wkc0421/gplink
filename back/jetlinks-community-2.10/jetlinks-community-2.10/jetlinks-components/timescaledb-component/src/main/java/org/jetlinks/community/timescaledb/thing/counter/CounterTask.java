package org.jetlinks.community.timescaledb.thing.counter;

import lombok.Data;

@Data
public class CounterTask {
    private String id;
    private String productId;
    private String propertyId;
    private String status;
    private String kind;
    private long from;
    private long to;
    private long processed;
    private long total;
    private String lastError;
    private Long lastSuccess;
    private Long lastFailure;
}
