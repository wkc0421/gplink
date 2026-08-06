package org.jetlinks.community.device.modbus;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Serializes and coalesces configuration refreshes. A burst of product/device
 * saves results in one refresh after the writes settle, while a new save during
 * a refresh schedules exactly one follow-up refresh.
 */
final class ModbusPollingReloadGate {

    private final Supplier<Mono<Void>> reloadAction;
    private final Duration debounce;
    private final AtomicLong requestedRevision = new AtomicLong();
    private final AtomicLong appliedRevision = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean();

    ModbusPollingReloadGate(Supplier<Mono<Void>> reloadAction, Duration debounce) {
        this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction");
        this.debounce = Objects.requireNonNull(debounce, "debounce");
    }

    Mono<Void> request() {
        requestedRevision.incrementAndGet();
        if (!running.compareAndSet(false, true)) {
            return Mono.empty();
        }
        return drain();
    }

    long getRequestedRevision() {
        return requestedRevision.get();
    }

    long getAppliedRevision() {
        return appliedRevision.get();
    }

    boolean isRunning() {
        return running.get();
    }

    private Mono<Void> drain() {
        return Mono.delay(debounce)
                .then(Mono.defer(() -> {
                    long revision = requestedRevision.get();
                    return reloadAction.get()
                            .then(Mono.defer(() -> {
                                appliedRevision.set(revision);
                                if (requestedRevision.get() != revision) {
                                    return drain();
                                }
                                synchronized (this) {
                                    if (requestedRevision.get() == revision) {
                                        running.set(false);
                                        return Mono.empty();
                                    }
                                }
                                return drain();
                            }));
                }))
                .doOnError(ignore -> running.set(false));
    }
}
