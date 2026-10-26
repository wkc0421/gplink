/*
 * Copyright 2025 JetLinks https://www.jetlinks.cn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetlinks.community.timescaledb.thing;

import io.micrometer.core.instrument.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.rdb.executor.reactive.ReactiveSqlExecutor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Job health and materialization watermark are separate; neither proves absence of late-data invalidations. */
@Slf4j
@RequiredArgsConstructor
public class CaggMonitorService implements InitializingBean, DisposableBean {
    static final String QUERY_SQL =
        "SELECT j.job_id,c.view_schema,c.view_name,"
            + "CASE WHEN js.last_successful_finish IS NULL OR NOT isfinite(js.last_successful_finish) THEN -1"
            + " ELSE extract(epoch FROM (now()-js.last_successful_finish)) END AS lag_seconds,"
            + "coalesce(js.total_failures,0) AS failures,"
            // Empty timestamp caggs use the minimum supported timestamp, not always INT64_MIN.
            + "CASE WHEN w.mark IS NULL OR w.mark < -62135596800000000 OR w.mark > 253402300799999999 THEN -1"
            + " ELSE greatest(0,extract(epoch FROM (now()-"
            + "((_timescaledb_functions.to_timestamp(w.mark) AT TIME ZONE 'UTC')"
            + " AT TIME ZONE 'Asia/Shanghai')))) END AS watermark_lag_seconds "
            + "FROM timescaledb_information.jobs j "
            + "JOIN timescaledb_information.continuous_aggregates c"
            + " ON j.hypertable_schema=c.view_schema"
            + " AND j.hypertable_name=c.view_name "
            + "LEFT JOIN timescaledb_information.job_stats js ON j.job_id=js.job_id "
            + "LEFT JOIN LATERAL (SELECT _timescaledb_functions.cagg_watermark("
            + "(j.config->>'mat_hypertable_id')::integer) AS mark) w ON true "
            + "WHERE j.proc_name='policy_refresh_continuous_aggregate'";

    private final ReactiveSqlExecutor sqlExecutor;
    private final MeterRegistry meterRegistry;
    private final Map<String, Metrics> metrics = new ConcurrentHashMap<>();
    private Disposable scheduler;

    @Override
    public void afterPropertiesSet() {
        scheduler = Flux.interval(Duration.ZERO, Duration.ofMinutes(5))
            .onBackpressureDrop()
            .flatMap(tick -> collectMetrics().onErrorResume(error -> {
                log.warn("cagg metrics collection failed", error);
                return Mono.empty();
            }), 1)
            .subscribe();
    }

    Mono<Void> collectMetrics() {
        return sqlExecutor.select(QUERY_SQL).collectList()
            .timeout(Duration.ofSeconds(30))
            .doOnNext(rows -> {
                Set<String> live = new HashSet<>();
                for (Map<String, Object> row : rows) {
                    String schema = String.valueOf(row.get("view_schema"));
                    String view = String.valueOf(row.get("view_name"));
                    String job = String.valueOf(row.get("job_id"));
                    String key = schema + "." + view + ":" + job;
                    live.add(key);
                    Metrics value = metrics.computeIfAbsent(key, ignore -> new Metrics(schema, view, job));
                    value.lag.set(number(row, "lag_seconds", -1));
                    value.watermarkLag.set(number(row, "watermark_lag_seconds", -1));
                    long failures = number(row, "failures", 0);
                    long previous = value.failures.getAndSet(failures);
                    if (failures > previous) {
                        log.warn("cagg {}: {} new refresh failure(s)", key, failures - previous);
                    }
                }
                metrics.keySet().removeIf(key -> {
                    if (live.contains(key)) return false;
                    metrics.get(key).remove();
                    return true;
                });
            }).then();
    }

    private static long number(Map<String, Object> row, String key, long fallback) {
        Object value = row.get(key);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private final class Metrics {
        final AtomicLong lag = new AtomicLong(-1);
        final AtomicLong watermarkLag = new AtomicLong(-1);
        final AtomicLong failures = new AtomicLong();
        final List<Meter> meters = new ArrayList<>();

        Metrics(String schema, String view, String job) {
            Tags tags = Tags.of("schema", schema, "view", view, "job", job);
            meters.add(Gauge.builder("timescaledb.cagg.refresh.lag_seconds", lag, AtomicLong::get)
                .tags(tags).description("Seconds since successful job finish; NOT data completeness")
                .register(meterRegistry));
            meters.add(Gauge.builder("timescaledb.cagg.watermark.lag_seconds", watermarkLag, AtomicLong::get)
                .tags(tags).description("Age of materialization watermark; late-data invalidations may still exist")
                .register(meterRegistry));
            meters.add(FunctionCounter.builder("timescaledb.cagg.refresh.failed", failures, AtomicLong::get)
                .tags(tags).description("Database cumulative job failures, not polling failures")
                .register(meterRegistry));
        }

        void remove() {
            meters.forEach(meterRegistry::remove);
        }
    }

    @Override
    public void destroy() {
        if (scheduler != null) scheduler.dispose();
        metrics.values().forEach(Metrics::remove);
        metrics.clear();
    }
}
