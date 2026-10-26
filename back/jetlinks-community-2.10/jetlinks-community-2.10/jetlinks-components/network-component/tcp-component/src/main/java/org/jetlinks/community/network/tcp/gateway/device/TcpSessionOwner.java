package org.jetlinks.community.network.tcp.gateway.device;

import org.jetlinks.community.PropertyConstants;
import org.jetlinks.community.gateway.DeviceGatewayHelper;
import org.jetlinks.core.device.DeviceConfigKey;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.device.session.DeviceSessionManager;
import org.jetlinks.core.server.session.ChildrenDeviceSession;
import org.jetlinks.core.server.session.DeviceSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** One physical connection generation. Never reactivated when its network reconnects. */
final class TcpSessionOwner implements DeviceGatewayHelper.SessionPolicy {
    final String accessId;
    private final DeviceSessionManager manager;
    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    private final Sinks.Empty<Void> drained = Sinks.empty();
    private volatile boolean active = true;
    private int pending;
    private final Mono<Void> cleanup;

    TcpSessionOwner(String accessId, DeviceSessionManager manager) {
        this.accessId = accessId;
        this.manager = manager;
        cleanup = Mono.defer(() -> {
            invalidate();
            return drained.asMono().thenMany(Flux.fromIterable(sessions.keySet()))
                .flatMapDelayError(this::remove, 32, 1).then();
        }).cache();
    }

    boolean isActive() {
        return active;
    }

    synchronized void invalidate() {
        active = false;
        if (pending == 0) drained.tryEmitEmpty();
    }

    <T> Mono<T> track(Supplier<Mono<T>> operation) {
        return Mono.defer(() -> {
            synchronized (this) {
                if (!active) return Mono.empty();
                pending++;
            }
            // A downstream disconnect must not cancel the post-compute cleanup.
            // Registry calls are bounded so shutdown can always drain this owner.
            return Mono.defer(operation).timeout(Duration.ofSeconds(10))
                .doFinally(ignored -> {
                    synchronized (this) {
                        pending--;
                        if (!active && pending == 0) drained.tryEmitEmpty();
                    }
                }).cache();
        });
    }

    Mono<Void> close() {
        invalidate();
        return cleanup;
    }

    Mono<Void> validate(DeviceRegistry registry, String deviceId, boolean strict) {
        return validateAccess(registry, deviceId, accessId, strict);
    }

    static Mono<Void> validateAccess(DeviceRegistry registry, String deviceId, String accessId, boolean strict) {
        if (deviceId == null || deviceId.trim().isEmpty()) return Mono.empty();
        return Mono.defer(() -> registry.getDevice(deviceId))
            .flatMap(device -> device.getConfig(PropertyConstants.accessId)
                .map(accessId::equals).defaultIfEmpty(!strict))
            // An unknown device cannot create a session; retain the protocol's
            // registration path and the server's legality timeout.
            .defaultIfEmpty(true)
            .flatMap(allowed -> allowed ? Mono.empty()
                : Mono.error(new IllegalStateException("Device [" + deviceId + "] does not belong to TCP access [" + accessId + "]")));
    }

    @Override
    public Mono<Void> validateChild(String parentId, DeviceOperator child) {
        return validateChildBinding(parentId, child);
    }

    private static Mono<Void> validateChildBinding(String parentId, DeviceOperator child) {
        return child.getConfig(DeviceConfigKey.parentGatewayId)
            .filter(parentId::equals)
            .switchIfEmpty(Mono.error(new IllegalStateException("Child [" + child.getDeviceId()
                + "] does not belong to parent [" + parentId + "]")))
            .then();
    }

    static Mono<Void> validateChildren(DeviceRegistry registry, String parentId, List<String> children) {
        if (parentId == null || parentId.isEmpty()) return Mono.empty();
        return Flux.fromIterable(children)
            .concatMap(id -> registry.getDevice(id).flatMap(child -> validateChildBinding(parentId, child)))
            .then();
    }

    static TcpDeviceSession tcpSession(DeviceSession session) {
        if (session.isWrapFrom(TcpDeviceSession.class)) return session.unwrap(TcpDeviceSession.class);
        if (session.isWrapFrom(ChildrenDeviceSession.class)) {
            return tcpSession(session.unwrap(ChildrenDeviceSession.class).getParent());
        }
        return null;
    }

    boolean owns(DeviceSession session) {
        TcpDeviceSession tcp = tcpSession(session);
        return tcp != null && tcp.owner() == this;
    }

    @Override
    public Mono<DeviceSession> update(DeviceSession existing, Supplier<Mono<DeviceSession>> create,
                                      Supplier<Mono<DeviceSession>> keep) {
        if (!active) return Mono.error(new IllegalStateException("TCP connection has closed"));
        TcpDeviceSession tcp = tcpSession(existing);
        if (tcp != null && tcp.owner() == this && existing.isAlive()) return keep.get();
        if (!existing.isAlive() || (tcp != null && tcp.owner().accessId.equals(accessId))) return create.get();
        return Mono.error(new IllegalStateException("Device [" + existing.getDeviceId() + "] already has a session owned by another access"));
    }

    @Override
    public void accepted(DeviceSession session) {
        if (owns(session)) sessions.put(session.getDeviceId(), session);
    }

    @Override
    public Mono<Long> remove(String deviceId) {
        return manager.remove(deviceId, this::owns)
            .doOnSuccess(ignored -> sessions.remove(deviceId));
    }
}
