package org.jetlinks.community.timescaledb.thing;

import org.jetlinks.community.things.data.AggregationRequest;
import org.jetlinks.community.things.data.PropertyAggregation;
import org.jetlinks.community.timeseries.query.Aggregation;
import org.jetlinks.community.timeseries.query.AggregationData;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jetlinks.community.timescaledb.thing.CaggTestSupport.*;

class UnknownPropertyAggregationCompatibilityTest {

    @Test
    void missingMetadataKeepsRequestedBucketFormatWithoutQueryingHistory() {
        Executor executor = new Executor();
        TimescaleDBThingsDataProperties properties = config();
        properties.getCagg().setRetention(null);
        Query query = new Query(database(executor, "metrics"), properties, "device-1");
        executor.responder = sql -> Flux.just(Map.of(
            "_ts", LocalDateTime.of(2026, 10, 22, 13, 0), "_a0", 387.5D));
        AggregationRequest request = request(
            "1h", "2026-10-22 12:00:00", "2026-10-22 14:59:59", 3);
        request.setFormat("yyyy-MM-dd HH");

        List<AggregationData> rows = query.aggregate("metrics", request,
            new PropertyAggregation("Uab", "voltage", Aggregation.FIRST)).collectList().block();

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).values()).containsEntry("time", "2026-10-22 14")
            .containsEntry("voltage", null);
        assertThat(rows.get(1).values()).containsEntry("time", "2026-10-22 13")
            .containsEntry("voltage", null);
        assertThat(rows.get(2).values()).containsEntry("time", "2026-10-22 12")
            .containsEntry("voltage", null);
        assertThat(executor.requests).isEmpty();
        assertThat(executor.extensionSchemaRequests).isEmpty();
        assertThat(executor.writes).isEmpty();
    }

    @Test
    void missingPropertyReturnsBoundedEmptyBucketsForNumericAggregations() {
        for (Aggregation aggregation : new Aggregation[]{Aggregation.FIRST, Aggregation.LAST,
            Aggregation.TOP, Aggregation.AVG, Aggregation.SUM, Aggregation.MIN, Aggregation.MAX,
            Aggregation.COUNT, Aggregation.DISTINCT_COUNT}) {
            Executor executor = new Executor();
            TimescaleDBThingsDataProperties properties = config();
            properties.getCagg().setEnabled(false);
            Query query = new Query(database(executor, "metrics"), properties);

            List<AggregationData> rows = query.aggregate("metrics",
                request("1h", "2026-10-22 00:00:00", "2026-10-23 23:59:59", 30),
                new PropertyAggregation("Uab", "Uab", aggregation)).collectList().block();

            assertThat(rows).as("empty buckets for %s", aggregation).hasSize(30);
            assertThat(rows.get(0).values()).containsEntry("time", "2026-10-23 23:00:00");
            assertThat(rows.get(29).values()).containsEntry("time", "2026-10-22 18:00:00");
            assertThat(rows).allSatisfy(row -> assertThat(row.values()).containsEntry("Uab", null));
            assertThat(executor.requests).isEmpty();
            assertThat(executor.extensionSchemaRequests).isEmpty();
            assertThat(executor.writes).isEmpty();
        }
    }
}
