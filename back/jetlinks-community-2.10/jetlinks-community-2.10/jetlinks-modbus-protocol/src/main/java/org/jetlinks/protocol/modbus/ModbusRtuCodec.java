package org.jetlinks.protocol.modbus;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.core.Value;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.message.DeviceMessage;
import org.jetlinks.core.message.HeaderKey;
import org.jetlinks.core.message.Headers;
import org.jetlinks.core.message.codec.DefaultTransport;
import org.jetlinks.core.message.codec.DeviceMessageCodec;
import org.jetlinks.core.message.codec.EncodedMessage;
import org.jetlinks.core.message.codec.FromDeviceMessageContext;
import org.jetlinks.core.message.codec.MessageDecodeContext;
import org.jetlinks.core.message.codec.MessageEncodeContext;
import org.jetlinks.core.message.codec.Transport;
import org.jetlinks.core.message.function.FunctionInvokeMessage;
import org.jetlinks.core.message.function.FunctionInvokeMessageReply;
import org.jetlinks.core.message.property.ReadPropertyMessage;
import org.jetlinks.core.message.property.ReadPropertyMessageReply;
import org.jetlinks.core.message.property.WritePropertyMessage;
import org.jetlinks.core.message.property.WritePropertyMessageReply;
import org.jetlinks.core.server.session.DeviceSession;
import org.jetlinks.protocol.modbus.frame.ModbusExceptionCode;
import org.jetlinks.protocol.modbus.frame.ModbusFunctionCode;
import org.jetlinks.protocol.modbus.frame.ModbusRequest;
import org.jetlinks.protocol.modbus.frame.ModbusResponse;
import org.jetlinks.protocol.modbus.mapping.RegisterCodec;
import org.jetlinks.protocol.modbus.mapping.RegisterDataType;
import org.jetlinks.protocol.modbus.mapping.RegisterMapping;
import org.jetlinks.protocol.modbus.mapping.RegisterMappingTable;
import org.jetlinks.protocol.modbus.pending.PendingRequestQueue;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Modbus RTU codec for TCP transport. Converts JetLinks device messages into
 * Modbus request frames and back, using per-product register-maps and
 * per-device slave ids. Request/response correlation is handled by a
 * single-in-flight {@link PendingRequestQueue} keyed by the TCP session id,
 * matching the physical half-duplex nature of a Modbus bus.
 */
@Slf4j
public class ModbusRtuCodec implements DeviceMessageCodec {

    public static final String CONFIG_SLAVE_ID = "slaveId";
    public static final String CONFIG_REGISTER_MAP = "registerMap";
    public static final String CONFIG_RESPONSE_TIMEOUT_MS = "responseTimeoutMs";
    public static final String CONFIG_DISPATCH_INTERVAL_MS = "dispatchIntervalMs";
    public static final String CONFIG_PARENT_ID = "parentId";

    public static final long DEFAULT_RESPONSE_TIMEOUT_MS = 3000L;
    public static final long DEFAULT_DISPATCH_INTERVAL_MS = 50L;
    private static final HeaderKey<Boolean> IGNORE_CACHE = HeaderKey.of("ignoreCache", false, Boolean.class);
    private static final String META_HEADERS = "headers";
    private static final String META_REPLY_MESSAGE_ID = "replyMessageId";
    private static final String META_REGISTER_TABLE = "registerTable";
    private static final String META_SPLIT_INDEX = "splitIndex";
    private static final String META_SPLIT_TOTAL = "splitTotal";

    /**
     * Maximum number of unused register/bit slots allowed between two
     * consecutive properties in a batch read. If any consecutive pair of
     * properties (sorted by address) has a gap larger than this value the
     * batch is split: only the first property is encoded in this turn and
     * the remaining ones must come from separate ReadPropertyMessages.
     * Keeping this small avoids wasting bus time reading empty registers.
     */
    static final int MAX_BATCH_GAP = 20;

    private final PendingRequestQueue pendingQueue;

    private final Map<String, RegisterMappingTable> mappingCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, SplitAggregate> splitAggregates = new java.util.concurrent.ConcurrentHashMap<>();

    public ModbusRtuCodec() {
        this(new PendingRequestQueue());
    }

    public ModbusRtuCodec(PendingRequestQueue pendingQueue) {
        this.pendingQueue = pendingQueue;
    }

    public PendingRequestQueue getPendingQueue() {
        return pendingQueue;
    }

    @Override
    public Transport getSupportTransport() {
        return DefaultTransport.TCP;
    }

    @NonNull
    @Override
    public Publisher<? extends EncodedMessage> encode(@NonNull MessageEncodeContext context) {
        if (!(context.getMessage() instanceof DeviceMessage)) {
            return Mono.empty();
        }
        DeviceMessage message = (DeviceMessage) context.getMessage();
        DeviceOperator device = context.getDevice();
        if (device == null) {
            log.warn("Cannot encode Modbus message without device context: {}", message);
            return Mono.empty();
        }
        return Mono
                .zip(
                        resolveSlaveId(device),
                        resolveRegisterTable(device),
                        resolveTimeout(device),
                        resolveDispatchInterval(device),
                        resolveSessionId(context)
                )
                .flatMapMany(tuple -> buildRequests(message, tuple.getT1(), tuple.getT2())
                        .concatMap(prepared -> {
                            String effectiveId = prepared.messageIdOverride != null
                                    ? prepared.messageIdOverride
                                    : message.getMessageId();
                            return trackAndEncode(tuple.getT5(), message.getDeviceId(),
                                    effectiveId, prepared, tuple.getT2(), tuple.getT3(), tuple.getT4());
                        }));
    }

    @NonNull
    @Override
    public Publisher<? extends DeviceMessage> decode(@NonNull MessageDecodeContext context) {
        if (!(context instanceof FromDeviceMessageContext)) {
            return Mono.empty();
        }
        FromDeviceMessageContext fromCtx = (FromDeviceMessageContext) context;
        DeviceSession session = fromCtx.getSession();
        if (session == null) {
            return Mono.empty();
        }
        ByteBuf payload = context.getMessage().getPayload();
        byte[] bytes = new byte[payload.readableBytes()];
        payload.getBytes(payload.readerIndex(), bytes);
        long nowMillis = System.currentTimeMillis();

        final ModbusResponse response;
        try {
            response = ModbusResponse.parse(bytes);
        } catch (Exception e) {
            log.warn("Invalid Modbus response on session {}: {}", session.getId(), e.getMessage());
            return Mono.empty();
        }

        AtomicBoolean matched = new AtomicBoolean(false);
        return resolvePending(session, response)
                .flatMapMany(pending -> {
                    matched.set(true);
                    String gatewayId = pending.getGatewayId();
                    pendingQueue.ack(gatewayId);
                    if (pending.isExpired(nowMillis)) {
                        log.warn("Drop late Modbus response after timeout: gateway={} slave={} fc={} device={}",
                                gatewayId, response.getSlaveId(), response.getFunction(), pending.getDeviceId());
                        releaseExpired(pending);
                        return drainNext(gatewayId, fromCtx.getSession());
                    }

                    return resolveRegisterTable(fromCtx.getDevice())
                            .defaultIfEmpty(RegisterMappingTable.empty())
                            .flatMapMany(table -> Flux.fromIterable(buildRepliesSafely(pending, response, table)))
                            .concatWith(Flux.defer(() -> drainNext(gatewayId, fromCtx.getSession())));
                })
                .switchIfEmpty(Flux.defer(() -> matched.get()
                        ? Flux.empty()
                        : drainExpiredForSession(session, nowMillis)));
    }

    private Flux<DeviceMessage> drainExpiredForSession(DeviceSession session, long nowMillis) {
        List<PendingRequestQueue.PendingRequest> expired = sweepExpiredRequests(nowMillis);
        return Flux
                .fromIterable(expired)
                .filter(exp -> isSessionForGateway(session, exp.getGatewayId()))
                .concatMap(exp -> {
                    PendingRequestQueue.PendingRequest next = pendingQueue.promote(exp.getGatewayId());
                    return next == null ? Mono.empty() : sendNext(session, next);
                })
                .thenMany(Flux.empty());
    }

    private Flux<DeviceMessage> drainNext(String sessionId, DeviceSession session) {
        PendingRequestQueue.PendingRequest next = pendingQueue.promote(sessionId);
        if (next == null) {
            return Flux.empty();
        }
        return sendNext(session, next)
                .thenMany(Flux.<DeviceMessage>empty());
    }

    private Mono<Void> sendNext(DeviceSession session, PendingRequestQueue.PendingRequest next) {
        if (session == null || next == null) {
            return Mono.empty();
        }
        long delayMillis = pendingQueue.dispatchDelay(
                next.getGatewayId(),
                next.getDispatchIntervalMillis(),
                System.currentTimeMillis());
        Mono<Long> delay = delayMillis <= 0
                ? Mono.just(0L)
                : Mono.delay(Duration.ofMillis(delayMillis));
        return delay
                .then(Mono.fromRunnable(() -> pendingQueue.markSent(next, System.currentTimeMillis())))
                .then(session.send(EncodedMessage.simple(Unpooled.wrappedBuffer(next.getRequest().toAdu()))))
                .doOnError(error -> {
                    pendingQueue.ack(next.getGatewayId());
                    releasePendingMeta(next);
                    splitAggregates.remove(aggregateKey(next, next.getLogicalMessageId()));
                    releaseWaitingFromSameLogicalRequest(next);
                    log.warn("Send Modbus request failed: gateway={} slave={} fc={} device={}, {}",
                            next.getGatewayId(), next.getRequest().getSlaveId(),
                            next.getRequest().getFunction(), next.getDeviceId(), error.getMessage());
                })
                .then();
    }

    private boolean isSessionForGateway(DeviceSession session, String gatewayId) {
        return session != null
                && gatewayId != null
                && (gatewayId.equals(session.getDeviceId()) || gatewayId.equals(session.getId()));
    }

    private Mono<PendingRequestQueue.PendingRequest> resolvePending(DeviceSession session, ModbusResponse response) {
        ModbusFunctionCode responseFc = response.getFunction();
        return resolveGatewayIdFromSession(session)
                .flatMap(resolution -> {
                    if (resolution.known) {
                        PendingRequestQueue.PendingRequest pending =
                                pendingQueue.findInFlight(resolution.gatewayId, response);
                        if (pending == null) {
                            log.debug("Dropping unmatched Modbus frame on gateway {}: slave={} fc={}",
                                    resolution.gatewayId, response.getSlaveId(), responseFc);
                            return Mono.empty();
                        }
                        return Mono.just(pending);
                    }
                    PendingRequestQueue.PendingRequest pending =
                            resolvePendingForUnknownSession(response);
                    return pending == null ? Mono.empty() : Mono.just(pending);
                });
    }

    private Mono<GatewayResolution> resolveGatewayIdFromSession(DeviceSession session) {
        if (session == null) {
            return Mono.just(GatewayResolution.unknown());
        }
        DeviceOperator operator = session.getOperator();
        if (operator != null) {
            return operator
                    .getParentDevice()
                    .map(parent -> GatewayResolution.known(parent.getDeviceId()))
                    .defaultIfEmpty(GatewayResolution.known(operator.getDeviceId()));
        }
        String deviceId = session.getDeviceId();
        if (deviceId != null && !deviceId.isEmpty() && !"unknown".equals(deviceId)) {
            return Mono.just(GatewayResolution.known(deviceId));
        }
        return Mono.just(GatewayResolution.unknown());
    }

    private PendingRequestQueue.PendingRequest resolvePendingForUnknownSession(int slaveId, ModbusFunctionCode fc) {
        List<PendingRequestQueue.PendingRequest> candidates =
                pendingQueue.findInFlightCandidates(slaveId, fc);
        if (candidates.size() == 1) {
            PendingRequestQueue.PendingRequest pending = candidates.get(0);
            log.debug("Resolve Modbus response by unique pending candidate: gateway={} slave={} fc={}",
                    pending.getGatewayId(), slaveId, fc);
            return pending;
        }
        if (candidates.size() > 1) {
            log.warn("Drop ambiguous Modbus response: slave={} fc={} candidates={}",
                    slaveId, fc, candidates.size());
        }
        return null;
    }

    private PendingRequestQueue.PendingRequest resolvePendingForUnknownSession(ModbusResponse response) {
        List<PendingRequestQueue.PendingRequest> candidates =
                pendingQueue.findInFlightCandidates(response);
        if (candidates.size() == 1) {
            PendingRequestQueue.PendingRequest pending = candidates.get(0);
            log.debug("Resolve Modbus response by unique strict pending candidate: gateway={} slave={} fc={}",
                    pending.getGatewayId(), response.getSlaveId(), response.getFunction());
            return pending;
        }
        if (candidates.size() > 1) {
            log.warn("Drop ambiguous Modbus response: slave={} fc={} candidates={}",
                    response.getSlaveId(), response.getFunction(), candidates.size());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<DeviceMessage> buildRepliesSafely(PendingRequestQueue.PendingRequest pending,
                                                    ModbusResponse response,
                                                    RegisterMappingTable table) {
        try {
            return buildReplies(pending, response, table);
        } catch (Exception e) {
            log.warn("Decode Modbus response failed: gateway={} slave={} fc={} device={}, {}",
                    pending.getGatewayId(), response.getSlaveId(), response.getFunction(),
                    pending.getDeviceId(), e.getMessage());
            return Collections.singletonList(buildDecodeErrorReply(pending, null, e));
        }
    }

    @SuppressWarnings("unchecked")
    private List<DeviceMessage> buildReplies(PendingRequestQueue.PendingRequest pending,
                                              ModbusResponse response,
                                              RegisterMappingTable table) {
        if (response.isException()) {
            return Collections.singletonList(buildErrorReply(pending, response));
        }

        Map<String, Object> meta = releasePendingMeta(pending);
        table = tableFromMeta(meta, table);

        if (pending.getRequest().getFunction().isWrite()) {
            String propertyId = meta != null ? (String) meta.get("propertyId") : null;
            if (isSplit(meta)) {
                return aggregateWriteSplit(pending, propertyId, meta);
            }
            return Collections.singletonList(buildWriteReply(pending, propertyId, meta));
        }

        // Batch read path
        List<String> batchIds = meta != null ? (List<String>) meta.get("batchPropertyIds") : null;
        if (batchIds != null) {
            int startAddress = meta.containsKey("batchStartAddress")
                    ? ((Number) meta.get("batchStartAddress")).intValue() : 0;
            return buildBatchReadReplies(pending, response, table, batchIds, startAddress, meta);
        }

        // Single property path
        String propertyId = meta != null ? (String) meta.get("propertyId") : null;
        RegisterMapping mapping = propertyId == null ? null : table.find(propertyId);
        if (mapping == null) {
            log.warn("No register mapping for property '{}', cannot decode read response", propertyId);
            return Collections.emptyList();
        }
        final Object value;
        try {
            value = decodeReadPayload(mapping, response.getPayload(), pending.getRequest().getQuantity());
        } catch (Exception e) {
            log.warn("Decode Modbus property '{}' failed: gateway={} slave={} fc={} device={}, {}",
                    propertyId, pending.getGatewayId(), response.getSlaveId(), response.getFunction(),
                    pending.getDeviceId(), e.getMessage());
            if (isSplit(meta)) {
                splitAggregates.remove(aggregateKey(pending, replyMessageId(pending, meta)));
                releaseWaitingFromSameLogicalRequest(pending);
            }
            return Collections.singletonList(buildDecodeErrorReply(pending, meta, e));
        }
        if (isSplit(meta)) {
            return aggregateReadSplit(pending, propertyId, value, meta);
        }
        return Collections.singletonList(buildReadReply(pending, propertyId, value, meta));
    }

    private List<DeviceMessage> buildBatchReadReplies(
            PendingRequestQueue.PendingRequest pending,
            ModbusResponse response,
            RegisterMappingTable table,
            List<String> propertyIds,
            int startAddress,
            Map<String, Object> meta) {
        byte[] payload = response.getPayload();
        boolean bitOriented = pending.getRequest().getFunction().isBitOriented();
        Map<String, Object> properties = new LinkedHashMap<>();

        for (String pid : propertyIds) {
            RegisterMapping mapping = table.find(pid);
            if (mapping == null) {
                log.warn("Batch read: no register mapping for property '{}', skipping", pid);
                continue;
            }
            try {
                Object value;
                if (bitOriented) {
                    int bitOffset = mapping.getAddress() - startAddress;
                    int byteIdx   = bitOffset / 8;
                    int bitInByte = bitOffset % 8;
                    if (byteIdx >= payload.length) {
                        log.warn("Batch read: payload too short for '{}' (bitOffset={})", pid, bitOffset);
                        continue;
                    }
                    value = ((payload[byteIdx] >> bitInByte) & 1) != 0;
                } else {
                    int byteOffset = (mapping.getAddress() - startAddress) * 2;
                    int need = mapping.getDataType().byteLength();
                    if (byteOffset + need > payload.length) {
                        log.warn("Batch read: payload too short for '{}' (offset={} need={} available={})",
                                pid, byteOffset, need, payload.length);
                        continue;
                    }
                    byte[] slice = new byte[need];
                    System.arraycopy(payload, byteOffset, slice, 0, need);
                    value = RegisterCodec.decode(mapping, slice);
                }
                properties.put(pid, value);
            } catch (Exception e) {
                log.warn("Batch read: failed to decode property '{}': {}", pid, e.getMessage());
            }
        }

        ReadPropertyMessageReply reply = new ReadPropertyMessageReply();
        reply.setDeviceId(pending.getDeviceId());
        reply.setMessageId(replyMessageId(pending, meta));
        reply.setTimestamp(System.currentTimeMillis());
        reply.setProperties(properties);
        reply.setSuccess(!properties.isEmpty());
        applyHeaders(reply, meta);
        return Collections.singletonList(reply);
    }

    private List<DeviceMessage> aggregateReadSplit(PendingRequestQueue.PendingRequest pending,
                                                   String propertyId,
                                                   Object value,
                                                   Map<String, Object> meta) {
        SplitAggregate aggregate = splitAggregate(pending, meta);
        synchronized (aggregate) {
            aggregate.properties.put(propertyId, value);
            aggregate.completed++;
            if (aggregate.completed < aggregate.total) {
                return Collections.emptyList();
            }
            splitAggregates.remove(aggregateKey(pending, aggregate.messageId));

            ReadPropertyMessageReply reply = new ReadPropertyMessageReply();
            reply.setDeviceId(aggregate.deviceId);
            reply.setMessageId(aggregate.messageId);
            reply.setTimestamp(System.currentTimeMillis());
            reply.setProperties(new LinkedHashMap<>(aggregate.properties));
            reply.setSuccess(!aggregate.properties.isEmpty());
            applyHeaders(reply, meta);
            return Collections.singletonList(reply);
        }
    }

    private List<DeviceMessage> aggregateWriteSplit(PendingRequestQueue.PendingRequest pending,
                                                    String propertyId,
                                                    Map<String, Object> meta) {
        SplitAggregate aggregate = splitAggregate(pending, meta);
        synchronized (aggregate) {
            if (propertyId != null) {
                aggregate.properties.put(propertyId, true);
            }
            aggregate.completed++;
            if (aggregate.completed < aggregate.total) {
                return Collections.emptyList();
            }
            splitAggregates.remove(aggregateKey(pending, aggregate.messageId));

            WritePropertyMessageReply reply = new WritePropertyMessageReply();
            reply.setDeviceId(aggregate.deviceId);
            reply.setMessageId(aggregate.messageId);
            reply.setTimestamp(System.currentTimeMillis());
            reply.setSuccess(true);
            reply.setProperties(new LinkedHashMap<>(aggregate.properties));
            applyHeaders(reply, meta);
            return Collections.singletonList(reply);
        }
    }

    private SplitAggregate splitAggregate(PendingRequestQueue.PendingRequest pending,
                                          Map<String, Object> meta) {
        String messageId = replyMessageId(pending, meta);
        String key = aggregateKey(pending, messageId);
        int total = splitTotal(meta);
        return splitAggregates.computeIfAbsent(key,
                ignore -> new SplitAggregate(total, pending.getDeviceId(), messageId));
    }

    private String aggregateKey(PendingRequestQueue.PendingRequest pending, String messageId) {
        return pending.getGatewayId() + ":" + messageId;
    }

    private boolean isSplit(Map<String, Object> meta) {
        return splitTotal(meta) > 1;
    }

    private int splitTotal(Map<String, Object> meta) {
        Object total = meta == null ? null : meta.get(META_SPLIT_TOTAL);
        return total instanceof Number ? ((Number) total).intValue() : 0;
    }

    private Object decodeReadPayload(RegisterMapping mapping, byte[] payload, int quantity) {
        if (mapping.getDataType() == RegisterDataType.BIT) {
            boolean on = payload.length > 0 && (payload[0] & 0x01) != 0;
            return on;
        }
        int need = mapping.getDataType().byteLength();
        if (payload.length < need) {
            throw new IllegalArgumentException("payload shorter than expected: "
                    + payload.length + " < " + need);
        }
        byte[] slice = new byte[need];
        System.arraycopy(payload, 0, slice, 0, need);
        return RegisterCodec.decode(mapping, slice);
    }

    private DeviceMessage buildReadReply(PendingRequestQueue.PendingRequest pending,
                                          String propertyId,
                                          Object value,
                                          Map<String, Object> meta) {
        ReadPropertyMessageReply reply = new ReadPropertyMessageReply();
        reply.setDeviceId(pending.getDeviceId());
        reply.setMessageId(replyMessageId(pending, meta));
        reply.setTimestamp(System.currentTimeMillis());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(propertyId, value);
        reply.setProperties(properties);
        reply.setSuccess(true);
        applyHeaders(reply, meta);
        return reply;
    }

    private DeviceMessage buildWriteReply(PendingRequestQueue.PendingRequest pending,
                                          String propertyId,
                                          Map<String, Object> meta) {
        if (pending.getRequest().getFunction() == ModbusFunctionCode.WRITE_SINGLE_COIL
                || pending.getRequest().getFunction() == ModbusFunctionCode.WRITE_SINGLE_REGISTER
                || pending.getRequest().getFunction() == ModbusFunctionCode.WRITE_MULTIPLE_COILS
                || pending.getRequest().getFunction() == ModbusFunctionCode.WRITE_MULTIPLE_REGISTERS) {
            WritePropertyMessageReply reply = new WritePropertyMessageReply();
            reply.setDeviceId(pending.getDeviceId());
            reply.setMessageId(replyMessageId(pending, meta));
            reply.setTimestamp(System.currentTimeMillis());
            reply.setSuccess(true);
            if (propertyId != null) {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put(propertyId, true);
                reply.setProperties(properties);
            }
            applyHeaders(reply, meta);
            return reply;
        }
        FunctionInvokeMessageReply fnReply = new FunctionInvokeMessageReply();
        fnReply.setDeviceId(pending.getDeviceId());
        fnReply.setMessageId(replyMessageId(pending, meta));
        fnReply.setTimestamp(System.currentTimeMillis());
        fnReply.setSuccess(true);
        applyHeaders(fnReply, meta);
        return fnReply;
    }

    private DeviceMessage buildErrorReply(PendingRequestQueue.PendingRequest pending, ModbusResponse response) {
        ModbusExceptionCode ec = response.getExceptionCode();
        String code = ec == null ? "modbus-exception" : "modbus-" + ec.name().toLowerCase();
        String message = "Modbus exception: " + (ec == null ? "unknown" : ec.name());
        Map<String, Object> meta = releasePendingMeta(pending);
        if (isSplit(meta)) {
            splitAggregates.remove(aggregateKey(pending, replyMessageId(pending, meta)));
            releaseWaitingFromSameLogicalRequest(pending);
        }
        if (pending.getRequest().getFunction().isWrite()) {
            WritePropertyMessageReply reply = new WritePropertyMessageReply();
            reply.setDeviceId(pending.getDeviceId());
            reply.setMessageId(replyMessageId(pending, meta));
            reply.setTimestamp(System.currentTimeMillis());
            reply.setSuccess(false);
            reply.setCode(code);
            reply.setMessage(message);
            applyHeaders(reply, meta);
            return reply;
        }
        ReadPropertyMessageReply reply = new ReadPropertyMessageReply();
        reply.setDeviceId(pending.getDeviceId());
        reply.setMessageId(replyMessageId(pending, meta));
        reply.setTimestamp(System.currentTimeMillis());
        reply.setSuccess(false);
        reply.setCode(code);
        reply.setMessage(message);
        applyHeaders(reply, meta);
        return reply;
    }

    private DeviceMessage buildDecodeErrorReply(PendingRequestQueue.PendingRequest pending,
                                                Map<String, Object> meta,
                                                Exception error) {
        ReadPropertyMessageReply reply = new ReadPropertyMessageReply();
        reply.setDeviceId(pending.getDeviceId());
        reply.setMessageId(replyMessageId(pending, meta));
        reply.setTimestamp(System.currentTimeMillis());
        reply.setSuccess(false);
        reply.setCode("modbus-decode-error");
        reply.setMessage(error.getMessage());
        applyHeaders(reply, meta);
        return reply;
    }

    private String replyMessageId(PendingRequestQueue.PendingRequest pending, Map<String, Object> meta) {
        Object messageId = meta == null ? null : meta.get(META_REPLY_MESSAGE_ID);
        return messageId == null ? pending.getMessageId() : String.valueOf(messageId);
    }

    private RegisterMappingTable tableFromMeta(Map<String, Object> meta, RegisterMappingTable fallback) {
        Object table = meta == null ? null : meta.get(META_REGISTER_TABLE);
        if (table instanceof RegisterMappingTable) {
            return (RegisterMappingTable) table;
        }
        return fallback == null ? RegisterMappingTable.empty() : fallback;
    }

    @SuppressWarnings("unchecked")
    private void applyHeaders(DeviceMessage reply, Map<String, Object> meta) {
        if (meta == null) {
            return;
        }
        Map<String, Object> headers = (Map<String, Object>) meta.get(META_HEADERS);
        if (headers != null) {
            headers.forEach(reply::addHeader);
        }
    }

    private final Map<String, Map<String, Object>> PENDING_META = new java.util.concurrent.ConcurrentHashMap<>();

    private Mono<EncodedMessage> trackAndEncode(String sessionId,
                                                String deviceId,
                                                String effectiveMessageId,
                                                PendingEncoded prepared,
                                                RegisterMappingTable table,
                                                long timeoutMs,
                                                long dispatchIntervalMs) {
        PendingRequestQueue.PendingRequest expired =
                pendingQueue.sweepExpired(sessionId, System.currentTimeMillis());
        if (expired != null) {
            releaseExpired(expired);
        }

        PendingRequestQueue.PendingRequest pending = new PendingRequestQueue.PendingRequest(
                sessionId,
                deviceId,
                effectiveMessageId,
                prepared.replyMessageId == null ? effectiveMessageId : prepared.replyMessageId,
                prepared.request,
                timeoutMs,
                dispatchIntervalMs
        );
        if (effectiveMessageId != null) {
            if (prepared.batchPropertyIds != null) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("batchPropertyIds", prepared.batchPropertyIds);
                meta.put("batchStartAddress", prepared.batchStartAddress);
                meta.put(META_REGISTER_TABLE, table);
                if (prepared.replyMessageId != null) {
                    meta.put(META_REPLY_MESSAGE_ID, prepared.replyMessageId);
                }
                if (prepared.splitTotal > 1) {
                    meta.put(META_SPLIT_INDEX, prepared.splitIndex);
                    meta.put(META_SPLIT_TOTAL, prepared.splitTotal);
                }
                if (!prepared.headers.isEmpty()) {
                    meta.put(META_HEADERS, prepared.headers);
                }
                PENDING_META.put(effectiveMessageId, meta);
            } else if (prepared.propertyId != null) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("propertyId", prepared.propertyId);
                meta.put(META_REGISTER_TABLE, table);
                if (prepared.replyMessageId != null) {
                    meta.put(META_REPLY_MESSAGE_ID, prepared.replyMessageId);
                }
                if (prepared.splitTotal > 1) {
                    meta.put(META_SPLIT_INDEX, prepared.splitIndex);
                    meta.put(META_SPLIT_TOTAL, prepared.splitTotal);
                }
                if (!prepared.headers.isEmpty()) {
                    meta.put(META_HEADERS, prepared.headers);
                }
                PENDING_META.put(effectiveMessageId, meta);
            }
        }
        if (!pendingQueue.offer(sessionId, pending)) {
            releasePendingMeta(pending);
            log.warn("Drop Modbus request because gateway queue is full: gateway={} slave={} fc={} device={}",
                    sessionId, prepared.request.getSlaveId(), prepared.request.getFunction(), deviceId);
            return emptyEncoded();
        }
        PendingRequestQueue.PendingRequest promoted = pendingQueue.promote(sessionId);
        if (promoted == null) {
            // something already in-flight, hold off: emit an empty frame to avoid collision
            return emptyEncoded();
        }
        return encodePromoted(promoted);
    }

    private Mono<EncodedMessage> encodePromoted(PendingRequestQueue.PendingRequest pending) {
        long delayMillis = pendingQueue.dispatchDelay(
                pending.getGatewayId(),
                pending.getDispatchIntervalMillis(),
                System.currentTimeMillis());
        Mono<Long> delay = delayMillis <= 0
                ? Mono.just(0L)
                : Mono.delay(Duration.ofMillis(delayMillis));
        return delay
                .then(Mono.fromSupplier(() -> {
                    pendingQueue.markSent(pending, System.currentTimeMillis());
                    return EncodedMessage.simple(Unpooled.wrappedBuffer(pending.getRequest().toAdu()));
                }));
    }

    private Mono<EncodedMessage> emptyEncoded() {
        return Mono.empty();
    }

    private List<PendingRequestQueue.PendingRequest> sweepExpiredRequests(long nowMillis) {
        List<PendingRequestQueue.PendingRequest> expired = pendingQueue.sweepAllExpired(nowMillis);
        for (PendingRequestQueue.PendingRequest request : expired) {
            releaseExpired(request);
        }
        return expired;
    }

    private void releaseExpired(PendingRequestQueue.PendingRequest request) {
        logTimeout(request);
        releasePendingMeta(request);
        splitAggregates.remove(aggregateKey(request, request.getLogicalMessageId()));
        releaseWaitingFromSameLogicalRequest(request);
    }

    private void releaseWaitingFromSameLogicalRequest(PendingRequestQueue.PendingRequest request) {
        List<PendingRequestQueue.PendingRequest> waiting =
                pendingQueue.removeWaitingByLogicalMessageId(request.getGatewayId(), request.getLogicalMessageId());
        for (PendingRequestQueue.PendingRequest orphan : waiting) {
            releasePendingMeta(orphan);
            log.debug("Drop pending Modbus frame from timed-out logical request: gateway={} device={} messageId={}",
                    orphan.getGatewayId(), orphan.getDeviceId(), orphan.getMessageId());
        }
    }

    private void logTimeout(PendingRequestQueue.PendingRequest request) {
        log.warn("Modbus request timed out ({}ms): gateway={} slave={} fc={} device={}",
                request.getTimeoutMillis(), request.getGatewayId(),
                request.getRequest().getSlaveId(), request.getRequest().getFunction(),
                request.getDeviceId());
    }

    private Map<String, Object> releasePendingMeta(PendingRequestQueue.PendingRequest pending) {
        return pending != null && pending.getMessageId() != null
                ? PENDING_META.remove(pending.getMessageId()) : null;
    }

    private Flux<PendingEncoded> buildRequests(DeviceMessage message,
                                               int slaveId,
                                               RegisterMappingTable table) {
        if (message instanceof ReadPropertyMessage) {
            ReadPropertyMessage read = (ReadPropertyMessage) message;
            List<String> props = read.getProperties();
            if (props == null || props.isEmpty()) {
                return Flux.empty();
            }
            if (props.size() > 1) {
                // Attempt to coalesce all properties with the same FC into a single
                // batch read frame. This avoids N round-trips on the half-duplex bus
                // and is the standard Modbus optimization for multi-register reads.
                RegisterMapping firstMapping = table.require(props.get(0));
                ModbusFunctionCode commonFc = firstMapping.requireReadFunctionCode();
                boolean allSameFc = true;
                for (String p : props) {
                    RegisterMapping m = table.find(p);
                    if (m == null || m.getReadFunctionCode() != commonFc) {
                        allSameFc = false;
                        break;
                    }
                }
                if (allSameFc) {
                    // Sort by address so gap analysis and decode offsets are consistent.
                    List<RegisterMapping> sorted = new ArrayList<>(props.size());
                    for (String p : props) sorted.add(table.require(p));
                    sorted.sort(java.util.Comparator.comparingInt(RegisterMapping::getAddress));

                    int startAddr = sorted.get(0).getAddress();
                    RegisterMapping lastM = sorted.get(sorted.size() - 1);
                    int endAddr = lastM.getAddress() + lastM.effectiveQuantity();
                    int qty = endAddr - startAddr;

                    // FC protocol limit: FC03/FC04 → 125 registers; FC01/FC02 → 2000 bits.
                    int maxQty = commonFc.isBitOriented() ? 2000 : 125;

                    // Max address gap between consecutive properties.
                    int worstGap = 0;
                    for (int i = 1; i < sorted.size(); i++) {
                        RegisterMapping prev = sorted.get(i - 1);
                        RegisterMapping curr = sorted.get(i);
                        int gap = curr.getAddress() - (prev.getAddress() + prev.effectiveQuantity());
                        worstGap = Math.max(worstGap, gap);
                    }

                    if (qty <= maxQty && worstGap <= MAX_BATCH_GAP) {
                        List<String> sortedIds = new ArrayList<>(sorted.size());
                        for (RegisterMapping m : sorted) sortedIds.add(m.getPropertyId());
                        ModbusRequest request = ModbusRequest.read(slaveId, commonFc, startAddr, qty);
                        return Flux.just(new PendingEncoded(request, null, sortedIds, startAddr, null, extractHeaders(message)));
                    }
                    if (qty > maxQty) {
                        log.warn("Batch read range {} exceeds FC 0x{} limit {}; splitting {} properties " +
                                 "into individual requests.",
                                qty, Integer.toHexString(commonFc.getCode()), maxQty, props.size());
                    } else {
                        log.warn("Batch read has address gap {} > threshold {}; splitting {} properties " +
                                 "into individual requests.",
                                worstGap, MAX_BATCH_GAP, props.size());
                    }
                } else {
                    log.warn("ReadPropertyMessage has {} properties with mixed function codes; " +
                             "splitting into individual requests.", props.size());
                }
                // Split: emit one request per property, each with a derived message-id so
                // the pending-queue can correlate each response independently.
                String baseId = message.getMessageId();
                List<PendingEncoded> split = new ArrayList<>(props.size());
                for (int i = 0; i < props.size(); i++) {
                    String pid = props.get(i);
                    RegisterMapping m = table.require(pid);
                    ModbusRequest req = ModbusRequest.read(slaveId, m.requireReadFunctionCode(),
                            m.getAddress(), m.effectiveQuantity());
                    String derivedId = baseId != null ? baseId + "_s" + i : null;
                    split.add(new PendingEncoded(req, pid, null, 0,
                            derivedId, baseId, extractHeaders(message), i, props.size()));
                }
                return Flux.fromIterable(split);
            }
            String propertyId = props.get(0);
            RegisterMapping mapping = table.require(propertyId);
            ModbusRequest request = ModbusRequest.read(
                    slaveId,
                    mapping.requireReadFunctionCode(),
                    mapping.getAddress(),
                    mapping.effectiveQuantity());
            return Flux.just(new PendingEncoded(request, propertyId, extractHeaders(message)));
        }
        if (message instanceof WritePropertyMessage) {
            WritePropertyMessage write = (WritePropertyMessage) message;
            Map<String, Object> properties = write.getProperties();
            if (properties == null || properties.isEmpty()) {
                return Flux.empty();
            }
            if (properties.size() > 1) {
                // Split into one write request per property so all registers are updated.
                log.warn("WritePropertyMessage contains {} properties; splitting into {} individual write requests.",
                        properties.size(), properties.size());
                String baseId = message.getMessageId();
                List<PendingEncoded> split = new ArrayList<>(properties.size());
                int i = 0;
                for (Map.Entry<String, Object> entry : properties.entrySet()) {
                    RegisterMapping m = table.require(entry.getKey());
                    ModbusRequest req = buildWriteRequest(slaveId, m, entry.getValue());
                    String derivedId = baseId != null ? baseId + "_s" + i : null;
                    split.add(new PendingEncoded(req, entry.getKey(), null, 0,
                            derivedId, baseId, extractHeaders(message), i, properties.size()));
                    i++;
                }
                return Flux.fromIterable(split);
            }
            Map.Entry<String, Object> first = properties.entrySet().iterator().next();
            String propertyId = first.getKey();
            RegisterMapping mapping = table.require(propertyId);
            ModbusRequest request = buildWriteRequest(slaveId, mapping, first.getValue());
            return Flux.just(new PendingEncoded(request, propertyId, extractHeaders(message)));
        }
        if (message instanceof FunctionInvokeMessage) {
            FunctionInvokeMessage fn = (FunctionInvokeMessage) message;
            String propertyId = fn.getFunctionId();
            RegisterMapping mapping = table.find(propertyId);
            if (mapping == null || !mapping.isWritable()) {
                return Flux.empty();
            }
            Object value = fn.getInputs() == null || fn.getInputs().isEmpty()
                    ? null : fn.getInputs().get(0).getValue();
            ModbusRequest request = buildWriteRequest(slaveId, mapping, value);
            return Flux.just(new PendingEncoded(request, propertyId, extractHeaders(message)));
        }
        return Flux.empty();
    }

    private Map<String, Object> extractHeaders(DeviceMessage message) {
        Map<String, Object> headers = new HashMap<>(3);
        message.getHeader(Headers.ignoreStorage).ifPresent(value -> headers.put(Headers.ignoreStorage.getKey(), value));
        message.getHeader(Headers.ignoreLog).ifPresent(value -> headers.put(Headers.ignoreLog.getKey(), value));
        message.getHeader(IGNORE_CACHE).ifPresent(value -> headers.put(IGNORE_CACHE.getKey(), value));
        return headers;
    }

    private ModbusRequest buildWriteRequest(int slaveId, RegisterMapping mapping, Object value) {
        ModbusFunctionCode fc = mapping.requireWriteFunctionCode();
        if (fc == ModbusFunctionCode.WRITE_SINGLE_COIL) {
            return ModbusRequest.writeSingleCoil(slaveId, mapping.getAddress(), toBool(value));
        }
        if (fc == ModbusFunctionCode.WRITE_MULTIPLE_COILS) {
            boolean[] coils = toBoolArray(value, mapping.effectiveQuantity());
            return ModbusRequest.writeMultipleCoils(slaveId, mapping.getAddress(), coils);
        }
        if (fc == ModbusFunctionCode.WRITE_SINGLE_REGISTER) {
            byte[] encoded = RegisterCodec.encode(mapping, toNumber(value));
            if (encoded.length != 2) {
                throw new IllegalStateException("Mapping for " + mapping.getPropertyId()
                        + " requires FC16 for " + mapping.getDataType());
            }
            int v = ((encoded[0] & 0xFF) << 8) | (encoded[1] & 0xFF);
            return ModbusRequest.writeSingleRegister(slaveId, mapping.getAddress(), v);
        }
        if (fc == ModbusFunctionCode.WRITE_MULTIPLE_REGISTERS) {
            byte[] encoded = RegisterCodec.encode(mapping, toNumber(value));
            int count = encoded.length / 2;
            int[] registers = new int[count];
            for (int i = 0; i < count; i++) {
                registers[i] = ((encoded[i * 2] & 0xFF) << 8) | (encoded[i * 2 + 1] & 0xFF);
            }
            return ModbusRequest.writeMultipleRegisters(slaveId, mapping.getAddress(), registers);
        }
        throw new IllegalStateException("Mapping for " + mapping.getPropertyId() + " uses non-write FC " + fc);
    }

    private Number toNumber(Object value) {
        if (value instanceof Number) {
            return (Number) value;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1 : 0;
        }
        if (value instanceof String) {
            return Double.parseDouble((String) value);
        }
        throw new IllegalArgumentException("Cannot coerce to Number: " + value);
    }

    private boolean toBool(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof String) {
            String s = ((String) value).trim().toLowerCase();
            return s.equals("true") || s.equals("1") || s.equals("on");
        }
        return false;
    }

    private boolean[] toBoolArray(Object value, int count) {
        if (value instanceof boolean[]) {
            return (boolean[]) value;
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            boolean[] out = new boolean[Math.max(count, list.size())];
            for (int i = 0; i < list.size(); i++) {
                out[i] = toBool(list.get(i));
            }
            return out;
        }
        boolean[] out = new boolean[count];
        out[0] = toBool(value);
        return out;
    }

    private Mono<Integer> resolveSlaveId(DeviceOperator device) {
        return device
                .getSelfConfig(CONFIG_SLAVE_ID)
                .map(Value::asInt)
                .defaultIfEmpty(1);
    }

    private Mono<RegisterMappingTable> resolveRegisterTable(DeviceOperator device) {
        if (device == null) {
            return Mono.just(RegisterMappingTable.empty());
        }
        return device
                .getProduct()
                .flatMap(product -> product
                        .getConfig(CONFIG_REGISTER_MAP)
                        .map(Value::asString)
                        .map(this::parseTable))
                .switchIfEmpty(Mono.fromSupplier(() -> mappingCache.getOrDefault(device.getDeviceId(), RegisterMappingTable.empty())));
    }

    private RegisterMappingTable parseTable(String json) {
        return mappingCache.computeIfAbsent(json, RegisterMappingTable::parse);
    }

    private Mono<Long> resolveTimeout(DeviceOperator device) {
        // Check slave-level config first, then fall back to product/gateway-level config,
        // then the hard-coded default. This ensures a timeout set on the gateway product
        // is honoured by all child slave devices that don't override it locally.
        return device
                .getSelfConfig(CONFIG_RESPONSE_TIMEOUT_MS)
                .switchIfEmpty(device.getProduct()
                        .flatMap(p -> p.getConfig(CONFIG_RESPONSE_TIMEOUT_MS)))
                .map(Value::asLong)
                .defaultIfEmpty(DEFAULT_RESPONSE_TIMEOUT_MS);
    }

    private Mono<Long> resolveDispatchInterval(DeviceOperator device) {
        return device
                .getSelfConfig(CONFIG_DISPATCH_INTERVAL_MS)
                .switchIfEmpty(device.getProduct()
                        .flatMap(p -> p.getConfig(CONFIG_DISPATCH_INTERVAL_MS)))
                .map(Value::asLong)
                .map(value -> Math.max(value, 0L))
                .defaultIfEmpty(DEFAULT_DISPATCH_INTERVAL_MS);
    }

    private Mono<String> resolveSessionId(MessageEncodeContext context) {
        DeviceOperator device = context.getDevice();
        if (device == null) {
            return Mono.just("__no_session__");
        }
        // Use the parent gateway device id as the pending-queue key so all
        // child slaves share a single in-flight slot on the physical bus.
        return device
                .getParentDevice()
                .map(DeviceOperator::getDeviceId)
                .defaultIfEmpty(device.getDeviceId());
    }

    private static final class PendingEncoded {
        final ModbusRequest request;
        final String propertyId;              // null when batch
        final List<String> batchPropertyIds;  // null when single
        final int batchStartAddress;
        /** Non-null only for split requests that need a per-frame tracking id. */
        final String messageIdOverride;
        /** Non-null when the logical upstream reply must keep the original id. */
        final String replyMessageId;
        final int splitIndex;
        final int splitTotal;
        final Map<String, Object> headers;

        PendingEncoded(ModbusRequest request, String propertyId) {
            this(request, propertyId, null, 0, null, null, Collections.emptyMap());
        }

        PendingEncoded(ModbusRequest request, String propertyId, Map<String, Object> headers) {
            this(request, propertyId, null, 0, null, null, headers);
        }

        PendingEncoded(ModbusRequest request, String propertyId,
                       List<String> batchPropertyIds, int batchStartAddress) {
            this(request, propertyId, batchPropertyIds, batchStartAddress, null, null, Collections.emptyMap());
        }

        PendingEncoded(ModbusRequest request, String propertyId,
                       List<String> batchPropertyIds, int batchStartAddress,
                       String messageIdOverride) {
            this(request, propertyId, batchPropertyIds, batchStartAddress,
                    messageIdOverride, null, Collections.emptyMap());
        }

        PendingEncoded(ModbusRequest request, String propertyId,
                       List<String> batchPropertyIds, int batchStartAddress,
                       String messageIdOverride, Map<String, Object> headers) {
            this(request, propertyId, batchPropertyIds, batchStartAddress,
                    messageIdOverride, null, headers);
        }

        PendingEncoded(ModbusRequest request, String propertyId,
                       List<String> batchPropertyIds, int batchStartAddress,
                       String messageIdOverride, String replyMessageId, Map<String, Object> headers) {
            this(request, propertyId, batchPropertyIds, batchStartAddress,
                    messageIdOverride, replyMessageId, headers, -1, 0);
        }

        PendingEncoded(ModbusRequest request, String propertyId,
                       List<String> batchPropertyIds, int batchStartAddress,
                       String messageIdOverride, String replyMessageId, Map<String, Object> headers,
                       int splitIndex, int splitTotal) {
            this.request = request;
            this.propertyId = propertyId;
            this.batchPropertyIds = batchPropertyIds;
            this.batchStartAddress = batchStartAddress;
            this.messageIdOverride = messageIdOverride;
            this.replyMessageId = replyMessageId;
            this.splitIndex = splitIndex;
            this.splitTotal = splitTotal;
            this.headers = headers == null ? Collections.emptyMap() : headers;
        }
    }

    private static final class GatewayResolution {
        private final boolean known;
        private final String gatewayId;

        private GatewayResolution(boolean known, String gatewayId) {
            this.known = known;
            this.gatewayId = gatewayId;
        }

        private static GatewayResolution known(String gatewayId) {
            return new GatewayResolution(gatewayId != null, gatewayId);
        }

        private static GatewayResolution unknown() {
            return new GatewayResolution(false, null);
        }
    }

    private static final class SplitAggregate {
        private final int total;
        private final String deviceId;
        private final String messageId;
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private int completed;

        private SplitAggregate(int total, String deviceId, String messageId) {
            this.total = total;
            this.deviceId = deviceId;
            this.messageId = messageId;
        }
    }

    /** Test hook: force-drain the property-id metadata map (tests only). */
    void clearPendingMetaForTests() {
        PENDING_META.clear();
        splitAggregates.clear();
    }

    int pendingMetaSizeForTests() {
        return PENDING_META.size();
    }

    List<PendingRequestQueue.PendingRequest> sweepExpiredForTests(long nowMillis) {
        return sweepExpiredRequests(nowMillis);
    }

    PendingRequestQueue.PendingRequest resolveUnknownPendingForTests(int slaveId, ModbusFunctionCode fc) {
        return resolvePendingForUnknownSession(slaveId, fc);
    }

    /** Test hook: pre-load batch metadata as encode() would. */
    void putBatchMeta(String messageId, List<String> propertyIds, int startAddress) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("batchPropertyIds", new ArrayList<>(propertyIds));
        meta.put("batchStartAddress", startAddress);
        PENDING_META.put(messageId, meta);
    }

    /** Test hook: pre-load batch metadata including request headers. */
    void putBatchMeta(String messageId, List<String> propertyIds, int startAddress, Map<String, Object> headers) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("batchPropertyIds", new ArrayList<>(propertyIds));
        meta.put("batchStartAddress", startAddress);
        meta.put(META_HEADERS, new HashMap<>(headers));
        PENDING_META.put(messageId, meta);
    }

    /** Test hook: pre-load single-property metadata as encode() would. */
    void putSingleMeta(String messageId,
                       String propertyId,
                       Map<String, Object> headers,
                       String replyMessageId,
                       RegisterMappingTable table) {
        putSingleMeta(messageId, propertyId, headers, replyMessageId, table, -1, 0);
    }

    /** Test hook: pre-load single-property split metadata as encode() would. */
    void putSingleMeta(String messageId,
                       String propertyId,
                       Map<String, Object> headers,
                       String replyMessageId,
                       RegisterMappingTable table,
                       int splitIndex,
                       int splitTotal) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("propertyId", propertyId);
        if (headers != null && !headers.isEmpty()) {
            meta.put(META_HEADERS, new HashMap<>(headers));
        }
        if (replyMessageId != null) {
            meta.put(META_REPLY_MESSAGE_ID, replyMessageId);
        }
        if (table != null) {
            meta.put(META_REGISTER_TABLE, table);
        }
        if (splitTotal > 1) {
            meta.put(META_SPLIT_INDEX, splitIndex);
            meta.put(META_SPLIT_TOTAL, splitTotal);
        }
        PENDING_META.put(messageId, meta);
    }

    /**
     * Test hook: parse a raw Modbus ADU as if it arrived from the wire, correlate
     * against the pending queue, and return the decoded DeviceMessage list.
     */
    List<?> decodeForTest(String gatewayId, byte[] adu, RegisterMappingTable table) {
        org.jetlinks.protocol.modbus.frame.ModbusResponse response =
                org.jetlinks.protocol.modbus.frame.ModbusResponse.parse(adu);
        PendingRequestQueue.PendingRequest pending =
                gatewayId == null
                        ? resolvePendingForUnknownSession(response)
                        : pendingQueue.findInFlight(gatewayId, response);
        if (pending == null) {
            return java.util.Collections.emptyList();
        }
        pendingQueue.ack(pending.getGatewayId());
        if (pending.isExpired(System.currentTimeMillis())) {
            releaseExpired(pending);
            return java.util.Collections.emptyList();
        }
        return buildRepliesSafely(pending, response, table);
    }
}
