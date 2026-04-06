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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.reactive.ReactiveSqlExecutor;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TimescaleDB continuous aggregate 刷新延迟监控。
 * <p>
 * 每 5 分钟查询一次 timescaledb_information.job_stats，
 * 通过 Micrometer 暴露以下指标：
 * <ul>
 *   <li>{@code timescaledb.cagg.refresh.lag_seconds{view}} — 距上次成功刷新的秒数（-1 表示从未刷新）</li>
 *   <li>{@code timescaledb.cagg.refresh.failed{view}}     — 刷新失败计数</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class CaggMonitorService implements InitializingBean, DisposableBean {

    private static final String QUERY_SQL =
        "SELECT j.application_name AS view_name," +
        " CASE WHEN js.last_successful_finish IS NULL THEN -1" +
        "      ELSE extract(epoch from (now()-js.last_successful_finish))" +
        " END AS lag_seconds," +
        " CASE WHEN js.last_run_status = 'Success' THEN 0 ELSE 1 END AS failed " +
        "FROM timescaledb_information.jobs j " +
        "LEFT JOIN timescaledb_information.job_stats js ON j.job_id = js.job_id " +
        "WHERE j.proc_name = 'policy_refresh_continuous_aggregate'";

    private final ReactiveSqlExecutor sqlExecutor;
    private final MeterRegistry meterRegistry;

    /** view_name → lag gauge value (already registered in meterRegistry) */
    private final ConcurrentHashMap<String, AtomicLong> lagMap = new ConcurrentHashMap<>();

    private Disposable scheduler;

    @Override
    public void afterPropertiesSet() {
        scheduler = Flux.interval(Duration.ofMinutes(5))
                        .onBackpressureDrop()
                        .flatMap(tick -> collectMetrics()
                            .onErrorResume(e -> {
                                log.warn("cagg monitor: metrics collection failed", e);
                                return Mono.empty();
                            }), 1)
                        .subscribe();
    }

    private Mono<Void> collectMetrics() {
        return sqlExecutor
            .select(SqlRequests.of(QUERY_SQL), ResultWrappers.map())
            .doOnNext(row -> {
                String view = String.valueOf(row.get("view_name"));

                long lag = row.get("lag_seconds") != null
                    ? ((Number) row.get("lag_seconds")).longValue() : -1L;

                lagMap.computeIfAbsent(view, v -> {
                    AtomicLong ref = new AtomicLong(lag);
                    Gauge.builder("timescaledb.cagg.refresh.lag_seconds", ref, AtomicLong::get)
                         .tag("view", v)
                         .description("Seconds since last successful cagg refresh (-1 = never)")
                         .register(meterRegistry);
                    return ref;
                }).set(lag);

                long failed = row.get("failed") != null
                    ? ((Number) row.get("failed")).longValue() : 0L;
                if (failed > 0) {
                    meterRegistry.counter("timescaledb.cagg.refresh.failed", "view", view)
                                 .increment();
                    log.warn("cagg monitor: refresh failed for view [{}]", view);
                }
            })
            .then();
    }

    @Override
    public void destroy() {
        if (scheduler != null && !scheduler.isDisposed()) {
            scheduler.dispose();
        }
    }
}
