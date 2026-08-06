package org.jetlinks.protocol.modbus;

import org.jetlinks.protocol.modbus.frame.ModbusFunctionCode;
import org.jetlinks.protocol.modbus.frame.ModbusRequest;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The only Modbus request execution queue for a physical gateway.
 *
 * <p>Manual and polling requests share the same FIFO. Each gateway has one
 * in-flight request at most. Timeouts are scheduled when a request is promoted
 * and use the immutable request token to avoid clearing a later request.</p>
 */
public final class ModbusGatewayRequestExecutor {

    public enum Source {
        MANUAL,
        POLLING
    }

    public enum CompletionType {
        RESPONSE,
        TIMEOUT,
        SEND_FAILED,
        CANCELLED,
        CONNECTION_INTERRUPTED
    }

    public interface Sender {
        Mono<Boolean> send(PendingRequest request);
    }

    public static final class Completion {
        private final CompletionType type;
        private final PendingRequest request;
        private final String message;

        Completion(CompletionType type, PendingRequest request, String message) {
            this.type = type;
            this.request = request;
            this.message = message;
        }

        public CompletionType getType() {
            return type;
        }

        public PendingRequest getRequest() {
            return request;
        }

        public String getMessage() {
            return message;
        }
    }

    private final Map<String, GatewayState> states = new ConcurrentHashMap<>();
    private final Map<String, Long> invalidLeaseOwners = new ConcurrentHashMap<>();
    private volatile Sender sender = request -> Mono.just(false);
    private volatile Predicate<PendingRequest> leaseValidator = this::defaultLeaseValid;

    public void setSender(Sender sender) {
        this.sender = sender == null ? request -> Mono.just(false) : sender;
    }

    public void setLeaseValidator(Predicate<PendingRequest> leaseValidator) {
        this.leaseValidator = leaseValidator == null ? this::defaultLeaseValid : leaseValidator;
    }

    /**
     * Enqueue one frame. Returns {@code true} only when this frame became the
     * first in-flight frame and therefore must be returned by codec.encode().
     */
    public boolean submit(PendingRequest request) {
        if (request == null || request.gatewayId == null) {
            throw new IllegalArgumentException("request and gatewayId must not be null");
        }
        GatewayState state = states.computeIfAbsent(request.gatewayId, ignore -> new GatewayState());
        synchronized (state) {
            state.waiting.addLast(request);
            return promoteLocked(state, request.gatewayId) == request;
        }
    }

    public PendingRequest peekInFlight(String gatewayId) {
        GatewayState state = states.get(gatewayId);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            return state.inFlight;
        }
    }

    public PendingRequest findInFlight(String gatewayId,
                                       int slaveId,
                                       ModbusFunctionCode function) {
        PendingRequest pending = peekInFlight(gatewayId);
        if (matches(pending, slaveId, function)) {
            return pending;
        }
        return null;
    }

    /**
     * Compatibility fallback for sessions whose device id is not available
     * yet. It refuses ambiguous matches across gateways.
     */
    public PendingRequest findUniqueInFlight(int slaveId, ModbusFunctionCode function) {
        PendingRequest matched = null;
        for (GatewayState state : states.values()) {
            synchronized (state) {
                if (matches(state.inFlight, slaveId, function)) {
                    if (matched != null) {
                        return null;
                    }
                    matched = state.inFlight;
                }
            }
        }
        return matched;
    }

    public PendingRequest acknowledge(String gatewayId, String requestToken) {
        GatewayState state = states.get(gatewayId);
        if (state == null) {
            return null;
        }
        PendingRequest completed;
        synchronized (state) {
            completed = state.inFlight;
            if (completed == null || !completed.requestToken.equals(requestToken)) {
                return null;
            }
            clearInFlightLocked(state);
            promoteAndSendLocked(state, gatewayId);
            removeIfEmptyLocked(gatewayId, state);
        }
        notifyCompletion(completed, CompletionType.RESPONSE, null);
        return completed;
    }

    /**
     * Cancel frames that have not been sent. An in-flight match cannot be
     * retracted from the wire and is only marked cancelled.
     */
    public List<PendingRequest> cancelByLogicalRequestId(String gatewayId, String logicalRequestId) {
        return cancel(gatewayId, request -> equals(logicalRequestId, request.logicalRequestId));
    }

    public List<PendingRequest> cancelByCycleId(String gatewayId, String cycleId) {
        return cancel(gatewayId, request -> equals(cycleId, request.cycleId));
    }

    public List<PendingRequest> cancelByLeaseOwnerToken(String gatewayId, String ownerToken) {
        return cancel(gatewayId, request -> request.source == Source.POLLING
                && equals(ownerToken, request.leaseOwnerToken));
    }

    /**
     * Fence a lease owner before cancelling its queued frames. The short-lived
     * fence also covers a frame that has been promoted but is still waiting for
     * its configured inter-frame delay in the sender.
     */
    public List<PendingRequest> invalidateLeaseOwnerToken(String gatewayId, String ownerToken) {
        if (gatewayId == null || ownerToken == null) {
            return Collections.emptyList();
        }
        long now = System.currentTimeMillis();
        invalidLeaseOwners.entrySet().removeIf(entry -> entry.getValue() <= now);
        invalidLeaseOwners.put(leaseKey(gatewayId, ownerToken), now + TimeUnit.MINUTES.toMillis(1));
        return cancelByLeaseOwnerToken(gatewayId, ownerToken);
    }

    private List<PendingRequest> cancel(String gatewayId, Predicate<PendingRequest> matcher) {
        GatewayState state = states.get(gatewayId);
        if (state == null) {
            return Collections.emptyList();
        }
        List<PendingRequest> cancelled = new ArrayList<>();
        synchronized (state) {
            if (state.inFlight != null && matcher.test(state.inFlight)) {
                state.inFlight.cancelled = true;
                cancelled.add(state.inFlight);
            }
            java.util.Iterator<PendingRequest> iterator = state.waiting.iterator();
            while (iterator.hasNext()) {
                PendingRequest request = iterator.next();
                if (matcher.test(request)) {
                    iterator.remove();
                    request.cancelled = true;
                    cancelled.add(request);
                }
            }
            removeIfEmptyLocked(gatewayId, state);
        }
        for (PendingRequest request : cancelled) {
            notifyCompletion(request, CompletionType.CANCELLED, "request cancelled before send");
        }
        return cancelled;
    }

    public List<PendingRequest> drain(String gatewayId, String reason) {
        GatewayState state = states.remove(gatewayId);
        if (state == null) {
            return Collections.emptyList();
        }
        List<PendingRequest> drained = new ArrayList<>();
        synchronized (state) {
            if (state.inFlight != null) {
                state.inFlight.cancelTimeout();
                drained.add(state.inFlight);
                state.inFlight = null;
            }
            drained.addAll(state.waiting);
            state.waiting.clear();
        }
        for (PendingRequest request : drained) {
            notifyCompletion(request, CompletionType.CONNECTION_INTERRUPTED, reason);
        }
        return drained;
    }

    public int gatewayStateCount() {
        return states.size();
    }

    public int waitingCount(String gatewayId) {
        GatewayState state = states.get(gatewayId);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.waiting.size();
        }
    }

    public void dispose() {
        for (String gatewayId : new ArrayList<>(states.keySet())) {
            drain(gatewayId, "modbus protocol disposed");
        }
        invalidLeaseOwners.clear();
    }

    private PendingRequest promoteLocked(GatewayState state, String gatewayId) {
        if (state.inFlight != null) {
            return null;
        }
        PendingRequest next = state.waiting.pollFirst();
        while (next != null && !canSend(next)) {
            next.cancelled = true;
            notifyCompletion(next, CompletionType.CANCELLED, "polling lease is no longer valid");
            next = state.waiting.pollFirst();
        }
        if (next == null) {
            removeIfEmptyLocked(gatewayId, state);
            return null;
        }
        state.inFlight = next;
        next.sentAt = System.currentTimeMillis();
        final String token = next.requestToken;
        next.timeoutTask = Schedulers
                .parallel()
                .schedule(() -> onTimeout(gatewayId, token),
                        Math.max(1L, next.timeoutMillis),
                        TimeUnit.MILLISECONDS);
        return next;
    }

    private void promoteAndSendLocked(GatewayState state, String gatewayId) {
        PendingRequest next = promoteLocked(state, gatewayId);
        if (next == null) {
            return;
        }
        sender
                .send(next)
                .defaultIfEmpty(false)
                .subscribe(
                        sent -> {
                            if (!Boolean.TRUE.equals(sent)) {
                                onSendFailed(gatewayId, next.requestToken, "session rejected request");
                            }
                        },
                        error -> onSendFailed(gatewayId, next.requestToken, error.getMessage()));
    }

    private void onTimeout(String gatewayId, String requestToken) {
        completeFailed(gatewayId, requestToken, CompletionType.TIMEOUT, "modbus response timeout");
    }

    private void onSendFailed(String gatewayId, String requestToken, String reason) {
        completeFailed(gatewayId, requestToken, CompletionType.SEND_FAILED, reason);
    }

    private void completeFailed(String gatewayId,
                                String requestToken,
                                CompletionType type,
                                String reason) {
        GatewayState state = states.get(gatewayId);
        if (state == null) {
            return;
        }
        PendingRequest failed;
        synchronized (state) {
            failed = state.inFlight;
            if (failed == null || !failed.requestToken.equals(requestToken)) {
                return;
            }
            clearInFlightLocked(state);
            promoteAndSendLocked(state, gatewayId);
            removeIfEmptyLocked(gatewayId, state);
        }
        notifyCompletion(failed, type, reason);
    }

    private void clearInFlightLocked(GatewayState state) {
        PendingRequest request = state.inFlight;
        state.inFlight = null;
        if (request != null) {
            request.cancelTimeout();
        }
    }

    private void removeIfEmptyLocked(String gatewayId, GatewayState state) {
        if (state.inFlight == null && state.waiting.isEmpty()) {
            states.remove(gatewayId, state);
        }
    }

    private boolean canSend(PendingRequest request) {
        return !request.cancelled
                && (request.source != Source.POLLING || leaseValidator.test(request));
    }

    public boolean isSendAllowed(PendingRequest request) {
        return request != null && canSend(request);
    }

    private boolean defaultLeaseValid(PendingRequest request) {
        if (request.leaseOwnerToken == null || request.leaseOwnerToken.isEmpty()) {
            return false;
        }
        Long invalidUntil = invalidLeaseOwners.get(
                leaseKey(request.gatewayId, request.leaseOwnerToken));
        if (invalidUntil != null) {
            if (invalidUntil > System.currentTimeMillis()) {
                return false;
            }
            invalidLeaseOwners.remove(
                    leaseKey(request.gatewayId, request.leaseOwnerToken),
                    invalidUntil);
        }
        Object expiresValue = request.attributes.get(ModbusRtuCodec.HEADER_POLL_LEASE_EXPIRES_AT);
        if (expiresValue == null) {
            return true;
        }
        try {
            return Long.parseLong(String.valueOf(expiresValue)) > System.currentTimeMillis();
        } catch (NumberFormatException ignore) {
            return false;
        }
    }

    private static boolean matches(PendingRequest request,
                                   int slaveId,
                                   ModbusFunctionCode function) {
        return request != null
                && request.request.getSlaveId() == slaveId
                && request.request.getFunction() == function;
    }

    private static boolean equals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static String leaseKey(String gatewayId, String ownerToken) {
        return gatewayId + '\u0000' + ownerToken;
    }

    private static void notifyCompletion(PendingRequest request,
                                         CompletionType type,
                                         String message) {
        Consumer<Completion> listener = request.completionListener;
        request.completionListener = null;
        if (listener != null) {
            listener.accept(new Completion(type, request, message));
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
        private final String replyMessageId;
        private final String requestToken;
        private final String logicalRequestId;
        private final String planId;
        private final String cycleId;
        private final String leaseOwnerToken;
        private final Source source;
        private final ModbusRequest request;
        private final long timeoutMillis;
        private final String propertyId;
        private final List<String> batchPropertyIds;
        private final int batchStartAddress;
        private final Map<String, Object> attributes;
        private volatile boolean cancelled;
        private volatile long sentAt = -1L;
        private volatile Disposable timeoutTask;
        private volatile Consumer<Completion> completionListener;

        private PendingRequest(Builder builder) {
            this.gatewayId = builder.gatewayId;
            this.deviceId = builder.deviceId;
            this.messageId = builder.messageId;
            this.replyMessageId = builder.replyMessageId;
            this.requestToken = builder.requestToken == null
                    ? UUID.randomUUID().toString()
                    : builder.requestToken;
            this.logicalRequestId = builder.logicalRequestId;
            this.planId = builder.planId;
            this.cycleId = builder.cycleId;
            this.leaseOwnerToken = builder.leaseOwnerToken;
            this.source = builder.source == null ? Source.MANUAL : builder.source;
            this.request = builder.request;
            this.timeoutMillis = builder.timeoutMillis;
            this.propertyId = builder.propertyId;
            this.batchPropertyIds = builder.batchPropertyIds == null
                    ? null
                    : Collections.unmodifiableList(new ArrayList<>(builder.batchPropertyIds));
            this.batchStartAddress = builder.batchStartAddress;
            this.attributes = builder.attributes == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new HashMap<>(builder.attributes));
            this.completionListener = builder.completionListener;
        }

        public static Builder builder() {
            return new Builder();
        }

        void cancelTimeout() {
            Disposable task = timeoutTask;
            timeoutTask = null;
            if (task != null) {
                task.dispose();
            }
        }

        public String getGatewayId() {
            return gatewayId;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public String getMessageId() {
            return messageId;
        }

        public String getReplyMessageId() {
            return replyMessageId;
        }

        public String getRequestToken() {
            return requestToken;
        }

        public String getLogicalRequestId() {
            return logicalRequestId;
        }

        public String getPlanId() {
            return planId;
        }

        public String getCycleId() {
            return cycleId;
        }

        public String getLeaseOwnerToken() {
            return leaseOwnerToken;
        }

        public Source getSource() {
            return source;
        }

        public ModbusRequest getRequest() {
            return request;
        }

        public long getTimeoutMillis() {
            return timeoutMillis;
        }

        public String getPropertyId() {
            return propertyId;
        }

        public List<String> getBatchPropertyIds() {
            return batchPropertyIds;
        }

        public int getBatchStartAddress() {
            return batchStartAddress;
        }

        public Map<String, Object> getAttributes() {
            return attributes;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public long getSentAt() {
            return sentAt;
        }

        public static final class Builder {
            private String gatewayId;
            private String deviceId;
            private String messageId;
            private String replyMessageId;
            private String requestToken;
            private String logicalRequestId;
            private String planId;
            private String cycleId;
            private String leaseOwnerToken;
            private Source source = Source.MANUAL;
            private ModbusRequest request;
            private long timeoutMillis = ModbusRtuCodec.DEFAULT_RESPONSE_TIMEOUT_MS;
            private String propertyId;
            private List<String> batchPropertyIds;
            private int batchStartAddress;
            private Map<String, Object> attributes;
            private Consumer<Completion> completionListener;

            public Builder gatewayId(String gatewayId) {
                this.gatewayId = gatewayId;
                return this;
            }

            public Builder deviceId(String deviceId) {
                this.deviceId = deviceId;
                return this;
            }

            public Builder messageId(String messageId) {
                this.messageId = messageId;
                return this;
            }

            public Builder replyMessageId(String replyMessageId) {
                this.replyMessageId = replyMessageId;
                return this;
            }

            public Builder requestToken(String requestToken) {
                this.requestToken = requestToken;
                return this;
            }

            public Builder logicalRequestId(String logicalRequestId) {
                this.logicalRequestId = logicalRequestId;
                return this;
            }

            public Builder planId(String planId) {
                this.planId = planId;
                return this;
            }

            public Builder cycleId(String cycleId) {
                this.cycleId = cycleId;
                return this;
            }

            public Builder leaseOwnerToken(String leaseOwnerToken) {
                this.leaseOwnerToken = leaseOwnerToken;
                return this;
            }

            public Builder source(Source source) {
                this.source = source;
                return this;
            }

            public Builder request(ModbusRequest request) {
                this.request = request;
                return this;
            }

            public Builder timeoutMillis(long timeoutMillis) {
                this.timeoutMillis = timeoutMillis;
                return this;
            }

            public Builder propertyId(String propertyId) {
                this.propertyId = propertyId;
                return this;
            }

            public Builder batchPropertyIds(List<String> batchPropertyIds) {
                this.batchPropertyIds = batchPropertyIds;
                return this;
            }

            public Builder batchStartAddress(int batchStartAddress) {
                this.batchStartAddress = batchStartAddress;
                return this;
            }

            public Builder attributes(Map<String, Object> attributes) {
                this.attributes = attributes;
                return this;
            }

            public Builder onCompletion(Consumer<Completion> completionListener) {
                this.completionListener = completionListener;
                return this;
            }

            public PendingRequest build() {
                if (gatewayId == null || request == null) {
                    throw new IllegalArgumentException("gatewayId and request must not be null");
                }
                return new PendingRequest(this);
            }
        }
    }
}
