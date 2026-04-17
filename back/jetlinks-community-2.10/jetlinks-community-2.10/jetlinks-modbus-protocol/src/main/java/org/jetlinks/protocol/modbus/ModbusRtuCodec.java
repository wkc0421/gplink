package org.jetlinks.protocol.modbus;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.core.Value;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.message.DeviceMessage;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    public static final String CONFIG_PARENT_ID = "parentId";

    public static final long DEFAULT_RESPONSE_TIMEOUT_MS = 3000L;

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
                        resolveSessionId(context)
                )
                .flatMapMany(tuple -> buildRequests(message, tuple.getT1(), tuple.getT2())
                        .map(prepared -> {
                            String effectiveId = prepared.messageIdOverride != null
                                    ? prepared.messageIdOverride
                                    : message.getMessageId();
                            return trackAndEncode(tuple.getT4(), message.getDeviceId(),
                                    effectiveId, prepared, tuple.getT3());
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

        // Fix P1-timeout: sweep expired in-flight entries before parsing the frame.
        // Any stale entry is cleared so the bus can advance; the original caller
        // will time out on its own via the platform's reply timeout.
        List<PendingRequestQueue.PendingRequest> expired =
                pendingQueue.sweepAllExpired(System.currentTimeMillis());
        for (PendingRequestQueue.PendingRequest exp : expired) {
            log.warn("Modbus request timed out ({}ms): gateway={} slave={} fc={} device={}",
                    exp.getTimeoutMillis(), exp.getGatewayId(),
                    exp.getRequest().getSlaveId(), exp.getRequest().getFunction(),
                    exp.getDeviceId());
            // Promote the next queued request immediately so the bus is not stalled.
            PendingRequestQueue.PendingRequest next = pendingQueue.promote(exp.getGatewayId());
            if (next != null) {
                session.send(EncodedMessage.simple(Unpooled.wrappedBuffer(next.getRequest().toAdu())))
                        .subscribe();
            }
        }

        final ModbusResponse response;
        try {
            response = ModbusResponse.parse(bytes);
        } catch (Exception e) {
            log.warn("Invalid Modbus response on session {}: {}", session.getId(), e.getMessage());
            return Mono.empty();
        }

        // Fix P1-key-mismatch: locate the in-flight request by slaveId+FC across all
        // gateway queues. This is safe because only one request can be in flight at
        // a time per gateway (serial queue invariant), and slaveId+FC uniquely
        // identifies the expected response. This avoids depending on session.getId()
        // matching the queue key, which fails when the session has not yet been
        // upgraded from UnknownTcpDeviceSession to TcpDeviceSession.
        ModbusFunctionCode responseFc = response.getFunction();
        PendingRequestQueue.PendingRequest pending =
                pendingQueue.findInFlightBySlaveAndFc(response.getSlaveId(), responseFc);
        if (pending == null) {
            log.debug("Dropping unsolicited Modbus frame on session {}: slave={} fc={}",
                    session.getId(), response.getSlaveId(), responseFc);
            return Mono.empty();
        }

        String gatewayId = pending.getGatewayId();
        pendingQueue.ack(gatewayId);

        return resolveRegisterTable(fromCtx.getDevice())
                .defaultIfEmpty(RegisterMappingTable.empty())
                .flatMapMany(table -> Flux.fromIterable(buildReplies(pending, response, table)))
                .concatWith(Flux.defer(() -> drainNext(gatewayId, fromCtx.getSession())));
    }

    private Flux<DeviceMessage> drainNext(String sessionId, DeviceSession session) {
        PendingRequestQueue.PendingRequest next = pendingQueue.promote(sessionId);
        if (next == null) {
            return Flux.empty();
        }
        byte[] adu = next.getRequest().toAdu();
        return session
                .send(EncodedMessage.simple(Unpooled.wrappedBuffer(adu)))
                .thenMany(Flux.<DeviceMessage>empty());
    }

    @SuppressWarnings("unchecked")
    private List<DeviceMessage> buildReplies(PendingRequestQueue.PendingRequest pending,
                                              ModbusResponse response,
                                              RegisterMappingTable table) {
        if (response.isException()) {
            return Collections.singletonList(buildErrorReply(pending, response));
        }

        Map<String, Object> meta = pending.getMessageId() != null
                ? PENDING_META.remove(pending.getMessageId()) : null;

        if (pending.getRequest().getFunction().isWrite()) {
            String propertyId = meta != null ? (String) meta.get("propertyId") : null;
            return Collections.singletonList(buildWriteReply(pending, propertyId));
        }

        // Batch read path
        List<String> batchIds = meta != null ? (List<String>) meta.get("batchPropertyIds") : null;
        if (batchIds != null) {
            int startAddress = meta.containsKey("batchStartAddress")
                    ? ((Number) meta.get("batchStartAddress")).intValue() : 0;
            return buildBatchReadReplies(pending, response, table, batchIds, startAddress);
        }

        // Single property path
        String propertyId = meta != null ? (String) meta.get("propertyId") : null;
        RegisterMapping mapping = propertyId == null ? null : table.find(propertyId);
        if (mapping == null) {
            log.warn("No register mapping for property '{}', cannot decode read response", propertyId);
            return Collections.emptyList();
        }
        Object value = decodeReadPayload(mapping, response.getPayload(), pending.getRequest().getQuantity());
        return Collections.singletonList(buildReadReply(pending, propertyId, value));
    }

    private List<DeviceMessage> buildBatchReadReplies(
            PendingRequestQueue.PendingRequest pending,
            ModbusResponse response,
            RegisterMappingTable table,
            List<String> propertyIds,
            int startAddress) {
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
        reply.setMessageId(pending.getMessageId());
        reply.setTimestamp(System.currentTimeMillis());
        reply.setProperties(properties);
        reply.setSuccess(!properties.isEmpty());
        return Collections.singletonList(reply);
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
                                          Object value) {
        ReadPropertyMessageReply reply = new ReadPropertyMessageReply();
        reply.setDeviceId(pending.getDeviceId());
        reply.setMessageId(pending.getMessageId());
        reply.setTimestamp(System.currentTimeMillis());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(propertyId, value);
        reply.setProperties(properties);
        reply.setSuccess(true);
        return reply;
    }

    private DeviceMessage buildWriteReply(PendingRequestQueue.PendingRequest pending, String propertyId) {
        if (pending.getRequest().getFunction() == ModbusFunctionCode.WRITE_SINGLE_COIL
                || pending.getRequest().getFunction() == ModbusFunctionCode.WRITE_SINGLE_REGISTER
                || pending.getRequest().getFunction() == ModbusFunctionCode.WRITE_MULTIPLE_COILS
                || pending.getRequest().getFunction() == ModbusFunctionCode.WRITE_MULTIPLE_REGISTERS) {
            WritePropertyMessageReply reply = new WritePropertyMessageReply();
            reply.setDeviceId(pending.getDeviceId());
            reply.setMessageId(pending.getMessageId());
            reply.setTimestamp(System.currentTimeMillis());
            reply.setSuccess(true);
            if (propertyId != null) {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put(propertyId, true);
                reply.setProperties(properties);
            }
            return reply;
        }
        FunctionInvokeMessageReply fnReply = new FunctionInvokeMessageReply();
        fnReply.setDeviceId(pending.getDeviceId());
        fnReply.setMessageId(pending.getMessageId());
        fnReply.setTimestamp(System.currentTimeMillis());
        fnReply.setSuccess(true);
        return fnReply;
    }

    private DeviceMessage buildErrorReply(PendingRequestQueue.PendingRequest pending, ModbusResponse response) {
        ModbusExceptionCode ec = response.getExceptionCode();
        String code = ec == null ? "modbus-exception" : "modbus-" + ec.name().toLowerCase();
        String message = "Modbus exception: " + (ec == null ? "unknown" : ec.name());
        if (pending.getRequest().getFunction().isWrite()) {
            WritePropertyMessageReply reply = new WritePropertyMessageReply();
            reply.setDeviceId(pending.getDeviceId());
            reply.setMessageId(pending.getMessageId());
            reply.setTimestamp(System.currentTimeMillis());
            reply.setSuccess(false);
            reply.setCode(code);
            reply.setMessage(message);
            return reply;
        }
        ReadPropertyMessageReply reply = new ReadPropertyMessageReply();
        reply.setDeviceId(pending.getDeviceId());
        reply.setMessageId(pending.getMessageId());
        reply.setTimestamp(System.currentTimeMillis());
        reply.setSuccess(false);
        reply.setCode(code);
        reply.setMessage(message);
        return reply;
    }

    private final Map<String, Map<String, Object>> PENDING_META = new java.util.concurrent.ConcurrentHashMap<>();

    private EncodedMessage trackAndEncode(String sessionId,
                                           String deviceId,
                                           String effectiveMessageId,
                                           PendingEncoded prepared,
                                           long timeoutMs) {
        PendingRequestQueue.PendingRequest pending = new PendingRequestQueue.PendingRequest(
                sessionId,
                deviceId,
                effectiveMessageId,
                prepared.request,
                timeoutMs
        );
        if (effectiveMessageId != null) {
            if (prepared.batchPropertyIds != null) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("batchPropertyIds", prepared.batchPropertyIds);
                meta.put("batchStartAddress", prepared.batchStartAddress);
                PENDING_META.put(effectiveMessageId, meta);
            } else if (prepared.propertyId != null) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("propertyId", prepared.propertyId);
                PENDING_META.put(effectiveMessageId, meta);
            }
        }
        pendingQueue.offer(sessionId, pending);
        PendingRequestQueue.PendingRequest promoted = pendingQueue.promote(sessionId);
        byte[] adu;
        if (promoted == pending) {
            adu = pending.getRequest().toAdu();
        } else {
            // something already in-flight, hold off: emit an empty frame to avoid collision
            adu = new byte[0];
        }
        return EncodedMessage.simple(Unpooled.wrappedBuffer(adu));
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
                ModbusFunctionCode commonFc = firstMapping.getFunctionCode();
                boolean allSameFc = true;
                for (String p : props) {
                    RegisterMapping m = table.find(p);
                    if (m == null || m.getFunctionCode() != commonFc) {
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
                        return Flux.just(new PendingEncoded(request, null, sortedIds, startAddr));
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
                    ModbusRequest req = ModbusRequest.read(slaveId, m.getFunctionCode(),
                            m.getAddress(), m.effectiveQuantity());
                    String derivedId = baseId != null ? baseId + "_s" + i : null;
                    split.add(new PendingEncoded(req, pid, null, 0, derivedId));
                }
                return Flux.fromIterable(split);
            }
            String propertyId = props.get(0);
            RegisterMapping mapping = table.require(propertyId);
            ModbusRequest request = ModbusRequest.read(
                    slaveId,
                    mapping.getFunctionCode(),
                    mapping.getAddress(),
                    mapping.effectiveQuantity());
            return Flux.just(new PendingEncoded(request, propertyId));
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
                    split.add(new PendingEncoded(req, entry.getKey(), null, 0, derivedId));
                    i++;
                }
                return Flux.fromIterable(split);
            }
            Map.Entry<String, Object> first = properties.entrySet().iterator().next();
            String propertyId = first.getKey();
            RegisterMapping mapping = table.require(propertyId);
            ModbusRequest request = buildWriteRequest(slaveId, mapping, first.getValue());
            return Flux.just(new PendingEncoded(request, propertyId));
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
            return Flux.just(new PendingEncoded(request, propertyId));
        }
        return Flux.empty();
    }

    private ModbusRequest buildWriteRequest(int slaveId, RegisterMapping mapping, Object value) {
        ModbusFunctionCode fc = mapping.getFunctionCode();
        if (fc == ModbusFunctionCode.WRITE_SINGLE_COIL) {
            return ModbusRequest.writeSingleCoil(slaveId, mapping.getAddress(), toBool(value));
        }
        if (fc == ModbusFunctionCode.WRITE_MULTIPLE_COILS) {
            boolean[] coils = toBoolArray(value, mapping.effectiveQuantity());
            return ModbusRequest.writeMultipleCoils(slaveId, mapping.getAddress(), coils);
        }
        if (fc == ModbusFunctionCode.WRITE_SINGLE_REGISTER) {
            byte[] encoded = RegisterCodec.encode(mapping, toNumber(value));
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

        PendingEncoded(ModbusRequest request, String propertyId) {
            this(request, propertyId, null, 0, null);
        }

        PendingEncoded(ModbusRequest request, String propertyId,
                       List<String> batchPropertyIds, int batchStartAddress) {
            this(request, propertyId, batchPropertyIds, batchStartAddress, null);
        }

        PendingEncoded(ModbusRequest request, String propertyId,
                       List<String> batchPropertyIds, int batchStartAddress,
                       String messageIdOverride) {
            this.request = request;
            this.propertyId = propertyId;
            this.batchPropertyIds = batchPropertyIds;
            this.batchStartAddress = batchStartAddress;
            this.messageIdOverride = messageIdOverride;
        }
    }

    /** Test hook: force-drain the property-id metadata map (tests only). */
    void clearPendingMetaForTests() {
        PENDING_META.clear();
    }

    /** Test hook: pre-load batch metadata as encode() would. */
    void putBatchMeta(String messageId, List<String> propertyIds, int startAddress) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("batchPropertyIds", new ArrayList<>(propertyIds));
        meta.put("batchStartAddress", startAddress);
        PENDING_META.put(messageId, meta);
    }

    /**
     * Test hook: parse a raw Modbus ADU as if it arrived from the wire, correlate
     * against the pending queue, and return the decoded DeviceMessage list.
     */
    List<?> decodeForTest(String gatewayId, byte[] adu, RegisterMappingTable table) {
        org.jetlinks.protocol.modbus.frame.ModbusResponse response =
                org.jetlinks.protocol.modbus.frame.ModbusResponse.parse(adu);
        org.jetlinks.protocol.modbus.frame.ModbusFunctionCode fc = response.getFunction();
        PendingRequestQueue.PendingRequest pending =
                pendingQueue.findInFlightBySlaveAndFc(response.getSlaveId(), fc);
        if (pending == null) {
            return java.util.Collections.emptyList();
        }
        pendingQueue.ack(gatewayId);
        return buildReplies(pending, response, table);
    }
}
