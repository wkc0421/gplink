package org.jetlinks.community.timescaledb.thing.counter;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The value is the original text value, never a floating-point CAGG conversion. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CounterBoundary {
    public static final String VALID = "VALID";
    public static final String NO_VALUE = "NO_VALUE";
    public static final String NOT_GENERATED = "NOT_GENERATED";

    private long boundary;
    private String value;
    private Long sampledAt;
    private String state;
    private String source;
}
