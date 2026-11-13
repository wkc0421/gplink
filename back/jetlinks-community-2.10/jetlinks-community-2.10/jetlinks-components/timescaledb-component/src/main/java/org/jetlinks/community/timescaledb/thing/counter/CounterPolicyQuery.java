package org.jetlinks.community.timescaledb.thing.counter;

import lombok.Data;
import java.util.List;

@Data
public class CounterPolicyQuery {
    private String scope = "PRODUCT";
    private List<String> productIds;
    private List<String> deviceIds;
    private List<String> propertyIds;
    private Boolean enabled;
    private String source;
    private String status;
    private int pageIndex;
    private int pageSize = 20;
}
