package org.jetlinks.community.device.modbus;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.api.crud.entity.TransactionManagers;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.enums.DeviceState;
import org.jetlinks.community.device.enums.DeviceType;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.gateway.DeviceGatewayManager;
import org.jetlinks.community.gateway.supports.DeviceGatewayProvider;
import org.jetlinks.community.network.DefaultNetworkType;
import org.jetlinks.community.network.manager.entity.DeviceGatewayEntity;
import org.jetlinks.community.network.manager.entity.NetworkConfigEntity;
import org.jetlinks.community.network.manager.enums.DeviceGatewayState;
import org.jetlinks.community.network.manager.enums.NetworkConfigState;
import org.jetlinks.community.network.manager.service.DeviceGatewayService;
import org.jetlinks.community.network.manager.service.NetworkConfigService;
import org.jetlinks.community.utils.ObjectMappers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ModbusAccessService {

    private final NetworkConfigService networkConfigService;
    private final DeviceGatewayService deviceGatewayService;
    private final LocalDeviceProductService productService;
    private final LocalDeviceInstanceService deviceService;
    private final DeviceGatewayManager deviceGatewayManager;
    private final ObjectProvider<ModbusAutoCollectorService> collectorService;

    public ModbusAccessService(NetworkConfigService networkConfigService,
                               DeviceGatewayService deviceGatewayService,
                               LocalDeviceProductService productService,
                               LocalDeviceInstanceService deviceService,
                               DeviceGatewayManager deviceGatewayManager,
                               ObjectProvider<ModbusAutoCollectorService> collectorService) {
        this.networkConfigService = networkConfigService;
        this.deviceGatewayService = deviceGatewayService;
        this.productService = productService;
        this.deviceService = deviceService;
        this.deviceGatewayManager = deviceGatewayManager;
        this.collectorService = collectorService;
    }

    @Transactional(rollbackFor = Throwable.class, transactionManager = TransactionManagers.reactiveTransactionManager)
    public Mono<ModbusAccessResponse> save(ModbusAccessRequest request) {
        validate(request);
        String accessId = resolveAccessId(request);
        String name = StringUtils.hasText(request.getName()) ? request.getName() : accessId;
        String networkId = "modbus_" + accessId + "_network";
        String gatewayId = "modbus_" + accessId + "_gateway";
        String gatewayProductId = "modbus_" + accessId + "_gateway_product";
        String gatewayDeviceId = resolveGatewayDeviceId(accessId, request);
        ModbusCollectionPolicy defaultPolicy = request.getDefaultPolicy() == null
                ? ModbusCollectionPolicy.defaults()
                : request.getDefaultPolicy();
        defaultPolicy.validate();

        Map<String, ModbusAccessRequest.SlaveType> types = request
                .getSlaveTypes()
                .stream()
                .collect(Collectors.toMap(
                        type -> resolveTypeId(accessId, type),
                        Function.identity(),
                        (left, right) -> right,
                        LinkedHashMap::new));

        List<String> slaveProductIds = new ArrayList<>();
        List<String> slaveDeviceIds = new ArrayList<>();

        return networkConfigService.save(Mono.just(buildNetwork(networkId, name, request)))
                .then(deviceGatewayService.save(Mono.just(buildGateway(gatewayId, networkId, name, gatewayDeviceId, request,
                        DeviceGatewayState.disabled))))
                .then(productService.save(Mono.just(buildGatewayProduct(accessId, gatewayProductId, name, gatewayId, request, defaultPolicy))))
                .then(deviceService.save(Mono.just(buildGatewayDevice(accessId, gatewayDeviceId, name, gatewayProductId, request, defaultPolicy))))
                .thenMany(Flux.fromIterable(types.entrySet()))
                .concatMap(entry -> {
                    String typeId = entry.getKey();
                    ModbusAccessRequest.SlaveType type = entry.getValue();
                    String productId = slaveProductId(accessId, typeId);
                    slaveProductIds.add(productId);
                    return productService
                            .save(Mono.just(buildSlaveProduct(accessId, productId, name, gatewayId, type, request, defaultPolicy)))
                            .thenReturn(productId);
                })
                .thenMany(Flux.fromIterable(request.getSlaves()))
                .concatMap(slave -> {
                    String typeId = ModbusAccessProperties.sanitizeId(slave.getTypeId(), "default");
                    ModbusAccessRequest.SlaveType type = types.get(typeId);
                    if (type == null && types.size() == 1) {
                        typeId = types.keySet().iterator().next();
                        type = types.get(typeId);
                    }
                    if (type == null) {
                        return Mono.error(new IllegalArgumentException("slave type not found: " + slave.getTypeId()));
                    }
                    String productId = slaveProductId(accessId, typeId);
                    String deviceId = resolveSlaveDeviceId(gatewayDeviceId, slave);
                    slaveDeviceIds.add(deviceId);
                    return deviceService
                            .save(Mono.just(buildSlaveDevice(accessId, deviceId, productId, type, gatewayDeviceId, slave, defaultPolicy)))
                            .thenReturn(deviceId);
                })
                .then(deployAll(gatewayProductId, gatewayDeviceId, slaveProductIds, slaveDeviceIds))
                .then(deviceGatewayService.updateState(gatewayId, DeviceGatewayState.enabled))
                .then(deviceGatewayManager.reload(gatewayId))
                .then(Mono.defer(() -> {
                    ModbusAutoCollectorService collector = collectorService.getIfAvailable();
                    if (collector == null) {
                        return Mono.empty();
                    }
                    return collector.reload();
                }))
                .thenReturn(buildResponse(accessId, name, networkId, gatewayId, gatewayProductId, gatewayDeviceId,
                        slaveProductIds, slaveDeviceIds, defaultPolicy));
    }

    private Mono<Void> deployAll(String gatewayProductId,
                                 String gatewayDeviceId,
                                 List<String> slaveProductIds,
                                 List<String> slaveDeviceIds) {
        List<String> products = new ArrayList<>();
        products.add(gatewayProductId);
        products.addAll(slaveProductIds);
        List<String> devices = new ArrayList<>();
        devices.add(gatewayDeviceId);
        devices.addAll(slaveDeviceIds);
        return Flux
                .fromIterable(products)
                .concatMap(productService::deploy)
                .thenMany(Flux.fromIterable(devices))
                .concatMap(deviceService::deploy)
                .then();
    }

    private NetworkConfigEntity buildNetwork(String networkId, String name, ModbusAccessRequest request) {
        NetworkConfigEntity entity = new NetworkConfigEntity();
        entity.setId(networkId);
        entity.setName(name + " TCP");
        entity.setState(NetworkConfigState.enabled);
        entity.setShareCluster(true);
        entity.setConfiguration(buildNetworkConfiguration(networkId, request));
        entity.setType(isTcpClient(request)
                ? DefaultNetworkType.TCP_CLIENT.getId()
                : DefaultNetworkType.TCP_SERVER.getId());
        return entity;
    }

    private Map<String, Object> buildNetworkConfiguration(String networkId, ModbusAccessRequest request) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("id", networkId);
        config.put("parserType", "DIRECT");
        config.put("parserConfiguration", new HashMap<>());
        config.put("ssl", false);
        config.put("enabled", true);
        if (isTcpClient(request)) {
            config.put("host", request.getConnection().getHost());
            config.put("port", request.getConnection().getPort());
        } else {
            String host = StringUtils.hasText(request.getConnection().getListenHost())
                    ? request.getConnection().getListenHost()
                    : "0.0.0.0";
            int port = request.getConnection().getListenPort();
            config.put("host", host);
            config.put("port", port);
            config.put("publicHost", host);
            config.put("publicPort", port);
            config.put("tcpKeepAlive", true);
        }
        return config;
    }

    private DeviceGatewayEntity buildGateway(String gatewayId,
                                             String networkId,
                                             String name,
                                             String gatewayDeviceId,
                                             ModbusAccessRequest request,
                                             DeviceGatewayState state) {
        DeviceGatewayEntity entity = new DeviceGatewayEntity();
        entity.setId(gatewayId);
        entity.setName(name + " Access");
        entity.setProvider(isTcpClient(request)
                ? ModbusAccessProperties.TCP_CLIENT_PROVIDER
                : ModbusAccessProperties.TCP_SERVER_PROVIDER);
        entity.setState(state);
        entity.setChannel(DeviceGatewayProvider.CHANNEL_NETWORK);
        entity.setChannelId(networkId);
        entity.setProtocol(ModbusAccessProperties.PROTOCOL_ID);
        entity.setTransport("TCP");
        Map<String, Object> configuration = new HashMap<>();
        configuration.put("bindDeviceId", gatewayDeviceId);
        entity.setConfiguration(configuration);
        return entity;
    }

    private DeviceProductEntity buildGatewayProduct(String accessId,
                                                    String productId,
                                                    String name,
                                                    String gatewayId,
                                                    ModbusAccessRequest request,
                                                    ModbusCollectionPolicy policy) {
        Map<String, Object> config = baseConfiguration(accessId, request, policy);
        DeviceProductEntity entity = baseProduct(productId, name + " Host Product", DeviceType.gateway, gatewayId, request, config);
        entity.setMetadata(emptyMetadata());
        return entity;
    }

    private DeviceProductEntity buildSlaveProduct(String accessId,
                                                  String productId,
                                                  String accessName,
                                                  String gatewayId,
                                                  ModbusAccessRequest.SlaveType type,
                                                  ModbusAccessRequest request,
                                                  ModbusCollectionPolicy defaultPolicy) {
        List<Map<String, Object>> registerMap = type.getRegisterMap();
        ModbusCollectionPolicy policy = defaultPolicy.merge(type.getCollectionPolicy());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(ModbusAccessProperties.CONFIG_ACCESS_ID, accessId);
        config.put(ModbusAccessProperties.CONFIG_CONNECTION_MODE, request.getConnection().getMode().name());
        policy.applyTo(config);
        config.put(ModbusAccessProperties.CONFIG_REGISTER_MAP, ModbusAccessProperties.serializeRegisterMap(registerMap));
        DeviceProductEntity entity = baseProduct(productId,
                StringUtils.hasText(type.getName()) ? type.getName() : accessName + " Slave Type",
                DeviceType.childrenDevice,
                gatewayId,
                request,
                config);
        entity.setMetadata(ModbusAccessProperties.buildMetadata(registerMap));
        return entity;
    }

    private DeviceProductEntity baseProduct(String productId,
                                            String name,
                                            DeviceType type,
                                            String gatewayId,
                                            ModbusAccessRequest request,
                                            Map<String, Object> config) {
        DeviceProductEntity entity = new DeviceProductEntity();
        entity.setId(productId);
        entity.setName(name);
        entity.setDeviceType(type);
        entity.setMessageProtocol(ModbusAccessProperties.PROTOCOL_ID);
        entity.setProtocolName("Modbus RTU (TCP transparent)");
        entity.setTransportProtocol("TCP");
        entity.setAccessId(gatewayId);
        entity.setAccessName(gatewayId);
        entity.setAccessProvider(request == null || isTcpClient(request)
                ? ModbusAccessProperties.TCP_CLIENT_PROVIDER
                : ModbusAccessProperties.TCP_SERVER_PROVIDER);
        entity.setConfiguration(config);
        return entity;
    }

    private DeviceInstanceEntity buildGatewayDevice(String accessId,
                                                    String gatewayDeviceId,
                                                    String name,
                                                    String gatewayProductId,
                                                    ModbusAccessRequest request,
                                                    ModbusCollectionPolicy policy) {
        DeviceInstanceEntity entity = new DeviceInstanceEntity();
        entity.setId(gatewayDeviceId);
        entity.setName(StringUtils.hasText(request.getHost().getName()) ? request.getHost().getName() : name + " Host");
        entity.setProductId(gatewayProductId);
        entity.setProductName(name + " Host Product");
        entity.setDeviceType(DeviceType.gateway);
        entity.setDescribe(request.getHost().getDescription());
        entity.setState(DeviceState.notActive);
        entity.setConfiguration(baseConfiguration(accessId, request, policy));
        return entity;
    }

    private DeviceInstanceEntity buildSlaveDevice(String accessId,
                                                  String deviceId,
                                                  String productId,
                                                  ModbusAccessRequest.SlaveType type,
                                                  String gatewayDeviceId,
                                                  ModbusAccessRequest.Slave slave,
                                                  ModbusCollectionPolicy defaultPolicy) {
        ModbusCollectionPolicy typePolicy = defaultPolicy.merge(type.getCollectionPolicy());
        ModbusCollectionPolicy policy = typePolicy.merge(slave.getCollectionPolicy());
        Map<String, Object> config = new LinkedHashMap<>();
        policy.applyTo(config);
        config.put(ModbusAccessProperties.CONFIG_SLAVE_ID, slave.getSlaveId());
        config.put(ModbusAccessProperties.CONFIG_ACCESS_ID, accessId);

        DeviceInstanceEntity entity = new DeviceInstanceEntity();
        entity.setId(deviceId);
        entity.setName(StringUtils.hasText(slave.getName()) ? slave.getName() : "Modbus Slave " + slave.getSlaveId());
        entity.setProductId(productId);
        entity.setProductName(StringUtils.hasText(type.getName()) ? type.getName() : productId);
        entity.setDeviceType(DeviceType.childrenDevice);
        entity.setParentId(gatewayDeviceId);
        entity.setDescribe(slave.getDescription());
        entity.setState(DeviceState.notActive);
        entity.setConfiguration(config);
        return entity;
    }

    private Map<String, Object> baseConfiguration(String accessId,
                                                  ModbusAccessRequest request,
                                                  ModbusCollectionPolicy policy) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(ModbusAccessProperties.CONFIG_ACCESS_ID, accessId);
        if (request != null && request.getConnection() != null) {
            config.put(ModbusAccessProperties.CONFIG_CONNECTION_MODE, request.getConnection().getMode().name());
        }
        policy.applyTo(config);
        return config;
    }

    private ModbusAccessResponse buildResponse(String id,
                                               String name,
                                               String networkId,
                                               String gatewayId,
                                               String gatewayProductId,
                                               String gatewayDeviceId,
                                               List<String> slaveProductIds,
                                               List<String> slaveDeviceIds,
                                               ModbusCollectionPolicy policy) {
        ModbusAccessResponse response = new ModbusAccessResponse();
        response.setId(id);
        response.setName(name);
        response.setNetworkId(networkId);
        response.setGatewayId(gatewayId);
        response.setGatewayProductId(gatewayProductId);
        response.setGatewayDeviceId(gatewayDeviceId);
        response.setSlaveProductIds(new ArrayList<>(slaveProductIds));
        response.setSlaveDeviceIds(new ArrayList<>(slaveDeviceIds));
        response.setDefaultPolicy(policy);
        return response;
    }

    private void validate(ModbusAccessRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.getConnection() == null || request.getConnection().getMode() == null) {
            throw new IllegalArgumentException("connection.mode must not be empty");
        }
        if (isTcpClient(request)) {
            if (!StringUtils.hasText(request.getConnection().getHost())) {
                throw new IllegalArgumentException("connection.host must not be empty");
            }
            validatePort(request.getConnection().getPort(), "connection.port");
        } else {
            validatePort(request.getConnection().getListenPort(), "connection.listenPort");
        }
        if (request.getDefaultPolicy() == null) {
            request.setDefaultPolicy(ModbusCollectionPolicy.defaults());
        }
        request.getDefaultPolicy().validate();
        if (CollectionUtils.isEmpty(request.getSlaveTypes())) {
            throw new IllegalArgumentException("slaveTypes must not be empty");
        }
        if (CollectionUtils.isEmpty(request.getSlaves())) {
            throw new IllegalArgumentException("slaves must not be empty");
        }
        for (ModbusAccessRequest.SlaveType type : request.getSlaveTypes()) {
            if (!StringUtils.hasText(type.getId()) && !StringUtils.hasText(type.getName())) {
                type.setId("default");
            }
            ModbusAccessProperties.validateRegisterMap(type.getRegisterMap());
            if (type.getCollectionPolicy() != null) {
                request.getDefaultPolicy().merge(type.getCollectionPolicy());
            }
        }
        HashSet<Integer> slaveIds = new HashSet<>();
        for (ModbusAccessRequest.Slave slave : request.getSlaves()) {
            if (slave.getSlaveId() == null || slave.getSlaveId() < 1 || slave.getSlaveId() > 247) {
                throw new IllegalArgumentException("slaveId must be 1-247");
            }
            if (!slaveIds.add(slave.getSlaveId())) {
                throw new IllegalArgumentException("slaveId must not be duplicated: " + slave.getSlaveId());
            }
            if (slave.getCollectionPolicy() != null) {
                request.getDefaultPolicy().merge(slave.getCollectionPolicy());
            }
        }
    }

    private void validatePort(Integer port, String field) {
        if (port == null || port <= 0 || port > 65535) {
            throw new IllegalArgumentException(field + " must be 1-65535");
        }
    }

    private boolean isTcpClient(ModbusAccessRequest request) {
        return request.getConnection().getMode() == ModbusAccessRequest.ConnectionMode.PLATFORM_CONNECTS_GATEWAY;
    }

    private String resolveAccessId(ModbusAccessRequest request) {
        if (StringUtils.hasText(request.getId())) {
            return ModbusAccessProperties.sanitizeId(request.getId(), "modbus");
        }
        if (StringUtils.hasText(request.getName())) {
            return ModbusAccessProperties.sanitizeId(request.getName(), "modbus");
        }
        return IDGenerator.SNOW_FLAKE_STRING.generate();
    }

    private String resolveGatewayDeviceId(String accessId, ModbusAccessRequest request) {
        if (request.getHost() != null && StringUtils.hasText(request.getHost().getDeviceId())) {
            return ModbusAccessProperties.sanitizeId(request.getHost().getDeviceId(), "modbus_gateway");
        }
        if (request.getHost() != null && StringUtils.hasText(request.getHost().getCode())) {
            return ModbusAccessProperties.sanitizeId(request.getHost().getCode(), "modbus_gateway");
        }
        return "modbus_" + accessId + "_host";
    }

    private String resolveSlaveDeviceId(String gatewayDeviceId, ModbusAccessRequest.Slave slave) {
        if (StringUtils.hasText(slave.getDeviceId())) {
            return ModbusAccessProperties.sanitizeId(slave.getDeviceId(), "modbus_slave");
        }
        if (StringUtils.hasText(slave.getCode())) {
            return ModbusAccessProperties.sanitizeId(slave.getCode(), "modbus_slave");
        }
        return gatewayDeviceId + "_" + slave.getSlaveId();
    }

    private String resolveTypeId(String accessId, ModbusAccessRequest.SlaveType type) {
        if (StringUtils.hasText(type.getId())) {
            return ModbusAccessProperties.sanitizeId(type.getId(), "default");
        }
        if (StringUtils.hasText(type.getName())) {
            return ModbusAccessProperties.sanitizeId(type.getName(), "default");
        }
        return accessId + "_default";
    }

    private String slaveProductId(String accessId, String typeId) {
        return "modbus_" + accessId + "_" + ModbusAccessProperties.sanitizeId(typeId, "default") + "_product";
    }

    private String emptyMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("properties", new ArrayList<>());
        metadata.put("functions", new ArrayList<>());
        metadata.put("events", new ArrayList<>());
        metadata.put("tags", new ArrayList<>());
        return ObjectMappers.toJsonString(metadata);
    }
}
