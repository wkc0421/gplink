package org.jetlinks.community.timescaledb.thing;

import io.r2dbc.postgresql.api.PostgresqlConnection;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Wrapped;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.core.FeatureId;
import org.hswebframework.ezorm.rdb.executor.reactive.r2dbc.R2dbcReactiveSqlExecutor;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.util.function.Function;

/** Read-only aggregation connections with server deadlines and explicit PostgreSQL cancellation. */
@Slf4j
public class CancellableAggregationExecutor extends R2dbcReactiveSqlExecutor {
    public static final FeatureId<CancellableAggregationExecutor> ID =
        FeatureId.of("timescaledb-aggregation-executor");
    static final String TIMEOUT_CONTEXT = CancellableAggregationExecutor.class.getName() + ".timeout";
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(5);
    private final ConnectionFactory factory;

    public CancellableAggregationExecutor(ConnectionFactory factory) {
        this.factory = java.util.Objects.requireNonNull(factory);
    }

    @Override public String getId() { return ID.getId(); }

    @Override protected Mono<Connection> getConnection() {
        // Borrow independently of a caller's transaction: cancellation must never
        // affect unrelated statements on a transaction-bound shared connection.
        return Mono.from(factory.create());
    }

    @Override protected <T> Flux<T> doInConnection(Function<Connection, Publisher<T>> operation) {
        return Flux.deferContextual(context -> {
            Duration timeout = context.getOrDefault(TIMEOUT_CONTEXT, Duration.ofSeconds(30));
            long millis = timeout.toMillis();
            if (millis < 1 || millis > Integer.MAX_VALUE) {
                return Flux.error(new IllegalArgumentException("aggregation timeout must be 1..2147483647 milliseconds"));
            }
            return Flux.usingWhen(getConnection(),
                connection -> {
                    nativeConnection(connection); // fail safely before running a query if cancellation is unsupported
                    return Flux.from(connection.createStatement(
                        "BEGIN READ ONLY; SET LOCAL statement_timeout='" + millis + "ms'").execute())
                        .concatMap(Result::getRowsUpdated).thenMany(Flux.defer(() -> Flux.from(operation.apply(connection))));
                },
                connection -> cleanup(connection, false),
                (connection, error) -> cleanup(connection, false),
                connection -> cleanup(connection, true));
        });
    }

    private Mono<Void> cleanup(Connection connection, boolean cancel) {
        return Mono.defer(() -> {
            PostgresqlConnection nativeConnection;
            try {
                nativeConnection = nativeConnection(connection);
            } catch (IllegalStateException error) {
                return Mono.from(connection.close());
            }
            Mono<Void> cancelRequest = cancel ? nativeConnection.cancelRequest() : Mono.empty();
            return cancelRequest.then(Mono.defer(() -> Mono.from(connection.rollbackTransaction())))
                .timeout(CLEANUP_TIMEOUT)
                .onErrorResume(error -> {
                    // Do not return an uncertain connection to the pool for another request.
                    log.warn("Failed to clean up aggregation connection; closing physical connection", error);
                    return nativeConnection.close().timeout(CLEANUP_TIMEOUT).onErrorResume(closeError -> {
                        log.warn("Failed to close physical aggregation connection", closeError);
                        return Mono.empty();
                    });
                })
                .then(Mono.defer(() -> Mono.from(connection.close())));
        });
    }

    private static PostgresqlConnection nativeConnection(Connection connection) {
        // Stop at the PostgreSQL API: its own Wrapped.unwrap() exposes the
        // protocol client, not another SPI Connection.
        Object target = connection;
        for (int depth = 0; depth < 10; depth++) {
            if (target instanceof PostgresqlConnection) return (PostgresqlConnection) target;
            if (!(target instanceof Wrapped)) break;
            target = ((Wrapped<?>) target).unwrap();
        }
        throw new IllegalStateException("PostgreSQL connection does not expose cancelRequest");
    }

    @Override protected void releaseConnection(SignalType signal, Connection connection) {
        // usingWhen above owns asynchronous cleanup on completion, error and cancellation.
    }
}
