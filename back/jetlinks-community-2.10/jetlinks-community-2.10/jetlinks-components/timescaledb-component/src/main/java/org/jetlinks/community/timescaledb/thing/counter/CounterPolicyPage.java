package org.jetlinks.community.timescaledb.thing.counter;

import lombok.Data;
import java.util.List;

@Data
public class CounterPolicyPage {
    private List<CounterPolicyRow> data;
    private long total;
    private int pageIndex;
    private int pageSize;
}
