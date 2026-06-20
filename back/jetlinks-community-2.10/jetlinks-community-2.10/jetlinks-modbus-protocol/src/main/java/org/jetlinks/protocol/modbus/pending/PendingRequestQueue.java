package org.jetlinks.protocol.modbus.pending;

import org.jetlinks.protocol.modbus.frame.ModbusFunctionCode;
import org.jetlinks.protocol.modbus.frame.ModbusRequest;
import org.jetlinks.protocol.modbus.frame.ModbusResponse;

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

    private static final int DEFAULT_MAX_WAITING_PER_GATEWAY = 256;

    private final Map<String, GatewayState> byGateway = new ConcurrentHashMap<>();
    private final int maxWaitingPerGateway;

    public PendingRequestQueue() {
        this(DEFAULT_MAX_WAITING_PER_GATEWAY);
    }

    public PendingRequestQueue(int maxWaitingPerGateway) {
        this.maxWaitingPerGateway = Math.max(maxWaitingPerGateway, 1);
    }

    public boolean offer(String gatewayId, PendingRequest request) {
        if (gatewayId == null || request == null) {
            throw new IllegalArgumentException("gatewayId and request must not be null");
        }
        GatewayState state = byGateway.computeIfAbsent(gatewayId, id -> new GatewayState());
        synchronized (state) {
            if (state.waiting.size() >= maxWaitingPerGateway) {
                return false;
            }
            state.waiting.addLast(request);
            return true;
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
            next.markPromoted(System.currentTimeMillis());
            state.inFlight = next;
            return next;
        }
    }

    public long dispatchDelay(String gatewayId, long dispatchIntervalMillis, long nowMillis) {
        if (dispatchIntervalMillis <= 0) {
            return 0;
        }
        GatewayState state = byGateway.get(gatewayId);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            if (state.lastSentAt <= 0) {
                return 0;
            }
            long nextAllowedAt = state.lastSentAt + dispatchIntervalMillis;
            return Math.max(nextAllowedAt - nowMillis, 0);
        }
    }

    public void markSent(PendingRequest request, long sentAt) {
        if (request == null) {
            return;
        }
        GatewayState state = byGateway.get(request.getGatewayId());
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.inFlight == request) {
                request.markSent(sentAt);
                state.lastSentAt = sentAt;
            }
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
     * Find an in-flight request for one gateway. This is the normal decode
     * correlation path and prevents responses from one physical connection
     * from acknowledging another gateway's request.
     */
    public PendingRequest findInFlight(String gatewayId, int slaveId, ModbusFunctionCode fc) {
        GatewayState state = byGateway.get(gatewayId);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            PendingRequest inFlight = state.inFlight;
            if (matches(inFlight, slaveId, fc)) {
                return inFlight;
            }
            return null;
        }
    }

    /**
     * Find an in-flight request for one gateway using both Modbus address and
     * response shape. RTU has no transaction id, so validating the response
     * byte-count or write echo reduces the chance of a late response acking
     * the next request with the same slave/function.
     */
    public PendingRequest findInFlight(String gatewayId, ModbusResponse response) {
        GatewayState state = byGateway.get(gatewayId);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            PendingRequest inFlight = state.inFlight;
            if (matches(inFlight, response)) {
                return inFlight;
            }
            return null;
        }
    }

    /**
     * Find all in-flight requests matching the given slaveId and function code
     * across gateways. This should only be used as a fallback for unknown TCP
     * sessions, and only when exactly one candidate exists.
     */
    public List<PendingRequest> findInFlightCandidates(int slaveId, ModbusFunctionCode fc) {
        List<PendingRequest> candidates = null;
        for (Map.Entry<String, GatewayState> entry : byGateway.entrySet()) {
            GatewayState state = entry.getValue();
            synchronized (state) {
                PendingRequest inFlight = state.inFlight;
                if (matches(inFlight, slaveId, fc)) {
                    if (candidates == null) {
                        candidates = new ArrayList<>();
                    }
                    candidates.add(inFlight);
                }
            }
        }
        return candidates == null ? Collections.<PendingRequest>emptyList() : candidates;
    }

    /**
     * Find all strict response matches across gateways. This is only for
     * unknown sessions and should be accepted by callers only when unique.
     */
    public List<PendingRequest> findInFlightCandidates(ModbusResponse response) {
        List<PendingRequest> candidates = null;
        for (Map.Entry<String, GatewayState> entry : byGateway.entrySet()) {
            GatewayState state = entry.getValue();
            synchronized (state) {
                PendingRequest inFlight = state.inFlight;
                if (matches(inFlight, response)) {
                    if (candidates == null) {
                        candidates = new ArrayList<>();
                    }
                    candidates.add(inFlight);
                }
            }
        }
        return candidates == null ? Collections.<PendingRequest>emptyList() : candidates;
    }

    /**
     * Legacy broad lookup retained for existing tests and callers. Prefer
     * {@link #findInFlight(String, int, ModbusFunctionCode)} in decode paths.
     *
     * @return the matching in-flight request, or {@code null} if none found
     */
    public PendingRequest findInFlightBySlaveAndFc(int slaveId, ModbusFunctionCode fc) {
        List<PendingRequest> candidates = findInFlightCandidates(slaveId, fc);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private boolean matches(PendingRequest request, int slaveId, ModbusFunctionCode fc) {
        return request != null
                && request.getRequest().getSlaveId() == slaveId
                && request.getRequest().getFunction() == fc;
    }

    private boolean matches(PendingRequest pending, ModbusResponse response) {
        if (pending == null || response == null) {
            return false;
        }
        ModbusRequest request = pending.getRequest();
        if (request.getSlaveId() != response.getSlaveId()
                || request.getFunction() != response.getFunction()) {
            return false;
        }
        if (response.isException()) {
            return true;
        }
        byte[] payload = response.getPayload();
        if (request.getFunction().isRead()) {
            int expectedBytes = request.getFunction().isBitOriented()
                    ? (request.getQuantity() + 7) / 8
                    : request.getQuantity() * 2;
            return payload != null && payload.length == expectedBytes;
        }
        return writeEchoMatches(request, payload);
    }

    private boolean writeEchoMatches(ModbusRequest request, byte[] payload) {
        if (payload == null || payload.length != 4) {
            return false;
        }
        int address = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        if (address != request.getAddress()) {
            return false;
        }
        int valueOrQuantity = ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        switch (request.getFunction()) {
            case WRITE_SINGLE_COIL:
            case WRITE_SINGLE_REGISTER:
                byte[] writeValue = request.getWriteValue();
                if (writeValue == null || writeValue.length < 2) {
                    return false;
                }
                int expectedValue = ((writeValue[0] & 0xFF) << 8) | (writeValue[1] & 0xFF);
                return valueOrQuantity == expectedValue;
            case WRITE_MULTIPLE_COILS:
            case WRITE_MULTIPLE_REGISTERS:
                return valueOrQuantity == request.getQuantity();
            default:
                return false;
        }
    }

    public List<PendingRequest> removeWaitingByLogicalMessageId(String gatewayId, String logicalMessageId) {
        if (logicalMessageId == null) {
            return Collections.emptyList();
        }
        GatewayState state = byGateway.get(gatewayId);
        if (state == null) {
            return Collections.emptyList();
        }
        synchronized (state) {
            List<PendingRequest> removed = null;
            java.util.Iterator<PendingRequest> iterator = state.waiting.iterator();
            while (iterator.hasNext()) {
                PendingRequest request = iterator.next();
                if (logicalMessageId.equals(request.getLogicalMessageId())) {
                    iterator.remove();
                    if (removed == null) {
                        removed = new ArrayList<>();
                    }
                    removed.add(request);
                }
            }
            return removed == null ? Collections.<PendingRequest>emptyList() : removed;
        }
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
        long lastSentAt;
    }

    public static final class PendingRequest {
        private final String gatewayId;
        private final String deviceId;
        private final String messageId;
        private final String logicalMessageId;
        private final ModbusRequest request;
        private final long timeoutMillis;
        private final long dispatchIntervalMillis;
        private long promotedAt = -1L;
        private long sentAt = -1L;

        public PendingRequest(String gatewayId,
                              String deviceId,
                              String messageId,
                              ModbusRequest request,
                              long timeoutMillis) {
            this(gatewayId, deviceId, messageId, messageId, request, timeoutMillis, 0L);
        }

        public PendingRequest(String gatewayId,
                              String deviceId,
                              String messageId,
                              ModbusRequest request,
                              long timeoutMillis,
                              long dispatchIntervalMillis) {
            this(gatewayId, deviceId, messageId, messageId, request, timeoutMillis, dispatchIntervalMillis);
        }

        public PendingRequest(String gatewayId,
                              String deviceId,
                              String messageId,
                              String logicalMessageId,
                              ModbusRequest request,
                              long timeoutMillis,
                              long dispatchIntervalMillis) {
            this.gatewayId = gatewayId;
            this.deviceId = deviceId;
            this.messageId = messageId;
            this.logicalMessageId = logicalMessageId == null ? messageId : logicalMessageId;
            this.request = request;
            this.timeoutMillis = timeoutMillis;
            this.dispatchIntervalMillis = Math.max(dispatchIntervalMillis, 0L);
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

        public String getLogicalMessageId() {
            return logicalMessageId;
        }

        public ModbusRequest getRequest() {
            return request;
        }

        public long getTimeoutMillis() {
            return timeoutMillis;
        }

        public long getDispatchIntervalMillis() {
            return dispatchIntervalMillis;
        }

        public long getSentAt() {
            return sentAt;
        }

        public long getPromotedAt() {
            return promotedAt;
        }

        void markPromoted(long promotedAt) {
            this.promotedAt = promotedAt;
        }

        void markSent(long sentAt) {
            this.sentAt = sentAt;
        }

        public boolean isExpired(long nowMillis) {
            long baseTime = sentAt > 0 ? sentAt : promotedAt;
            return baseTime > 0 && nowMillis - baseTime > timeoutMillis;
        }
    }
}
