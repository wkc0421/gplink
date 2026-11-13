package org.jetlinks.community.timescaledb.thing.counter;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CounterReadContext {
    private boolean enabled;
    private long configVersion;
    private long sourceVersion;
    private long publishedVersion;
    private long policyRevision;

    public CounterReadContext(boolean enabled, long configVersion, long sourceVersion, long publishedVersion) {
        this(enabled, configVersion, sourceVersion, publishedVersion, 0);
    }

    public CounterReadContext(boolean enabled, long configVersion, long sourceVersion,
                              long publishedVersion, long policyRevision) {
        this.enabled = enabled;
        this.configVersion = configVersion;
        this.sourceVersion = sourceVersion;
        this.publishedVersion = publishedVersion;
        this.policyRevision = policyRevision;
    }

    public String getVersion() {
        return configVersion + ":" + sourceVersion + ":" + publishedVersion + ":" + policyRevision;
    }
}
