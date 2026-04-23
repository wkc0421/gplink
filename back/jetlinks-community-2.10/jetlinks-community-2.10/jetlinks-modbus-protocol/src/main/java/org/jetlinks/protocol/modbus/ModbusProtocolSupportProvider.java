package org.jetlinks.protocol.modbus;

import org.jetlinks.core.defaults.CompositeProtocolSupport;
import org.jetlinks.core.message.codec.DefaultTransport;
import org.jetlinks.core.metadata.DefaultConfigMetadata;
import org.jetlinks.core.metadata.types.IntType;
import org.jetlinks.core.metadata.types.PasswordType;
import org.jetlinks.core.metadata.types.StringType;
import org.jetlinks.core.spi.ProtocolSupportProvider;
import org.jetlinks.core.spi.ServiceContext;
import org.jetlinks.supports.official.JetLinksDeviceMetadataCodec;
import reactor.core.publisher.Mono;

public class ModbusProtocolSupportProvider implements ProtocolSupportProvider {

    public static final String PROTOCOL_ID = "modbus-rtu.v1";

    private static final DefaultConfigMetadata GATEWAY_CONFIG = new DefaultConfigMetadata(
            "Modbus 网关配置",
            "Modbus RTU over TCP 网关（主机）设备配置")
            .add("responseTimeoutMs", "响应超时(ms)",
                    "单次请求最长等待时间,默认 3000ms", new IntType())
            .add("probeIntervalMs", "探测周期(ms)",
                    "平台主动下发探测帧的周期,默认 30000ms", new IntType())
            .add("keepOnlineTimeout", "保活超时(s)",
                    "无流量时的在线保活时长,默认 120s", new IntType())
            .add("secureKey", "secureKey",
                    "可选的软校验密钥,为空则跳过", new PasswordType());

    private static final DefaultConfigMetadata SLAVE_CONFIG = new DefaultConfigMetadata(
            "Modbus 从机配置",
            "Modbus RTU 从机设备配置")
            .add(ModbusRtuCodec.CONFIG_SLAVE_ID, "slaveId",
                    "Modbus 从机地址,范围 1~247", new IntType())
            .add("probeFunctionCode", "探测功能码",
                    "平台探测在线状态时使用的功能码(1/2/3/4),默认 3", new IntType())
            .add("probeStartAddress", "探测起始地址",
                    "默认 0", new IntType())
            .add("probeQuantity", "探测寄存器数",
                    "默认 1", new IntType())
            .add(ModbusRtuCodec.CONFIG_REGISTER_MAP, "寄存器映射",
                    "JSON 数组,每条包含 propertyId/fc/address/dataType 等", new StringType());

    @Override
    public Mono<? extends CompositeProtocolSupport> create(ServiceContext context) {
        return Mono.defer(() -> {
            CompositeProtocolSupport support = new CompositeProtocolSupport();
            support.setId(PROTOCOL_ID);
            support.setName("Modbus RTU (TCP 透传)");
            support.setDescription("Modbus RTU over TCP 协议包,支持以 TCP 网关形式接入 Modbus 从机。");

            support.setMetadataCodec(new JetLinksDeviceMetadataCodec());

            ModbusRtuCodec codec = new ModbusRtuCodec();
            support.addMessageCodecSupport(DefaultTransport.TCP, codec);
            support.addConfigMetadata(DefaultTransport.TCP, GATEWAY_CONFIG.merge(SLAVE_CONFIG));

            support.doOnClientConnect(DefaultTransport.TCP, new ModbusOnConnectHandler());
            support.setDeviceStateChecker(new ModbusStateChecker());

            support.setDocument(DefaultTransport.TCP,
                    "document-modbus.md",
                    ModbusProtocolSupportProvider.class.getClassLoader());

            return Mono.just(support);
        });
    }
}
