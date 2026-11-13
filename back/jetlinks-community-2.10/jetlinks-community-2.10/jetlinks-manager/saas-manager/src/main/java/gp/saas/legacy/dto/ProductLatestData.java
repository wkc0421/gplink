package gp.saas.legacy.dto;

import java.util.List;

public record ProductLatestData(List<Column> columns, long total, List<Row> data) {

    public record Column(String id, String name) {
    }

    public record Row(String deviceId, String deviceName, Long latestTimestamp, List<Property> properties) {
    }

    public record Property(String property, Object value, long timestamp) {
    }
}
