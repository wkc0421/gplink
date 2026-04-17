package org.jetlinks.protocol.modbus.pending;

import org.jetlinks.protocol.modbus.frame.ModbusFunctionCode;
import org.jetlinks.protocol.modbus.frame.ModbusRequest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-gateway pending-request tracker. Modbus RTU is strictly half-duplex:
 * once a request goes on the wire, no further request may be sent until the
 * response (or a timeout) arrives. A single {@link PendingRequestQueue}
 * enforces that invariant across every slave reachable through the same
 * gateway connection.
 *
 * <p>The queue is keyed by gateway device id. Each gateway has at most one
 * in-flight request; additional requests pile up in a FIFO waiting list.
 */
public final class PendingRequestQueue {

    private final Map<String, GatewayState> byGateway = new ConcurrentHashMap<>();

    public void offer(String gatewayId, PendingRequest request) {
        if (gatewayId == null || request == null) {
            throw new IllegalArgumentException("gatewayId and request must not be null");
        }
        GatewayState state = byGateway.computeIfAbsent(gatewayId, id -> new GatewayState());
        synchronized (state) {
            state.waiting.addLast(request);
        }
    }

    /**
     * Promote the next waiting request to in-flight for the given gateway.
     * Returns the request that should now be written to the wire, or
     * {@code null} when the queue is empty or something is already in flight.
     */
    public PendingRequest promote(String gatewayId) {
        GatewayState state = byGateway.get(gatewayId);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            if (state.inFlight != null) {
                return null;
            }
            PendingRequest next = state.waiting.pollFirst();
            if (next == null) {
                return null;
            }
            next.markSent();
            state.inFlight = next;
            return next;
        }
    }

    public PendingRequest peekInFlight(String gatewayId) {
        GatewayState state = byGateway.get(gatewayId);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            return state.inFlight;
        }
    }

    /**
     * Find an in-flight request matching the given slaveId and function code,
     * searching across all gateway queues. This is used during decode to
     * correlate a response without relying on the session id matching the
     * queue key (they may differ before the session is fully upgraded).
     *
     * @return the matching in-flight request, or {@code null} if none found
     */
    public PendingRequest findInFlightBySlaveAndFc(int slaveId, ModbusFunctionCode fc) {
        for (Map.Entry<String, GatewayState> entry : byGateway.entrySet()) {
            GatewayState state = entry.getValue();
            synchronized (state) {
                PendingRequest inFlight = state.inFlight;
                if (inFlight != null
                        && inFlight.getRequest().getSlaveId() == slaveId
                        && inFlight.getRequest().getFunction() == fc) {
                    return inFlight;
                }
            }
        }
        return null;
    }

    /**
     * Acknowledge the current in-flight request for the given gateway.
     * Call after a matching response has been emitted.
     */
    public PendingRequest ack(String gatewayId) {
        GatewayState state = byGateway.get(gatewayId);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            PendingRequest done = state.inFlight;
            state.inFlight = null;
            return done;
        }
    }

    /**
     * Drop everything queued for a gateway (called on disconnect). Returns the
     * orphaned requests so the caller may emit error replies for them.
     */
    public List<PendingRequest> drain(String gatewayId) {
        GatewayState state = byGateway.remove(gatewayId);
        if (state == null) {
            return Collections.emptyList();
        }
        synchronized (state) {
            List<PendingRequest> orphans = new ArrayList<>();
            if (state.inFlight != null) {
                orphans.add(state.inFlight);
                state.inFlight = null;
            }
            orphans.addAll(state.waiting);
            state.waiting.clear();
            return orphans;
        }
    }

    /**
     * Sweep all gateways and remove in-flight requests that have exceeded
     * their configured timeout. Returns all expired requests so the caller
     * can emit timeout error replies and then drive the next queued request
     * via {@link #promote(String)}.
     *
     * <p>Must be called periodically (e.g. at the start of every
     * {@code decode()} invocation) to prevent a silent timeout from
     * permanently blocking the gateway bus.
     */
    public List<PendingRequest> sweepAllExpired(long nowMillis) {
        List<PendingRequest> expired = null;
        for (Map.Entry<String, GatewayState> entry : byGateway.entrySet()) {
            GatewayState state = entry.getValue();
            synchronized (state) {
                PendingRequest inFlight = state.inFlight;
                if (inFlight != null && inFlight.isExpired(nowMillis)) {
                    state.inFlight = null;
                    if (expired == null) {
                        expired = new ArrayList<>();
                    }
                    expired.add(inFlight);
                }
            }
        }
        return expired == null ? Collections.<PendingRequest>emptyList() : expired;
    }

    /**
     * Sweep a single gateway and remove its in-flight request if expired.
     */
    public PendingRequest sweepExpired(String gatewayId, long nowMillis) {
        GatewayState state = byGateway.get(gatewayId);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            PendingRequest inFlight = state.inFlight;
            if (inFlight != null && inFlight.isExpired(nowMillis)) {
                state.inFlight = null;
                return inFlight;
            }
            return null;
        }
    }

    static final class GatewayState {
        final Deque<PendingRequest> waiting = new ArrayDeque<>();
        PendingRequest inFlight;
    }

    public static final class PendingRequest {
        private final String gatewayId;
        private final String deviceId;
        private final String messageId;
        private final ModbusRequest request;
        private final long timeoutMillis;
        private long sentAt = -1L;

        public PendingRequest(String gatewayId,
                              String deviceId,
                              String messageId,
                              ModbusRequest request,
                              long timeoutMillis) {
            this.gatewayId = gatewayId;
            this.deviceId = deviceId;
            this.messageId = messageId;
            this.request = request;
            this.timeoutMillis = timeoutMillis;
        }

        /** Gateway queue key — the key passed to {@link PendingRequestQueue#offer}. */
        public String getGatewayId() {
            return gatewayId;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public String getMessageId() {
            return messageId;
        }

        public ModbusRequest getRequest() {
            return request;
        }

        public long getTimeoutMillis() {
            return timeoutMillis;
        }

        public long getSentAt() {
            return sentAt;
        }

        void markSent() {
            this.sentAt = System.currentTimeMillis();
        }

        boolean isExpired(long nowMillis) {
            return sentAt > 0 && nowMillis - sentAt > timeoutMillis;
        }
    }
}
