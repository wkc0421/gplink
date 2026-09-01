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

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.reactive.r2dbc.R2dbcReactiveSqlExecutor;
import org.jetlinks.community.Interval;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.r2dbc.spi.ConnectionFactoryOptions.DATABASE;
import static io.r2dbc.spi.ConnectionFactoryOptions.DRIVER;
import static io.r2dbc.spi.ConnectionFactoryOptions.HOST;
import static io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD;
import static io.r2dbc.spi.ConnectionFactoryOptions.PORT;
import static io.r2dbc.spi.ConnectionFactoryOptions.USER;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class TimescaleDBRetentionPolicyManagerIntegrationTest {

    private static final List<String> TABLES = List.of(
        "device_metrics",
        "device_gateway_monitor",
        "device_session_metric",
        "system_monitor",
        "redis_latest"
    );

    @Container
    private static final GenericContainer<?> TIMESCALE_DB =
        new GenericContainer<>(DockerImageName.parse("timescale/timescaledb:2.29.1-pg16"))
            .withEnv("POSTGRES_PASSWORD", "test")
            .withEnv("TIMESCALEDB_TELEMETRY", "off")
            .withExposedPorts(5432)
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofMinutes(2));

    private static R2dbcReactiveSqlExecutor executor;

    @BeforeAll
    static void setUpDatabase() {
        ConnectionFactoryOptions options = ConnectionFactoryOptions
            .builder()
            .option(DRIVER, "postgresql")
            .option(HOST, TIMESCALE_DB.getHost())
            .option(PORT, TIMESCALE_DB.getMappedPort(5432))
            .option(USER, "postgres")
            .option(PASSWORD, "test")
            .option(DATABASE, "postgres")
            .build();
        executor = new TestConnectionFactorySqlExecutor(ConnectionFactories.get(options));

        executor
            .execute(SqlRequests.of("CREATE EXTENSION IF NOT EXISTS timescaledb"))
            .block(Duration.ofSeconds(30));
        for (String table : TABLES) {
            createHypertable(table);
        }

        // Verify that coordination also corrects a pre-existing policy.
        executor
            .select(
                "SELECT public.add_retention_policy(?::regclass, INTERVAL '7 days')",
                "public.\"device_metrics\""
            )
            .then()
            .block(Duration.ofSeconds(30));
    }

    @Test
    void shouldScheduleThirtyDayRetentionForAllMetricsTables() {
        TimescaleDBRetentionPolicyManager manager =
            new TimescaleDBRetentionPolicyManager("public", executor);

        for (String table : TABLES) {
            manager
                .ensure(table, Interval.ofDays(30))
                .block(Duration.ofSeconds(30));
        }

        List<Map<String, Object>> jobs = executor
            .select(
                "SELECT hypertable_name," +
                    " scheduled," +
                    " schedule_interval::text AS schedule_interval," +
                    " config->>'drop_after' AS drop_after " +
                    "FROM timescaledb_information.jobs " +
                    "WHERE proc_name = 'policy_retention' " +
                    "AND hypertable_schema = 'public' " +
                    "AND hypertable_name IN (" +
                    "'device_metrics','device_gateway_monitor','device_session_metric'," +
                    "'system_monitor','redis_latest')"
            )
            .collectList()
            .block(Duration.ofSeconds(30));

        assertThat(jobs)
            .hasSize(5)
            .allSatisfy(job -> {
                assertThat(job.get("scheduled")).isEqualTo(true);
                assertThat(job.get("schedule_interval")).isEqualTo("1 day");
                assertThat(job.get("drop_after")).isEqualTo("30 days");
            });
        assertThat(jobs)
            .extracting(job -> String.valueOf(job.get("hypertable_name")))
            .containsExactlyInAnyOrderElementsOf(TABLES);

        assertThat(executor
                       .select("SELECT extversion FROM pg_extension WHERE extname = 'timescaledb'")
                       .map(row -> String.valueOf(row.get("extversion")))
                       .blockFirst(Duration.ofSeconds(30)))
            .isEqualTo("2.29.1");
    }

    private static void createHypertable(String table) {
        executor
            .execute(SqlRequests.of(
                "CREATE TABLE public.\"" + table + "\" (\"timestamp\" TIMESTAMPTZ NOT NULL)"
            ))
            .block(Duration.ofSeconds(30));
        executor
            .select(
                "SELECT public.create_hypertable(?::regclass, 'timestamp', if_not_exists => TRUE)",
                "public.\"" + table + "\""
            )
            .then()
            .block(Duration.ofSeconds(30));
    }

    private static final class TestConnectionFactorySqlExecutor extends R2dbcReactiveSqlExecutor {

        private final ConnectionFactory connectionFactory;

        private TestConnectionFactorySqlExecutor(ConnectionFactory connectionFactory) {
            this.connectionFactory = connectionFactory;
        }

        @Override
        protected Mono<Connection> getConnection() {
            return Mono.from(connectionFactory.create());
        }

        @Override
        protected void releaseConnection(SignalType signalType, Connection connection) {
            Mono.from(connection.close()).subscribe();
        }
    }
}
