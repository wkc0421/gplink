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
            "Modbus gateway config",
            "Modbus RTU over TCP gateway device config")
            .add(ModbusRtuCodec.CONFIG_RESPONSE_TIMEOUT_MS, "responseTimeoutMs",
                    "Single request response timeout. Default 3000ms.", new IntType())
            .add("collectEnabled", "collectEnabled",
                    "Enable Modbus auto collection. Default true.", new IntType())
            .add("scanIntervalMs", "scanIntervalMs",
                    "Auto collection scan interval. Default 5000ms.", new IntType())
            .add(ModbusRtuCodec.CONFIG_DISPATCH_INTERVAL_MS, "dispatchIntervalMs",
                    "Minimum interval between Modbus frames on the same gateway. Default 50ms.", new IntType())
            .add("storageIntervalMs", "storageIntervalMs",
                    "Property storage interval. Default 60000ms.", new IntType())
            .add("probeIntervalMs", "probeIntervalMs",
                    "Gateway online probe interval. Default 30000ms.", new IntType())
            .add("keepOnlineTimeout", "keepOnlineTimeout",
                    "Keep-online timeout in seconds. Default 120s.", new IntType())
            .add("secureKey", "secureKey",
                    "Optional soft validation secret.", new PasswordType());

    private static final DefaultConfigMetadata SLAVE_CONFIG = new DefaultConfigMetadata(
            "Modbus slave config",
            "Modbus RTU slave device config")
            .add(ModbusRtuCodec.CONFIG_SLAVE_ID, "slaveId",
                    "Modbus slave address, range 1-247.", new IntType())
            .add("collectEnabled", "collectEnabled",
                    "Slave-level auto collection switch. Overrides type/access default.", new IntType())
            .add("scanIntervalMs", "scanIntervalMs",
                    "Slave-level scan interval. Overrides type/access default.", new IntType())
            .add(ModbusRtuCodec.CONFIG_DISPATCH_INTERVAL_MS, "dispatchIntervalMs",
                    "Slave-level gateway frame interval. Overrides type/access default.", new IntType())
            .add("storageIntervalMs", "storageIntervalMs",
                    "Slave-level storage interval. Overrides type/access default.", new IntType())
            .add(ModbusRtuCodec.CONFIG_RESPONSE_TIMEOUT_MS, "responseTimeoutMs",
                    "Slave-level response timeout. Overrides type/access default.", new IntType())
            .add("probeFunctionCode", "probeFunctionCode",
                    "Function code used by online probe (1/2/3/4). Default 3.", new IntType())
            .add("probeStartAddress", "probeStartAddress",
                    "Probe start address. Default 0.", new IntType())
            .add("probeQuantity", "probeQuantity",
                    "Probe register quantity. Default 1.", new IntType())
            .add(ModbusRtuCodec.CONFIG_REGISTER_MAP, "registerMap",
                    "JSON array with propertyId/functionCode/address/dataType fields.", new StringType());

    @Override
    public Mono<? extends CompositeProtocolSupport> create(ServiceContext context) {
        return Mono.defer(() -> {
            CompositeProtocolSupport support = new CompositeProtocolSupport();
            support.setId(PROTOCOL_ID);
            support.setName("Modbus RTU (TCP transparent)");
            support.setDescription("Modbus RTU over TCP protocol package for gateway-style Modbus slave access.");

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
