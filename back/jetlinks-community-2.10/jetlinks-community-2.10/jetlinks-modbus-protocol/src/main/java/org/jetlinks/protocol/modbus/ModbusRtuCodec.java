package org.jetlinks.protocol.modbus;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.core.Value;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.message.DeviceMessage;
import org.jetlinks.core.message.ChildDeviceMessage;
import org.jetlinks.core.message.ChildDeviceMessageReply;
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
import org.jetlinks.protocol.modbus.frame.ModbusCrc16;
import org.jetlinks.protocol.modbus.frame.ModbusRequest;
import org.jetlinks.protocol.modbus.frame.ModbusResponse;
import org.jetlinks.protocol.modbus.mapping.RegisterCodec;
import org.jetlinks.protocol.modbus.mapping.RegisterDataType;
import org.jetlinks.protocol.modbus.mapping.RegisterMapping;
import org.jetlinks.protocol.modbus.mapping.RegisterMappingTable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modbus RTU codec for TCP transport. Converts JetLinks device messages into
 * Modbus request frames and back, using per-product register-maps and
 * per-device slave ids. Request/response correlation is handled by a
 * single-in-flight {@link ModbusGatewayRequestExecutor} keyed by gateway id,
 * matching the physical half-duplex nature of a Modbus bus.
 */
@Slf4j
public class ModbusRtuCodec implements DeviceMessageCodec {

    public static final String CONFIG_SLAVE_ID = "slaveId";
    public static final String CONFIG_REGISTER_MAP = "registerMap";
    public static final String CONFIG_RESPONSE_TIMEOUT_MS = "responseTimeoutMs";
    public static final String CONFIG_PARENT_ID = "parentId";
    public static final String CONFIG_MAX_READ_REGISTERS = "maxReadRegistersPerRequest";
    public static final String CONFIG_MAX_READ_BITS = "maxReadBitsPerRequest";
    public static final String CONFIG_MAX_READ_ADDRESS_GAP = "maxReadAddressGap";

    public static final String HEADER_REQUEST_SOURCE = "modbusRequestSource";
    public static final String HEADER_LOGICAL_REQUEST_ID = "modbusLogicalRequestId";
    public static final String HEADER_POLL_PLAN_ID = "modbusPollPlanId";
    public static final String HEADER_POLL_CYCLE_ID = "modbusPollCycleId";
    public static final String HEADER_POLL_LEASE_OWNER = "modbusPollLeaseOwnerToken";
    public static final String HEADER_POLL_LEASE_EXPIRES_AT = "modbusPollLeaseExpiresAt";
    public static final String HEADER_POLL_FRAME_INTERVAL_MS = "modbusPollFrameIntervalMs";

    public static final long DEFAULT_RESPONSE_TIMEOUT_MS = 3000L;

    /**
     * Maximum number of unused register/bit slots allowed between two
     * consecutive properties in a batch read. If any consecutive pair of
     * properties (sorted by address) has a gap larger than this value the
     * batch is split: only the first property is encoded in this turn and
     * the remaining ones must come from separate ReadPropertyMessages.
     * Keeping this small avoids wasting bus time reading empty registers.
     */
    static final int DEFAULT_MAX_READ_REGISTERS = 60;
    static final int DEFAULT_MAX_READ_BITS = 512;
    static final int DEFAULT_MAX_READ_ADDRESS_GAP = 2;
    static final int HARD_MAX_READ_REGISTERS = 125;
    static final int HARD_MAX_READ_BITS = 2000;

    private final ModbusGatewayRequestExecutor requestExecutor;
    private final Set<String> closeListeners = ConcurrentHashMap.newKeySet();

    private final Map<String, RegisterMappingTable> mappingCache = new java.util.concurrent.ConcurrentHashMap<>();

    public ModbusRtuCodec() {
        this(new ModbusGatewayRequestExecutor());
    }

    public ModbusRtuCodec(ModbusGatewayRequestExecutor requestExecutor) {
        this.requestExecutor = requestExecutor;
    }

    public ModbusGatewayRequestExecutor getRequestExecutor() {
        return requestExecutor;
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
        DeviceMessage outerMessage = (DeviceMessage) context.getMessage();
        DeviceMessage message = outerMessage;
        Mono<DeviceOperator> deviceMono = Mono.justOrEmpty(context.getDevice());
        if (outerMessage instanceof ChildDeviceMessage) {
            ChildDeviceMessage child = (ChildDeviceMessage) outerMessage;
            if (!(child.getChildDeviceMessage() instanceof DeviceMessage)) {
                return Mono.empty();
            }
            message = (DeviceMessage) child.getChildDeviceMessage();
            deviceMono = context.getDevice(child.getChildDeviceId());
        }
        if (message == null) {
            log.warn("Cannot encode Modbus message without device context: {}", message);
            return Mono.empty();
        }
        final DeviceMessage encodedMessage = message;
        return Mono
                .zip(
                        deviceMono,
                        deviceMono.flatMap(this::resolveSlaveId),
                        deviceMono.flatMap(this::resolveRegisterTable),
                        deviceMono.flatMap(this::resolveTimeout),
                        deviceMono.flatMap(this::resolveReadWindow),
                        resolveSessionId(context)
                )
                .flatMapMany(tuple -> buildRequests(encodedMessage, tuple.getT2(), tuple.getT3(), tuple.getT5())
                        .map(prepared -> {
                            String effectiveId = prepared.messageIdOverride != null
                                    ? prepared.messageIdOverride
                                    : encodedMessage.getMessageId();
                            return trackAndEncode(tuple.getT6(), encodedMessage.getDeviceId(),
                                    effectiveId, encodedMessage.getMessageId(), prepared, tuple.getT4(),
                                    encodedMessage, context);
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
        registerCloseListener(session);
        ByteBuf payload = context.getMessage().getPayload();
        byte[] bytes = new byte[payload.readableBytes()];
        payload.getBytes(payload.readerIndex(), bytes);

        // TCP is a byte stream. A fast Modbus slave may return multiple ADUs
        // in one read, especially when a property read is split by function
        // code. Decode each complete response independently instead of
        // treating the whole TCP chunk as one frame.
        return Flux
                .fromIterable(splitFrames(bytes))
                .concatMap(frame -> decodeFrame(fromCtx, session, frame));
    }

    private Flux<DeviceMessage> decodeFrame(FromDeviceMessageContext fromCtx,
                                             DeviceSession session,
                                             byte[] bytes) {

        final ModbusResponse response;
        try {
            response = ModbusResponse.parse(bytes);
        } catch (Exception e) {
            log.warn("Invalid Modbus response on session {}: {}", session.getId(), e.getMessage());
            return Flux.empty();
        }

        // Fix P1-key-mismatch: locate the in-flight request by slaveId+FC across all
        // gateway queues. This is safe because only one request can be in flight at
        // a time per gateway (serial queue invariant), and slaveId+FC uniquely
        // identifies the expected response. This avoids depending on session.getId()
        // matching the queue key, which fails when the session has not yet been
        // upgraded from UnknownTcpDeviceSession to TcpDeviceSession.
        ModbusFunctionCode responseFc = response.getFunction();
        String sessionGatewayId = session.getDeviceId();
        ModbusGatewayRequestExecutor.PendingRequest pending =
                requestExecutor.findInFlight(sessionGatewayId, response.getSlaveId(), responseFc);
        if (pending == null) {
            pending = requestExecutor.findUniqueInFlight(response.getSlaveId(), responseFc);
        }
        if (pending == null) {
            log.debug("Dropping unsolicited Modbus frame on session {}: slave={} fc={}",
                    session.getId(), response.getSlaveId(), responseFc);
            return Flux.empty();
        }

        String gatewayId = pending.getGatewayId();
        requestExecutor.acknowledge(gatewayId, pending.getRequestToken());
        final ModbusGatewayRequestExecutor.PendingRequest matched = pending;

        return fromCtx.getDevice(matched.getDeviceId())
                .switchIfEmpty(Mono.justOrEmpty(fromCtx.getDevice()))
                .flatMap(this::resolveRegisterTable)
                .defaultIfEmpty(RegisterMappingTable.empty())
                .flatMapMany(table -> Flux.fromIterable(
                                matched.isCancelled()
                                        ? Collections.<DeviceMessage>emptyList()
                                        : buildReplies(matched, response, table))
                        .map(reply -> wrapChildReply(fromCtx, matched, reply)))
                .switchIfEmpty(Flux.empty());
    }

    private List<byte[]> splitFrames(byte[] bytes) {
        if (bytes.length == 0) {
            return Collections.emptyList();
        }
        List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        while (offset < bytes.length) {
            int length = frameLength(bytes, offset);
            int remaining = bytes.length - offset;
            if (length <= 0 || length > remaining
                    || !ModbusCrc16.validate(bytes, offset, length)) {
                // Preserve the existing invalid-frame diagnostic for a
                // fragmented or malformed chunk. A future stream buffer can
                // retry this chunk when more bytes arrive.
                return frames.isEmpty()
                        ? Collections.singletonList(bytes)
                        : Collections.singletonList(Arrays.copyOfRange(bytes, offset, bytes.length));
            }
            frames.add(Arrays.copyOfRange(bytes, offset, offset + length));
            offset += length;
        }
        return frames;
    }

    private int frameLength(byte[] bytes, int offset) {
        int remaining = bytes.length - offset;
        if (remaining < 3) {
            return -1;
        }
        int function = bytes[offset + 1] & 0xFF;
        if ((function & 0x80) != 0) {
            return 5;
        }
        switch (function) {
            case 1:
            case 2:
            case 3:
            case 4:
                return 5 + (bytes[offset + 2] & 0xFF);
            case 5:
            case 6:
                return 8;
            case 15:
            case 16:
                return remaining < 7 ? -1 : 9 + (bytes[offset + 6] & 0xFF);
            default:
                return -1;
        }
    }

    private DeviceMessage wrapChildReply(FromDeviceMessageContext context,
                                          ModbusGatewayRequestExecutor.PendingRequest pending,
                                          DeviceMessage reply) {
        DeviceOperator gateway = context.getDevice();
        if (gateway == null || pending.getDeviceId() == null
                || pending.getDeviceId().equals(gateway.getDeviceId())) {
            return reply;
        }
        ChildDeviceMessageReply wrapped = new ChildDeviceMessageReply();
        wrapped.setDeviceId(gateway.getDeviceId());
        wrapped.setChildDeviceId(pending.getDeviceId());
        wrapped.setMessageId(reply.getMessageId());
        wrapped.setTimestamp(reply.getTimestamp());
        wrapped.setChildDeviceMessage(reply);
        return wrapped;
    }

    @SuppressWarnings("unchecked")
    private List<DeviceMessage> buildReplies(ModbusGatewayRequestExecutor.PendingRequest pending,
                                              ModbusResponse response,
                                              RegisterMappingTable table) {
        if (response.isException()) {
            return Collections.singletonList(buildErrorReply(pending, response));
        }

        if (pending.getRequest().getFunction().isWrite()) {
            return Collections.singletonList(buildWriteReply(pending, pending.getPropertyId()));
        }

        // Batch read path
        List<String> batchIds = pending.getBatchPropertyIds();
        if (batchIds != null) {
            return buildBatchReadReplies(pending, response, table, batchIds, pending.getBatchStartAddress());
        }

        // Single property path
        String propertyId = pending.getPropertyId();
        RegisterMapping mapping = propertyId == null ? null : table.find(propertyId);
        if (mapping == null) {
            log.warn("No register mapping for property '{}', cannot decode read response", propertyId);
            return Collections.emptyList();
        }
        Object value = decodeReadPayload(mapping, response.getPayload(), pending.getRequest().getQuantity());
        return Collections.singletonList(buildReadReply(pending, propertyId, value));
    }

    private List<DeviceMessage> buildBatchReadReplies(
            ModbusGatewayRequestExecutor.PendingRequest pending,
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
        reply.setMessageId(pending.getReplyMessageId());
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

    private DeviceMessage buildReadReply(ModbusGatewayRequestExecutor.PendingRequest pending,
                                          String propertyId,
                                          Object value) {
        ReadPropertyMessageReply reply = new ReadPropertyMessageReply();
        reply.setDeviceId(pending.getDeviceId());
        reply.setMessageId(pending.getReplyMessageId());
        reply.setTimestamp(System.currentTimeMillis());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(propertyId, value);
        reply.setProperties(properties);
        reply.setSuccess(true);
        return reply;
    }

    private DeviceMessage buildWriteReply(ModbusGatewayRequestExecutor.PendingRequest pending, String propertyId) {
        if (pending.getRequest().getFunction() == ModbusFunctionCode.WRITE_SINGLE_COIL
                || pending.getRequest().getFunction() == ModbusFunctionCode.WRITE_SINGLE_REGISTER
                || pending.getRequest().getFunction() == ModbusFunctionCode.WRITE_MULTIPLE_COILS
                || pending.getRequest().getFunction() == ModbusFunctionCode.WRITE_MULTIPLE_REGISTERS) {
            WritePropertyMessageReply reply = new WritePropertyMessageReply();
            reply.setDeviceId(pending.getDeviceId());
            reply.setMessageId(pending.getReplyMessageId());
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
        fnReply.setMessageId(pending.getReplyMessageId());
        fnReply.setTimestamp(System.currentTimeMillis());
        fnReply.setSuccess(true);
        return fnReply;
    }

    private DeviceMessage buildErrorReply(ModbusGatewayRequestExecutor.PendingRequest pending, ModbusResponse response) {
        ModbusExceptionCode ec = response.getExceptionCode();
        String code = ec == null ? "modbus-exception" : "modbus-" + ec.name().toLowerCase();
        String message = "Modbus exception: " + (ec == null ? "unknown" : ec.name());
        if (pending.getRequest().getFunction().isWrite()) {
            WritePropertyMessageReply reply = new WritePropertyMessageReply();
            reply.setDeviceId(pending.getDeviceId());
            reply.setMessageId(pending.getReplyMessageId());
            reply.setTimestamp(System.currentTimeMillis());
            reply.setSuccess(false);
            reply.setCode(code);
            reply.setMessage(message);
            return reply;
        }
        ReadPropertyMessageReply reply = new ReadPropertyMessageReply();
        reply.setDeviceId(pending.getDeviceId());
        reply.setMessageId(pending.getReplyMessageId());
        reply.setTimestamp(System.currentTimeMillis());
        reply.setSuccess(false);
        reply.setCode(code);
        reply.setMessage(message);
        return reply;
    }

    private EncodedMessage trackAndEncode(String sessionId,
                                           String deviceId,
                                           String effectiveMessageId,
                                           String replyMessageId,
                                           PendingEncoded prepared,
                                           long timeoutMs,
                                           DeviceMessage sourceMessage,
                                           MessageEncodeContext encodeContext) {
        ModbusGatewayRequestExecutor.Source source = sourceMessage
                .getHeader(HEADER_REQUEST_SOURCE)
                .map(String::valueOf)
                .filter("POLLING"::equalsIgnoreCase)
                .map(ignore -> ModbusGatewayRequestExecutor.Source.POLLING)
                .orElse(ModbusGatewayRequestExecutor.Source.MANUAL);

        ModbusGatewayRequestExecutor.PendingRequest pending =
                ModbusGatewayRequestExecutor.PendingRequest
                        .builder()
                        .gatewayId(sessionId)
                        .deviceId(deviceId)
                        .messageId(effectiveMessageId)
                        .replyMessageId(replyMessageId)
                        .logicalRequestId(header(sourceMessage, HEADER_LOGICAL_REQUEST_ID, replyMessageId))
                        .planId(header(sourceMessage, HEADER_POLL_PLAN_ID, null))
                        .cycleId(header(sourceMessage, HEADER_POLL_CYCLE_ID, null))
                        .leaseOwnerToken(header(sourceMessage, HEADER_POLL_LEASE_OWNER, null))
                        .source(source)
                        .request(prepared.request)
                        .timeoutMillis(timeoutMs)
                        .propertyId(prepared.propertyId)
                        .batchPropertyIds(prepared.batchPropertyIds)
                        .batchStartAddress(prepared.batchStartAddress)
                        .attributes(requestAttributes(sourceMessage))
                        .onCompletion(completion -> onRequestCompletion(completion, sourceMessage, encodeContext))
                        .build();

        byte[] adu = requestExecutor.submit(pending)
                ? pending.getRequest().toAdu()
                : new byte[0];
        return EncodedMessage.simple(Unpooled.wrappedBuffer(adu));
    }

    private Flux<PendingEncoded> buildRequests(DeviceMessage message,
                                               int slaveId,
                                               RegisterMappingTable table,
                                               ReadWindow readWindow) {
        if (message instanceof ReadPropertyMessage) {
            ReadPropertyMessage read = (ReadPropertyMessage) message;
            List<String> props = read.getProperties();
            if (props == null || props.isEmpty()) {
                return Flux.empty();
            }
            return Flux.fromIterable(buildReadWindows(message.getMessageId(), slaveId, table, props, readWindow));
            /*
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
            return Flux.just(new PendingEncoded(request, propertyId));*/
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

    private List<PendingEncoded> buildReadWindows(String baseMessageId,
                                                  int slaveId,
                                                  RegisterMappingTable table,
                                                  List<String> propertyIds,
                                                  ReadWindow readWindow) {
        List<RegisterMapping> mappings = new ArrayList<>(propertyIds.size());
        for (String propertyId : propertyIds) {
            RegisterMapping mapping = table.require(propertyId);
            if (mapping.getFunctionCode().isWrite()) {
                throw new IllegalArgumentException("Property is not readable: " + propertyId);
            }
            mappings.add(mapping);
        }
        mappings.sort((left, right) -> {
            int byFunction = Integer.compare(
                    left.getFunctionCode().getCode(),
                    right.getFunctionCode().getCode());
            return byFunction != 0
                    ? byFunction
                    : Integer.compare(left.getAddress(), right.getAddress());
        });

        List<List<RegisterMapping>> windows = new ArrayList<>();
        List<RegisterMapping> current = null;
        int start = -1;
        int end = -1;
        ModbusFunctionCode function = null;
        for (RegisterMapping mapping : mappings) {
            int mappingEnd = mapping.getAddress() + mapping.effectiveQuantity();
            int maxQuantity = mapping.getFunctionCode().isBitOriented()
                    ? readWindow.maxBits
                    : readWindow.maxRegisters;
            if (mapping.effectiveQuantity() > maxQuantity) {
                throw new IllegalArgumentException(
                        "Property " + mapping.getPropertyId() + " exceeds read window " + maxQuantity);
            }
            int gap = current == null ? 0 : mapping.getAddress() - end;
            boolean split = current == null
                    || mapping.getFunctionCode() != function
                    || gap > readWindow.maxAddressGap
                    || mappingEnd - start > maxQuantity;
            if (split) {
                current = new ArrayList<>();
                windows.add(current);
                function = mapping.getFunctionCode();
                start = mapping.getAddress();
                end = mappingEnd;
            }
            current.add(mapping);
            end = Math.max(end, mappingEnd);
        }

        List<PendingEncoded> requests = new ArrayList<>(windows.size());
        for (int i = 0; i < windows.size(); i++) {
            List<RegisterMapping> window = windows.get(i);
            RegisterMapping first = window.get(0);
            RegisterMapping last = window.get(window.size() - 1);
            int windowStart = first.getAddress();
            int windowEnd = last.getAddress() + last.effectiveQuantity();
            List<String> ids = new ArrayList<>(window.size());
            for (RegisterMapping mapping : window) {
                ids.add(mapping.getPropertyId());
            }
            String frameMessageId = windows.size() > 1 && baseMessageId != null
                    ? baseMessageId + "_w" + i
                    : null;
            requests.add(new PendingEncoded(
                    ModbusRequest.read(
                            slaveId,
                            first.getFunctionCode(),
                            windowStart,
                            windowEnd - windowStart),
                    null,
                    ids,
                    windowStart,
                    frameMessageId));
        }
        return requests;
    }

    private void onRequestCompletion(ModbusGatewayRequestExecutor.Completion completion,
                                     DeviceMessage sourceMessage,
                                     MessageEncodeContext encodeContext) {
        if (completion.getType() == ModbusGatewayRequestExecutor.CompletionType.RESPONSE) {
            return;
        }
        ModbusGatewayRequestExecutor.PendingRequest request = completion.getRequest();
        DeviceMessage reply = buildLifecycleErrorReply(
                request,
                completion.getType().name(),
                completion.getMessage());
        if (encodeContext.getMessage() instanceof ChildDeviceMessage) {
            ChildDeviceMessage child = (ChildDeviceMessage) encodeContext.getMessage();
            ChildDeviceMessageReply wrapped = new ChildDeviceMessageReply();
            wrapped.setDeviceId(child.getDeviceId());
            wrapped.setChildDeviceId(child.getChildDeviceId());
            wrapped.setMessageId(reply.getMessageId());
            wrapped.setTimestamp(reply.getTimestamp());
            wrapped.setChildDeviceMessage(reply);
            reply = wrapped;
        }
        encodeContext
                .reply(reply)
                .subscribe(
                        ignore -> {
                        },
                        error -> log.warn("Failed to publish Modbus lifecycle reply: {}", error.getMessage()));
    }

    private DeviceMessage buildLifecycleErrorReply(ModbusGatewayRequestExecutor.PendingRequest pending,
                                                   String code,
                                                   String message) {
        if (pending.getRequest().getFunction().isWrite()) {
            WritePropertyMessageReply reply = new WritePropertyMessageReply();
            reply.setDeviceId(pending.getDeviceId());
            reply.setMessageId(pending.getReplyMessageId());
            reply.setTimestamp(System.currentTimeMillis());
            reply.setSuccess(false);
            reply.setCode(code);
            reply.setMessage(message);
            return reply;
        }
        ReadPropertyMessageReply reply = new ReadPropertyMessageReply();
        reply.setDeviceId(pending.getDeviceId());
        reply.setMessageId(pending.getReplyMessageId());
        reply.setTimestamp(System.currentTimeMillis());
        reply.setSuccess(false);
        reply.setCode(code);
        reply.setMessage(message);
        return reply;
    }

    private String header(DeviceMessage message, String key, String defaultValue) {
        return message.getHeader(key).map(String::valueOf).orElse(defaultValue);
    }

    private Map<String, Object> requestAttributes(DeviceMessage message) {
        Map<String, Object> attributes = new HashMap<>();
        message.getHeader(HEADER_POLL_LEASE_EXPIRES_AT)
                .ifPresent(value -> attributes.put(HEADER_POLL_LEASE_EXPIRES_AT, value));
        message.getHeader(HEADER_POLL_FRAME_INTERVAL_MS)
                .ifPresent(value -> attributes.put(HEADER_POLL_FRAME_INTERVAL_MS, value));
        return attributes;
    }

    private void registerCloseListener(DeviceSession session) {
        String listenerKey = session.getId() + "@" + session.getDeviceId();
        if (!closeListeners.add(listenerKey)) {
            return;
        }
        session.onClose(() -> {
            closeListeners.remove(listenerKey);
            requestExecutor.drain(session.getDeviceId(), "gateway connection interrupted");
        });
    }

    private Mono<ReadWindow> resolveReadWindow(DeviceOperator device) {
        return Mono.zip(
                resolveIntConfig(device, CONFIG_MAX_READ_REGISTERS, DEFAULT_MAX_READ_REGISTERS),
                resolveIntConfig(device, CONFIG_MAX_READ_BITS, DEFAULT_MAX_READ_BITS),
                resolveIntConfig(device, CONFIG_MAX_READ_ADDRESS_GAP, DEFAULT_MAX_READ_ADDRESS_GAP)
        ).map(tuple -> new ReadWindow(
                clamp(tuple.getT1(), 1, HARD_MAX_READ_REGISTERS),
                clamp(tuple.getT2(), 1, HARD_MAX_READ_BITS),
                Math.max(0, tuple.getT3())));
    }

    private Mono<Integer> resolveIntConfig(DeviceOperator device, String key, int defaultValue) {
        return device
                .getSelfConfig(key)
                .switchIfEmpty(device.getProduct().flatMap(product -> product.getConfig(key)))
                .map(Value::asInt)
                .defaultIfEmpty(defaultValue);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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

    static final class ReadWindow {
        final int maxRegisters;
        final int maxBits;
        final int maxAddressGap;

        ReadWindow(int maxRegisters, int maxBits, int maxAddressGap) {
            this.maxRegisters = maxRegisters;
            this.maxBits = maxBits;
            this.maxAddressGap = maxAddressGap;
        }
    }

    /**
     * Test hook: parse a raw Modbus ADU as if it arrived from the wire, correlate
     * against the pending queue, and return the decoded DeviceMessage list.
     */
    List<?> decodeForTest(String gatewayId, byte[] adu, RegisterMappingTable table) {
        org.jetlinks.protocol.modbus.frame.ModbusResponse response =
                org.jetlinks.protocol.modbus.frame.ModbusResponse.parse(adu);
        org.jetlinks.protocol.modbus.frame.ModbusFunctionCode fc = response.getFunction();
        ModbusGatewayRequestExecutor.PendingRequest pending =
                requestExecutor.findInFlight(gatewayId, response.getSlaveId(), fc);
        if (pending == null) {
            return java.util.Collections.emptyList();
        }
        requestExecutor.acknowledge(gatewayId, pending.getRequestToken());
        return buildReplies(pending, response, table);
    }

    List<ModbusRequest> buildReadRequestsForTest(int slaveId,
                                                 RegisterMappingTable table,
                                                 List<String> propertyIds,
                                                 int maxRegisters,
                                                 int maxBits,
                                                 int maxAddressGap) {
        List<PendingEncoded> encoded = buildReadWindows(
                "test",
                slaveId,
                table,
                propertyIds,
                new ReadWindow(
                        clamp(maxRegisters, 1, HARD_MAX_READ_REGISTERS),
                        clamp(maxBits, 1, HARD_MAX_READ_BITS),
                        Math.max(0, maxAddressGap)));
        List<ModbusRequest> requests = new ArrayList<>(encoded.size());
        for (PendingEncoded request : encoded) {
            requests.add(request.request);
        }
        return requests;
    }
}
