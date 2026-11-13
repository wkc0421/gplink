package org.jetlinks.community.timescaledb.thing.counter;

import lombok.Data;

@Data
public class CounterPolicyRow {
    private String productId;
    private String deviceId;
    private String propertyId;
    private String mode;
    private boolean enabled;
    private boolean productEnabled;
    private boolean eligible;
    private long policyRevision;
    private CounterConfig config;
}
