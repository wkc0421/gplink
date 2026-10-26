package org.jetlinks.community.timescaledb.thing;

import org.jetlinks.community.things.data.AggregationRequest;
import org.jetlinks.community.things.data.PropertyAggregation;
import org.jetlinks.community.timeseries.query.Aggregation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jetlinks.community.timescaledb.thing.CaggTestSupport.request;
import static org.jetlinks.community.timescaledb.thing.CaggTestSupport.time;

class DailyFiveMinuteAggregationWindowTest {

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-22", "2026-10-23"})
    void explicitLimitIncludesDaytimeInsteadOfOnlyLastThirtyBuckets(String day) {
        PropertyAggregation[] columns = {new PropertyAggregation("P", "P", Aggregation.FIRST)};
        TimescaleDBThingsDataProperties.AggregationLimits limits =
            new TimescaleDBThingsDataProperties.AggregationLimits();
        AggregationRequest request = request("5m", day + " 00:00:00", day + " 23:59:59", 5000);
        request.setLimit(null);

        AggregationWindow truncated = AggregationWindow.create(request, columns, limits);
        assertThat(truncated.rows).hasSize(30);
        assertThat(truncated.from).isEqualTo(time(day + " 21:30:00"));

        request.setLimit(5000);
        AggregationWindow fullDay = AggregationWindow.create(request, columns, limits);
        assertThat(fullDay.rows).hasSize(288);
        assertThat(fullDay.rows.keySet()).contains(time(day + " 00:00:00"), time(day + " 10:00:00"),
                                                   time(day + " 23:55:00"));
        assertThat(fullDay.from).isEqualTo(time(day + " 00:00:00"));
    }
}
