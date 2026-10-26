package org.jetlinks.community.timescaledb.thing;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.reactive.r2dbc.R2dbcReactiveSqlExecutor;
import org.jetlinks.community.things.data.PropertyBoundaryValues;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jetlinks.community.timescaledb.thing.CaggTestSupport.*;

/** Run only against an explicitly provisioned disposable PostgreSQL database. */
class PropertyBoundaryIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static TestExecutor executor;
    private String table;

    @BeforeAll
    static void connect() {
        String url = System.getProperty("boundary.test.url");
        Assumptions.assumeTrue(url != null, "No disposable boundary.test.url configured");
        executor = new TestExecutor(ConnectionFactories.get(url));
    }

    @BeforeEach
    void createFixture() {
        table = "boundary_it_" + Long.toUnsignedString(System.nanoTime(), 36);
        execute("CREATE TABLE public.\"" + table + "\" (\"timestamp\" timestamp NOT NULL,"
            + "\"deviceId\" varchar NOT NULL,property varchar NOT NULL,value varchar)");
        insert("2024-01-19 23:00:00", "device-1", "p", "1718469.63");
        insert("2024-01-21 09:00:00", "device-1", "p", null);
        insert("2024-01-21 09:00:00", "other-device", "p", "999");
        insert("2024-01-21 09:00:00", "device-1", "p2", "999");
        insert("2024-01-21 10:00:00", "device-1", "p", "1718479.63");
        insert("2024-01-21 11:00:00", "device-1", "p", "1718499.63");
    }

    @AfterEach
    void dropFixture() {
        if (table != null) execute("DROP TABLE public.\"" + table + "\"");
    }

    @Test
    void missingDayUsesLastReadingBeforeStartAndFirstNonNullReadingAfterEnd() {
        long start = time("2024-01-20 00:00:00");
        long middle = time("2024-01-20 12:00:00");
        long end = time("2024-01-21 00:00:00");
        PropertyBoundaryValues values = query("device-1").boundaries(
            table, "p", start, end, List.of(start, middle, end)).block(WAIT);

        assertThat(((Number) values.getInitialValue()).doubleValue()).isEqualTo(1718469.63D);
        assertThat(values.getValues()).extracting(value -> ((Number) value.getValue()).doubleValue())
            .containsExactly(1718469.63D, 1718469.63D, 1718479.63D);
    }

    @Test
    void observedEndTakesPriorityOverFollowingReading() {
        long start = time("2024-01-21 09:00:00");
        long end = time("2024-01-21 10:00:00");
        PropertyBoundaryValues values = query("device-1").boundaries(
            table, "p", start, end, List.of(start, end)).block(WAIT);

        assertThat(((Number) values.getValues().get(1).getValue()).doubleValue()).isEqualTo(1718479.63D);
    }

    @Test
    void noFollowingReadingKeepsEndNullAndDoesNotCopyPreviousStart() {
        insert("2024-01-19 23:00:00", "orphan", "p", "10");
        long start = time("2024-01-20 00:00:00");
        long end = time("2024-01-21 00:00:00");
        PropertyBoundaryValues values = query("orphan").boundaries(
            table, "p", start, end, List.of(start, end)).block(WAIT);

        assertThat(((Number) values.getInitialValue()).doubleValue()).isEqualTo(10D);
        assertThat(values.getValues().get(1).getValue()).isNull();
    }

    @Test
    void completelyAbsentReadingsStayNull() {
        long start = time("2024-01-20 00:00:00");
        long end = time("2024-01-21 00:00:00");
        PropertyBoundaryValues values = query("absent").boundaries(
            table, "p", start, end, List.of(start, end)).block(WAIT);

        assertThat(values.getInitialValue()).isNull();
        assertThat(values.getValues()).allSatisfy(value -> assertThat(value.getValue()).isNull());
    }

    @Test
    void realZeroReadingIsRetained() {
        insert("2024-01-19 23:00:00", "zero", "p", "10");
        insert("2024-01-20 12:00:00", "zero", "p", "0");
        long start = time("2024-01-20 00:00:00");
        long end = time("2024-01-21 00:00:00");
        PropertyBoundaryValues values = query("zero").boundaries(
            table, "p", start, end, List.of(start, end)).block(WAIT);

        assertThat(((Number) values.getValues().get(1).getValue()).doubleValue()).isEqualTo(0D);
    }

    @Test
    void futureDatedSampleIsNotUsedAsFollowingFallback() {
        insert("2024-01-19 23:00:00", "future", "p", "10");
        execute("INSERT INTO public.\"" + table + "\" VALUES (?::timestamp,?,?,?)",
            AggregationWindow.local(System.currentTimeMillis() + 86_400_000L), "future", "p", "20");
        long start = time("2024-01-20 00:00:00");
        long end = time("2024-01-21 00:00:00");
        PropertyBoundaryValues values = query("future").boundaries(
            table, "p", start, end, List.of(start, end)).block(WAIT);

        assertThat(values.getValues().get(1).getValue()).isNull();
    }

    private Query query(String device) {
        return new Query(database(executor, table), config(), device);
    }

    private void insert(String timestamp, String device, String property, String value) {
        execute("INSERT INTO public.\"" + table + "\" VALUES (?::timestamp,?,?,?)",
            LocalDateTime.parse(timestamp.replace(' ', 'T')), device, property, value);
    }

    private void execute(String sql, Object... parameters) {
        executor.execute(SqlRequests.of(sql, parameters)).block(WAIT);
    }

    private static class TestExecutor extends R2dbcReactiveSqlExecutor {
        private final ConnectionFactory factory;
        private TestExecutor(ConnectionFactory factory) { this.factory = factory; }
        @Override protected Mono<Connection> getConnection() {
            return Mono.from(factory.create()).flatMap(connection ->
                Flux.from(connection.createStatement("SET TIME ZONE 'UTC'").execute())
                    .concatMap(Result::getRowsUpdated).then(Mono.just(connection)));
        }
        @Override protected void releaseConnection(SignalType signal, Connection connection) {
            Mono.from(connection.close()).subscribe();
        }
    }
}
