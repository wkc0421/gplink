package org.jetlinks.community.timescaledb.thing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class CaggMonitorServiceTest {
    @Test void countDatabaseFailuresOnceAndRemoveStaleMeters() {
        CaggTestSupport.Executor executor = new CaggTestSupport.Executor();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CaggMonitorService monitor = new CaggMonitorService(executor,registry);
        executor.responder = sql -> Flux.just(Map.of("job_id",1,"view_schema","public",
            "view_name","metrics_hourly_agg","lag_seconds",50,"watermark_lag_seconds",3600,"failures",2));
        monitor.collectMetrics().block();
        monitor.collectMetrics().block();
        assertThat(registry.get("timescaledb.cagg.refresh.failed").functionCounter().count()).isEqualTo(2);
        assertThat(registry.get("timescaledb.cagg.watermark.lag_seconds").gauge().value()).isEqualTo(3600);
        executor.responder = sql -> Flux.empty();
        monitor.collectMetrics().block();
        assertThat(registry.getMeters()).isEmpty();
        monitor.destroy();
    }
}
