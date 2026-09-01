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
package org.jetlinks.community.timescaledb.timeseries;

import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.executor.reactive.ReactiveSqlExecutor;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrapper;
import org.jetlinks.community.Interval;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class TimescaleDBRetentionPolicyManagerTest {

    @Test
    void shouldCreateMissingPolicy() {
        AtomicInteger findCount = new AtomicInteger();
        TestSqlExecutor executor = new TestSqlExecutor(request -> {
            if (request.getSql().startsWith("SELECT job_id")) {
                return findCount.getAndIncrement() == 0
                    ? Flux.empty()
                    : Flux.just(job(1, true, true, true));
            }
            return Flux.just(Map.of("job_id", 1));
        });

        StepVerifier.create(manager(executor).ensure("device_metrics", Interval.ofDays(30)))
            .verifyComplete();

        assertThat(executor.requests).hasSize(3);
        SqlRequest create = executor.requests.get(1);
        assertThat(executor.mutationRequests).containsExactly(create);
        assertThat(create.getSql())
            .contains("add_retention_policy")
            .contains("schedule_interval => INTERVAL '1 day'")
            .contains("if_not_exists => TRUE");
        assertThat(create.getParameters())
            .containsExactly("\"public\".\"device_metrics\"", "30 days");
    }

    @Test
    void shouldUpdateIncorrectPolicy() {
        AtomicInteger findCount = new AtomicInteger();
        TestSqlExecutor executor = new TestSqlExecutor(request -> {
            if (request.getSql().startsWith("SELECT job_id")) {
                return findCount.getAndIncrement() == 0
                    ? Flux.just(job(7, false, false, false))
                    : Flux.just(job(7, true, true, true));
            }
            return Flux.just(Map.of("job_id", 7));
        });

        StepVerifier.create(manager(executor).ensure("system_monitor", Interval.ofDays(30)))
            .verifyComplete();

        assertThat(executor.requests).hasSize(3);
        SqlRequest update = executor.requests.get(1);
        assertThat(executor.mutationRequests).containsExactly(update);
        assertThat(update.getSql())
            .contains("alter_job")
            .contains("schedule_interval => INTERVAL '1 day'")
            .contains("scheduled => TRUE")
            .contains("jsonb_set(config, '{drop_after}'");
        assertThat(update.getParameters()).containsExactly("30 days", 7);
    }

    @Test
    void shouldLeaveCorrectPolicyUnchanged() {
        TestSqlExecutor executor = new TestSqlExecutor(request ->
            Flux.just(job(9, true, true, true)));

        StepVerifier.create(manager(executor).ensure("redis_latest", Interval.ofDays(30)))
            .verifyComplete();

        assertThat(executor.requests).hasSize(1);
        assertThat(executor.requests.get(0).getSql()).startsWith("SELECT job_id");
    }

    @Test
    void shouldFailWhenPolicyIsMissingAfterCreate() {
        TestSqlExecutor executor = new TestSqlExecutor(request -> Flux.empty());

        StepVerifier.create(manager(executor).ensure("device_metrics", Interval.ofDays(30)))
            .expectErrorMessage("TimescaleDB retention policy is missing after create: public.device_metrics")
            .verify();
    }

    @Test
    void shouldFailWhenPolicyStillMismatchesAfterUpdate() {
        TestSqlExecutor executor = new TestSqlExecutor(request -> {
            if (request.getSql().startsWith("SELECT job_id")) {
                return Flux.just(job(7, false, false, false));
            }
            return Flux.empty();
        });

        StepVerifier.create(manager(executor).ensure("system_monitor", Interval.ofDays(30)))
            .expectErrorMessage("TimescaleDB retention policy does not match expected configuration after coordination: public.system_monitor")
            .verify();
    }

    @Test
    void shouldPropagateDatabaseFailure() {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        TestSqlExecutor executor = new TestSqlExecutor(request -> Flux.error(failure));

        StepVerifier.create(manager(executor).ensure("device_session_metric", Interval.ofDays(30)))
            .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
            .verify();
    }

    private static TimescaleDBRetentionPolicyManager manager(TestSqlExecutor executor) {
        return new TimescaleDBRetentionPolicyManager("public", executor);
    }

    private static Map<String, Object> job(int jobId,
                                           boolean scheduled,
                                           boolean scheduleMatches,
                                           boolean retentionMatches) {
        return Map.of(
            "job_id", jobId,
            "scheduled", scheduled,
            "schedule_matches", scheduleMatches,
            "retention_matches", retentionMatches
        );
    }

    private static final class TestSqlExecutor implements ReactiveSqlExecutor {

        private final Function<SqlRequest, Flux<Map<String, Object>>> responder;
        private final List<SqlRequest> requests = new ArrayList<>();
        private final List<SqlRequest> mutationRequests = new ArrayList<>();

        private TestSqlExecutor(Function<SqlRequest, Flux<Map<String, Object>>> responder) {
            this.responder = responder;
        }

        @Override
        public Mono<Integer> update(Publisher<SqlRequest> publisher) {
            return Mono.error(new UnsupportedOperationException());
        }

        @Override
        public Mono<Void> execute(Publisher<SqlRequest> publisher) {
            return Flux
                .from(publisher)
                .concatMap(request -> {
                    requests.add(request);
                    mutationRequests.add(request);
                    return responder.apply(request);
                })
                .then();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <E> Flux<E> select(Publisher<SqlRequest> publisher, ResultWrapper<E, ?> wrapper) {
            return Flux
                .from(publisher)
                .concatMap(request -> {
                    requests.add(request);
                    return responder.apply(request);
                })
                .map(row -> (E) row);
        }
    }
}
