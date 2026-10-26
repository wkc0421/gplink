package org.jetlinks.community.timescaledb.thing.counter;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CounterReadContext {
    private boolean enabled;
    private long configVersion;
    private long sourceVersion;
    private long publishedVersion;

    public String getVersion() {
        return configVersion + ":" + sourceVersion + ":" + publishedVersion;
    }
}
