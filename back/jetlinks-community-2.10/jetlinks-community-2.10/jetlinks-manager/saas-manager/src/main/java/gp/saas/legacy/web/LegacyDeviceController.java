package gp.saas.legacy.web;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import gp.saas.legacy.dto.CustomizePropertyExcelInfo;
import gp.saas.legacy.dto.DeviceStatusEntity;
import gp.saas.legacy.dto.SimpleDeviceInstanceEntity;
import gp.saas.legacy.dto.SimpleDeviceProperty;
import gp.saas.legacy.util.LegacyMetadataUtils;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.reactive.ReactiveSqlExecutor;
import org.hswebframework.reactor.excel.CellDataType;
import org.hswebframework.reactor.excel.ExcelHeader;
import org.hswebframework.reactor.excel.ReactorExcel;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.community.things.data.ThingsDataConstants;
import org.jetlinks.community.things.utils.ThingsDatabaseUtils;
import org.jetlinks.core.device.DeviceConfigKey;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.message.property.ReportPropertyMessage;
import org.jetlinks.core.metadata.PropertyMetadata;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/device")
@Authorize(ignore = true)
public class LegacyDeviceController {

    private static final String LEGACY_PASSWORD = "yada8888";
    private static final String ROW_STORE_POLICY = "timescaledb-row";

    private final LocalDeviceInstanceService deviceService;
    private final LocalDeviceProductService productService;
    private final DeviceRegistry registry;
    private final DeviceDataService deviceDataService;
    private final ReactiveSqlExecutor sqlExecutor;
    private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    public LegacyDeviceController(LocalDeviceInstanceService deviceService,
                                  LocalDeviceProductService productService,
                                  DeviceRegistry registry,
                                  DeviceDataService deviceDataService,
                                  ReactiveSqlExecutor sqlExecutor) {
        this.deviceService = deviceService;
        this.productService = productService;
        this.registry = registry;
        this.deviceDataService = deviceDataService;
        this.sqlExecutor = sqlExecutor;
    }

    @GetMapping("/{id:.+}")
    public Mono<DeviceInstanceEntity> getDevice(@PathVariable String id) {
        return deviceService.findById(id);
    }

    @GetMapping("/{id:.+}/status")
    public Mono<DeviceStatusEntity> getStatus(@PathVariable String id) {
        return deviceService
            .findById(id)
            .flatMap(device -> registry
                .getDevice(id)
                .flatMap(operator -> operator
                    .checkState()
                    .then(Mono.defer(operator::getState))
                    .defaultIfEmpty(org.jetlinks.core.device.DeviceState.unknown)
                    .flatMap(state -> {
                        boolean online = state == org.jetlinks.core.device.DeviceState.online;
                        Mono<Long> lastMessageTime = online ? operator.getOnlineTime() : operator.getOfflineTime();
                        return lastMessageTime
                            .defaultIfEmpty(device.getModifyTime())
                            .map(time -> toStatus(state, online, time));
                    }))
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    boolean online = org.jetlinks.community.device.enums.DeviceState.online.equals(device.getState());
                    return toStatus(
                        online ? org.jetlinks.core.device.DeviceState.online : org.jetlinks.core.device.DeviceState.offline,
                        online,
                        device.getModifyTime());
                })));
    }

    @PostMapping
    public Mono<DeviceInstanceEntity> createDevice(@RequestBody SimpleDeviceInstanceEntity request) {
        return productService.findById(request.getProductId())
            .flatMap(product -> {
                DeviceInstanceEntity device = toDevice(request, product);
                return deviceService
                    .insert(device)
                    .then(deviceService.deploy(device.getId()).then())
                    .then(deviceService.findById(device.getId()));
            });
    }

    @PostMapping("/{productId:.+}/devices")
    public Flux<DeviceInstanceEntity> createDevices(@PathVariable String productId,
                                                    @RequestBody List<SimpleDeviceInstanceEntity> devices) {
        return Flux.fromIterable(devices)
            .doOnNext(device -> device.setProductId(productId))
            .flatMap(this::createDevice);
    }

    @PostMapping("/{productId:.+}/{orgId:.+}/devices")
    public Mono<Integer> createDevicesForOrg(@PathVariable String productId,
                                             @PathVariable String orgId,
                                             @RequestBody List<SimpleDeviceInstanceEntity> devices) {
        return createDevices(productId, devices).count().map(Long::intValue);
    }

    @PutMapping("/{id:.+}")
    public Mono<Integer> updateDevice(@PathVariable String id, @RequestBody SimpleDeviceInstanceEntity request) {
        return deviceService
            .createUpdate()
            .set(DeviceInstanceEntity::getName, request.getName())
            .where(DeviceInstanceEntity::getId, id)
            .execute();
    }

    @DeleteMapping("/{id:.+}")
    public Mono<Integer> deleteDevice(@PathVariable String id) {
        return deviceService.unregisterDevice(id).then(deviceService.deleteById(id));
    }

    @PostMapping("/product/{productId:.+}")
    public Mono<Integer> deleteByProduct(@PathVariable String productId, @RequestParam String password) {
        if (!LEGACY_PASSWORD.equals(password)) {
            return Mono.just(0);
        }
        return deviceService
            .createQuery()
            .where(DeviceInstanceEntity::getProductId, productId)
            .fetch()
            .map(DeviceInstanceEntity::getId)
            .collectList()
            .flatMapMany(Flux::fromIterable)
            .flatMap(id -> deviceService.unregisterDevice(id).then(deviceService.deleteById(id)))
            .reduce(0, Integer::sum);
    }

    @PostMapping("/_query")
    public Mono<PagerResult<DeviceInstanceEntity>> query(@RequestBody QueryParamEntity query) {
        return deviceService.queryPager(query);
    }

    @PostMapping("/_query/no-paging")
    public Flux<DeviceInstanceEntity> queryNoPaging(@RequestBody QueryParamEntity query) {
        return deviceService.query(query);
    }

    @GetMapping("/{id:.+}/metadata")
    public Mono<JSONObject> metadata(@PathVariable String id) {
        return findDeviceMetadata(id).map(LegacyMetadataUtils::parseMetadata);
    }

    @GetMapping("/{id:.+}/metadata/properties")
    public Mono<JSONArray> metadataProperties(@PathVariable String id) {
        return findDeviceMetadata(id).map(LegacyMetadataUtils::getProperties);
    }

    @GetMapping("/{id:.+}/metadata/functions")
    public Mono<JSONArray> metadataFunctions(@PathVariable String id) {
        return findDeviceMetadata(id).map(LegacyMetadataUtils::getFunctions);
    }

    @PostMapping("/{id:.+}/{functionId:.+}")
    public Flux<?> invokeFunction(@PathVariable String id,
                                  @PathVariable String functionId,
                                  @RequestBody Map<String, Object> body) {
        Map<String, Object> input = new HashMap<>();
        input.put("input", body);
        return deviceService.invokeFunction(id, functionId, input);
    }

    @PostMapping("/{id:.+}/{property:.+}/read")
    public Mono<Map<String, Object>> readProperty(@PathVariable String id, @PathVariable String property) {
        return deviceService.readProperty(id, property);
    }

    @PostMapping("/{id:.+}/{property:.+}/history/write")
    public Flux<Void> writeDeviceProperty(@PathVariable String id,
                                          @PathVariable String property,
                                          @RequestBody List<SimpleDeviceProperty> propertyList) {
        return deviceService
            .findById(id)
            .switchIfEmpty(Mono.error(new BusinessException("device not found", "error.device_not_found")))
            .flatMapMany(device -> Flux
                .fromIterable(propertyList)
                .flatMap(item -> {
                    ReportPropertyMessage message = new ReportPropertyMessage();
                    message.setDeviceId(id);
                    message.setTimestamp(item.getTimestamp());
                    message.setProperties(Map.of(property, item.getValue()));
                    message.addHeader("productId", device.getProductId());
                    return deviceDataService.saveDeviceMessage(message);
                }));
    }

    @PostMapping("/{id:.+}/{property:.+}/history/update")
    public Mono<Void> updateDeviceProperty(@PathVariable String id,
                                           @PathVariable String property,
                                           @RequestBody List<SimpleDeviceProperty> propertyList) {
        if (propertyList == null || propertyList.isEmpty()) {
            return Mono.error(new IllegalArgumentException("property data must not be empty"));
        }
        return deviceService
            .findById(id)
            .switchIfEmpty(Mono.error(new BusinessException("device not found", "error.device_not_found")))
            .flatMap(this::assertRowModeHistoryWritable)
            .flatMap(device -> registry
                .getDevice(id)
                .switchIfEmpty(Mono.error(new BusinessException("device not active", "error.device_not_found")))
                .flatMap(operator -> operator
                    .getMetadata()
                    .flatMap(metadata -> metadata
                        .getProperty(property)
                        .map(propertyMetadata -> upsertHistoryProperties(device, propertyMetadata, propertyList))
                        .orElseGet(() -> Mono.error(new BusinessException(
                            "device property not found: " + property,
                            "error.property_not_found"))))));
    }

    @DeleteMapping("/{deviceId:.+}/history/delete")
    public Mono<Long> deleteDeviceHistoryData(@PathVariable String deviceId,
                                              @RequestParam String id,
                                              @RequestParam Long timestamp) {
        return deviceService
            .findById(deviceId)
            .switchIfEmpty(Mono.error(new BusinessException("device not found", "error.device_not_found")))
            .flatMap(this::assertRowModeHistoryWritable)
            .flatMap(device -> sqlExecutor
                .update(
                    "delete from \"" + propertyTable(device) + "\" where \"id\" = ? and \"" +
                        ThingsDataConstants.COLUMN_THING_ID + "\" = ?",
                    id,
                    deviceId)
                .map(Number::longValue));
    }

    @GetMapping("/bind")
    public Mono<Boolean> bind(@RequestParam String gatewayId, @RequestParam String deviceId) {
        if (gatewayId == null || deviceId == null) {
            return Mono.just(false);
        }
        if (gatewayId.equals(deviceId)) {
            return Mono.just(true);
        }
        return deviceService
            .checkCyclicDependency(deviceId, gatewayId)
            .then(deviceService
                .createUpdate()
                .set(DeviceInstanceEntity::getParentId, gatewayId)
                .where(DeviceInstanceEntity::getId, deviceId)
                .execute())
            .flatMap(count -> {
                if (count <= 0) {
                    return Mono.just(false);
                }
                return registry
                    .getDevice(deviceId)
                    .flatMap(device -> device.setConfig(DeviceConfigKey.parentGatewayId, gatewayId))
                    .then(registry.getDevice(gatewayId)
                        .flatMap(gwOperator -> gwOperator.getProtocol()
                            .flatMap(protocolSupport -> protocolSupport.onChildBind(
                                gwOperator,
                                Flux.from(registry.getDevice(deviceId)))
                            )))
                    .thenReturn(true);
            });
    }

    @PostMapping("/{deviceId:.+}/property-metadata/update")
    public Mono<String> updateDevicePropertyMetadata(@PathVariable String deviceId,
                                                     @RequestBody List<CustomizePropertyExcelInfo> propertyList) {
        List<JSONObject> properties = propertyList
            .stream()
            .map(CustomizePropertyExcelInfo::toPropertyJson)
            .collect(Collectors.toList());
        return deviceService.findById(deviceId).flatMap(device -> {
            String metadata = LegacyMetadataUtils.replaceProperties(device.getDeriveMetadata(), properties);
            device.setDeriveMetadata(metadata);
            return deviceService
                .updateById(deviceId, device)
                .then(registry.getDevice(deviceId).flatMap(operator -> operator.updateMetadata(metadata)).then())
                .thenReturn(metadata);
        });
    }

    @GetMapping("/{deviceId:.+}/property-metadata/list")
    public Mono<List<CustomizePropertyExcelInfo>> getDevicePropertyMetadataList(@PathVariable String deviceId) {
        return findDeviceMetadata(deviceId)
            .map(LegacyMetadataUtils::getProperties)
            .flatMapMany(CustomizePropertyExcelInfo::fromProperties)
            .collectList();
    }

    @GetMapping("/{deviceId:.+}/property-metadata/properties.{format}")
    public Mono<Void> downloadDevicePropertyMetadata(@PathVariable String deviceId,
                                                     @PathVariable String format,
                                                     ServerHttpResponse response) {
        setDownloadHeader(response, "properties.", format);
        return getDevicePropertyMetadataList(deviceId)
            .flatMapMany(Flux::fromIterable)
            .as(data -> writeExcel(format, data))
            .map(bufferFactory::wrap)
            .as(response::writeWith);
    }

    @GetMapping("/property-metadata/properties_template.{format}")
    public Mono<Void> downloadDevicePropertyMetadataTemplate(@PathVariable String format,
                                                             ServerHttpResponse response) {
        setDownloadHeader(response, "properties_template.", format);
        return writeExcel(format, Flux.empty())
            .map(bufferFactory::wrap)
            .as(response::writeWith);
    }

    @PostMapping("/test")
    public Flux<DeviceInstanceEntity> test() {
        return deviceService.createQuery().fetch();
    }

    private DeviceInstanceEntity toDevice(SimpleDeviceInstanceEntity request, DeviceProductEntity product) {
        DeviceInstanceEntity device = new DeviceInstanceEntity();
        device.setId(request.getId());
        device.setName(request.getName());
        device.setProductId(product.getId());
        device.setProductName(product.getName());
        return device;
    }

    private DeviceStatusEntity toStatus(byte state, boolean online, Long lastMessageTime) {
        DeviceStatusEntity status = new DeviceStatusEntity();
        status.setLastMessageTime(lastMessageTime);
        status.setIsOnline(online);
        status.setStatus(toLegacyStatus(state));
        return status;
    }

    private String toLegacyStatus(byte state) {
        if (state == org.jetlinks.core.device.DeviceState.online) {
            return "online";
        }
        if (state == org.jetlinks.core.device.DeviceState.offline) {
            return "offline";
        }
        if (state == org.jetlinks.core.device.DeviceState.timeout) {
            return "timeout";
        }
        if (state == org.jetlinks.core.device.DeviceState.noActive) {
            return "notActive";
        }
        return "unknown";
    }

    private Mono<String> findDeviceMetadata(String deviceId) {
        return deviceService.findById(deviceId)
            .flatMap(device -> {
                if (device.getDeriveMetadata() != null && !device.getDeriveMetadata().isBlank()) {
                    return Mono.just(device.getDeriveMetadata());
                }
                return productService.findById(device.getProductId()).map(DeviceProductEntity::getMetadata);
            });
    }

    private Mono<DeviceInstanceEntity> assertRowModeHistoryWritable(DeviceInstanceEntity device) {
        return productService
            .findById(device.getProductId())
            .flatMap(product -> {
                String storePolicy = product.getStorePolicy();
                if (!StringUtils.hasText(storePolicy) || ROW_STORE_POLICY.equals(storePolicy)) {
                    return Mono.just(device);
                }
                return Mono.error(new BusinessException(
                    "unsupported store policy for legacy history mutation: " + storePolicy,
                    "error.unsupported_store_policy"));
            });
    }

    private Mono<Void> upsertHistoryProperties(DeviceInstanceEntity device,
                                               PropertyMetadata metadata,
                                               List<SimpleDeviceProperty> propertyList) {
        String table = propertyTable(device);
        return Flux
            .fromIterable(propertyList)
            .concatMap(item -> sqlExecutor
                .update(
                    SqlRequests.prepare(
                        "insert into \"" + table + "\" " +
                            "(\"id\",\"" + ThingsDataConstants.COLUMN_THING_ID + "\",\"" +
                            ThingsDataConstants.COLUMN_TIMESTAMP + "\",\"" +
                            ThingsDataConstants.COLUMN_PROPERTY_ID + "\",\"" +
                            ThingsDataConstants.COLUMN_CREATE_TIME + "\",\"" +
                            ThingsDataConstants.COLUMN_PROPERTY_VALUE + "\",\"" +
                            ThingsDataConstants.COLUMN_PROPERTY_NUMBER_VALUE + "\") " +
                            "values (?,?,?,?,?,?,?) " +
                            "on conflict (\"id\") do update set " +
                            "\"" + ThingsDataConstants.COLUMN_THING_ID + "\" = excluded.\"" + ThingsDataConstants.COLUMN_THING_ID + "\"," +
                            "\"" + ThingsDataConstants.COLUMN_TIMESTAMP + "\" = excluded.\"" + ThingsDataConstants.COLUMN_TIMESTAMP + "\"," +
                            "\"" + ThingsDataConstants.COLUMN_PROPERTY_ID + "\" = excluded.\"" + ThingsDataConstants.COLUMN_PROPERTY_ID + "\"," +
                            "\"" + ThingsDataConstants.COLUMN_CREATE_TIME + "\" = excluded.\"" + ThingsDataConstants.COLUMN_CREATE_TIME + "\"," +
                            "\"" + ThingsDataConstants.COLUMN_PROPERTY_VALUE + "\" = excluded.\"" + ThingsDataConstants.COLUMN_PROPERTY_VALUE + "\"," +
                            "\"" + ThingsDataConstants.COLUMN_PROPERTY_NUMBER_VALUE + "\" = excluded.\"" + ThingsDataConstants.COLUMN_PROPERTY_NUMBER_VALUE + "\"",
                        historyRow(device, metadata, item)))
                .then())
            .then();
    }

    private Object[] historyRow(DeviceInstanceEntity device,
                                PropertyMetadata metadata,
                                SimpleDeviceProperty item) {
        Long timestamp = item.getTimestamp();
        Object value = item.getValue();
        return new Object[]{
            StringUtils.hasText(item.getId()) ? item.getId() : IDGenerator.SNOW_FLAKE_STRING.generate(),
            device.getId(),
            timestamp,
            metadata.getId(),
            timestamp,
            value == null ? null : String.valueOf(value),
            numericValue(metadata, value)
        };
    }

    private Object numericValue(PropertyMetadata metadata, Object value) {
        if (value == null || metadata.getValueType() == null) {
            return null;
        }
        switch (metadata.getValueType().getType()) {
            case "int":
            case "long":
            case "float":
            case "double":
                return value;
            default:
                return null;
        }
    }

    private String propertyTable(DeviceInstanceEntity device) {
        return ThingsDatabaseUtils.createTableName("device_properties_" + device.getProductId());
    }

    private Flux<byte[]> writeExcel(String format, Flux<CustomizePropertyExcelInfo> data) {
        return ReactorExcel
            .<CustomizePropertyExcelInfo>writer(format)
            .headers(propertyHeaders())
            .converter(CustomizePropertyExcelInfo::toMap)
            .writeBuffer(data);
    }

    private List<ExcelHeader> propertyHeaders() {
        return List.of(
            new ExcelHeader("id", "id", CellDataType.STRING),
            new ExcelHeader("name", "name", CellDataType.STRING),
            new ExcelHeader("sortsIndex", "sortsIndex", CellDataType.STRING),
            new ExcelHeader("registerAddr", "registerAddr", CellDataType.STRING),
            new ExcelHeader("registerNum", "registerNum", CellDataType.STRING),
            new ExcelHeader("parseMethod", "parseMethod", CellDataType.STRING),
            new ExcelHeader("functionCode", "functionCode", CellDataType.STRING),
            new ExcelHeader("source", "source", CellDataType.STRING),
            new ExcelHeader("type", "type", CellDataType.STRING),
            new ExcelHeader("valueType", "valueType", CellDataType.STRING),
            new ExcelHeader("scale", "scale", CellDataType.STRING),
            new ExcelHeader("unit", "unit", CellDataType.STRING),
            new ExcelHeader("formula", "formula", CellDataType.STRING)
        );
    }

    private void setDownloadHeader(ServerHttpResponse response, String prefix, String format) {
        String fileName = URLEncoder.encode(prefix + format, StandardCharsets.UTF_8);
        response.getHeaders().set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fileName);
    }
}
