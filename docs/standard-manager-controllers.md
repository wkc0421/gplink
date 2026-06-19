# standard-manager Controller 接口与逻辑总结

本文整理 `jetlinks-manager/standard-manager/src/main/java/org/gaopu/api/web` 中指定 controller 的整体职责、核心依赖、显式接口与注意事项。

统计口径：

- 只记录 controller 源码中直接声明的 `@GetMapping`、`@PostMapping`、`@PutMapping`、`@DeleteMapping`、`@PatchMapping` 接口。
- 不展开 `ReactiveServiceCrudController` 等框架继承产生的隐式 CRUD 接口。
- `org.gaopu.api.web.excel` 下的类仅作为导入导出辅助模型，不作为 controller 单独整理。

## Controller 总览

| Controller | 基础路径 | 主要职责 |
|---|---|---|
| `ApiAuthorizationController` | `/api/v1/authorization` | 简化 token 获取，使用硬编码账号密码构造认证并返回 token |
| `ChangePropertyConfigController` | `/api/v1/change-property/config` | 设备采集项变位配置管理、批量生成、导入导出、缓存重建 |
| `DeviceController` | `/api/v1/device` | 设备实例管理、状态与物模型查询、功能调用、属性读写、历史数据维护 |
| `DeviceDataController` | `/api/v1/device-data` | 设备最新数据、历史数据、事件、趋势聚合与变压器负荷率查询 |
| `MqttDeviceDataSubscriberController` | `/api/v1/mqtt/device-data/subscriber` | MQTT 设备数据订阅配置管理、导入、主题查询、订阅取消 |
| `ProductController` | `/api/v1/product` | 产品查询、产品物模型查询与导入导出、批量部署和注销 |
| `RuleSceneController` | `/api/v1/rule/scene` | 规则场景 CRUD、启停，以及定时读属性、定时调功能、告警规则场景生成 |
| `ScheduledTaskController` | `/api/v1/scheduled-task` | 定时任务 CRUD、启停执行、日志查询，以及采集器维度任务批量创建和启停 |

## ApiAuthorizationController

基础路径：`/api/v1/authorization`

整体职责：提供简化 token 获取接口。接口绕过普通鉴权，校验请求中的硬编码账号密码后，构造 hsweb 的 `SimpleAuthentication`，发布 `AuthorizationSuccessEvent`，再从事件结果中取 token 返回。

核心依赖：

- `ApplicationEventPublisher`：发布授权成功事件，由现有认证体系生成 token。
- `SimpleUser`、`SimpleAuthentication`：手动构造认证主体。
- `AuthorizationSuccessEvent`：承载认证结果和 token。
- `TokenRequestEntity`：承载 `user`、`password`。

接口列表：

| HTTP | 完整路径 | 方法名 | 入参/请求体 | 返回类型 | 功能说明 |
|---|---|---|---|---|---|
| POST | `/api/v1/authorization/token` | `getToken` | Body `TokenRequestEntity{user,password}` | `Mono<String>` | 仅 `admin/yada88` 通过，固定用户 ID 后返回 token |

注意事项：

- 类级接口 `@Authorize(ignore = true)`，无需普通鉴权即可访问。
- 凭据和用户 ID 硬编码，属于高风险内部授权接口。
- 认证失败仅抛 `ValidationException("密码错误")`，未发布授权失败事件。

## ChangePropertyConfigController

基础路径：`/api/v1/change-property/config`

整体职责：管理设备采集项变位配置。配置按 `deviceId + propertyId` 作为唯一键进行新增或覆盖，支持单条 CRUD、批量新增、按产品批量生成、按条件批量删除、Excel/CSV 导入，以及监控配置缓存重建。

核心依赖：

- `ChangePropertyConfigService`：实现查询、upsert、批量写入、按产品生成、删除和缓存重建。
- `ImportExportService`：读取平台文件地址中的导入文件。
- `ReactorExcel`、`FileUtils`、`ChangePropertyConfigImportRow`：处理 Excel/CSV 导入和模板导出。
- `ChangePropertyConfigEntity`：变位配置实体，包含设备、属性、阈值、映射等配置字段。
- `JSONObject`：解析导入行中的 `valueMapping` JSON 对象。

接口列表：

| HTTP | 完整路径 | 方法名 | 入参/请求体 | 返回类型 | 功能说明 |
|---|---|---|---|---|---|
| GET | `/api/v1/change-property/config` | `query` | Query `QueryParamEntity` | `Mono<PagerResult<ChangePropertyConfigEntity>>` | 分页查询变位配置 |
| GET | `/api/v1/change-property/config/{id}` | `getById` | Path `id` | `Mono<ChangePropertyConfigEntity>` | 按 ID 查询变位配置 |
| POST | `/api/v1/change-property/config` | `add` | Body `ChangePropertyConfigEntity` | `Mono<String>` | 按唯一键新增或覆盖变位配置 |
| PUT | `/api/v1/change-property/config/{id}` | `update` | Path `id`，Body `ChangePropertyConfigEntity` | `Mono<Boolean>` | 修改变位配置，并处理唯一键冲突 |
| DELETE | `/api/v1/change-property/config/{id}` | `delete` | Path `id` | `Mono<Boolean>` | 删除配置、基线和缓存字段 |
| POST | `/api/v1/change-property/config/batch` | `batchAdd` | Body `List<ChangePropertyConfigEntity>` | `Mono<List<String>>` | 批量新增或覆盖变位配置 |
| POST | `/api/v1/change-property/config/by-product` | `addByProduct` | Body `ChangePropertyConfigEntity` | `Mono<Map<String,Object>>` | 为指定产品下所有设备生成同一采集项变位配置 |
| POST | `/api/v1/change-property/config/batch-delete` | `batchDelete` | Body `List<String>` | `Mono<Boolean>` | 按 ID 批量删除变位配置 |
| POST | `/api/v1/change-property/config/delete-by-query` | `deleteByQuery` | Body `ChangePropertyConfigEntity` | `Mono<Map<String,Object>>` | 按条件批量删除，至少需要一个条件 |
| DELETE | `/api/v1/change-property/config/cache` | `clearCache` | 无 | `Mono<Map<String,Object>>` | 重建变位监控配置缓存 |
| POST | `/api/v1/change-property/config/import` | `importFromExcelMultipart` | multipart `file` | `Mono<Map<String,Object>>` | 从上传的 xlsx/csv 文件批量导入 |
| POST | `/api/v1/change-property/config/import?fileUrl=...` | `importFromExcelUrl` | Query `fileUrl` | `Mono<Map<String,Object>>` | 从平台可访问文件地址批量导入 |
| GET | `/api/v1/change-property/config/import/template.{format}` | `downloadImportTemplate` | Path `format`，支持 `xlsx/csv` | `Mono<Void>` | 下载批量导入模板 |

注意事项：

- 唯一键是 `deviceId + propertyId`，不是 `productId + deviceId + propertyId`。
- `/by-product` 会对产品下所有设备做批量写入，影响范围较大。
- `/delete-by-query` 是批量删除接口，条件为空时应拒绝。
- `DELETE /cache` 是维护接口，会重建监控索引缓存。
- 导入的 `valueMapping` 必须是 JSON 对象。
- 普通新增和导入流程未在 controller 层显式校验设备是否存在。

## DeviceController

基础路径：`/api/v1/device`

整体职责：提供设备实例层的标准接口，包括设备增删改查、状态查询、批量创建设备和组织绑定、设备物模型查询、设备功能调用、属性读取、历史属性写入/更新/删除、网关绑定，以及设备级物模型导入导出。

核心依赖：

- `LocalDeviceInstanceService`：设备实例 CRUD、部署、注销、详情查询、网关循环依赖检查。
- `LocalDeviceProductService`：查询产品和产品物模型，作为设备无派生物模型时的回退来源。
- `DeviceDataService`：查询设备消息日志，保存属性上报消息。
- `DeviceRegistry`：获取运行态设备、下发属性读取、调用功能、更新元数据、设置父网关。
- `ElasticSearchService`、`ElasticSearchIndexManager`：直接写入、更新、删除 ES 历史属性数据并处理索引策略。
- `OrganizationService`：绑定产品或设备到组织维度。
- `CustomizePropertyExcelInfo`、`ReactorExcel`：设备物模型属性和 Excel/CSV 行之间的转换与导出。

接口列表：

| HTTP | 完整路径 | 方法名 | 入参/请求体 | 返回类型 | 功能说明 |
|---|---|---|---|---|---|
| GET | `/api/v1/device/{id}` | `getDeviceById` | Path `id` | `Mono<DeviceInstanceEntity>` | 查询设备基本信息 |
| GET | `/api/v1/device/{id}/status` | `checkDeviceStatus` | Path `id` | `Mono<DeviceStatusEntity>` | 查询设备状态、在线标识、最近消息时间 |
| POST | `/api/v1/device` | `addDevice` | Body `SimpleDeviceInstanceEntity{id,name,productId}` | `Mono<DeviceInstanceEntity>` | 新增并部署设备，补充创建人和产品名 |
| POST | `/api/v1/device/{productId}/devices` | `addDeviceList` | Path `productId`，Body `List<SimpleDeviceInstanceEntity>` | `Flux<DeviceInstanceEntity>` | 批量新增并部署设备 |
| POST | `/api/v1/device/{productId}/{orgId}/devices` | `addDeviceListToDimension` | Path `productId,orgId`，Body `List<SimpleDeviceInstanceEntity>` | `Mono<Integer>` | 批量新增设备后绑定组织 |
| PUT | `/api/v1/device/{id}` | `updateDevice` | Path `id`，Body `SimpleDeviceInstanceEntity` | `Mono<Integer>` | 更新设备名称等基础信息 |
| DELETE | `/api/v1/device/{id}` | `deleteDevice` | Path `id` | `Mono<Integer>` | 注销并删除设备 |
| POST | `/api/v1/device/product/{productId}` | `deleteDevicesByProductId` | Path `productId`，Query `password` | `Mono<Integer>` | 按产品批量删除设备 |
| POST | `/api/v1/device/_query` | `queryDevicePage` | Body `QueryParamEntity` | `Mono<PagerResult<DeviceInstanceEntity>>` | 分页查询设备 |
| POST | `/api/v1/device/_query/no-paging` | `queryDevices` | Body `QueryParamEntity` | `Flux<DeviceInstanceEntity>` | 不分页查询设备列表 |
| GET | `/api/v1/device/{id}/metadata` | `getDeviceMetadataById` | Path `id` | `Mono<JSONObject>` | 查询设备物模型，无设备派生物模型时回退产品物模型 |
| GET | `/api/v1/device/{id}/metadata/properties` | `getDevicePropertiesById` | Path `id` | `Mono<JSONArray>` | 查询设备属性物模型 |
| GET | `/api/v1/device/{id}/metadata/functions` | `getDeviceFunctionsById` | Path `id` | `Mono<JSONArray>` | 查询设备功能物模型 |
| POST | `/api/v1/device/{id}/{functionId}` | `invokeDeviceFunction` | Path `id,functionId`，Body `Map<String,Object>` | `Flux<?>` | 调用设备功能，参数包装为 `input` |
| POST | `/api/v1/device/{id}/{property}/read` | `readDeviceProperty` | Path `id,property` | `Mono<Map<String,Object>>` | 通过设备注册中心下发属性读取 |
| POST | `/api/v1/device/{id}/{property}/history/write` | `writeDeviceProperty` | Path `id,property`，Body `List<SimpleDeviceProperty>` | `Flux<Void>` | 构造属性上报消息并写入历史数据 |
| POST | `/api/v1/device/{id}/{property}/history/update` | `updateDeviceProperty` | Path `id,property`，Body `List<SimpleDeviceProperty>` | `Mono<Void>` | 插入或更新 ES 历史属性，按月写入 `properties_{productId}_{yyyy-M}` |
| DELETE | `/api/v1/device/{deviceId}/history/delete` | `deleteDeviceHistoryData` | Path `deviceId`，Query `id,timestamp` | `Mono<Long>` | 按记录 ID 删除单条 ES 历史属性数据 |
| GET | `/api/v1/device/bind` | `bindDevice` | Query `gatewayId,deviceId` | `Mono<Boolean>` | 绑定子设备到网关并通知协议 |
| POST | `/api/v1/device/{deviceId}/property-metadata/update` | `updateDevicePropertyMetadata` | Path `deviceId`，Body `List<CustomizePropertyExcelInfo>` | `Mono<String>` | 更新设备派生属性物模型，保留原 functions/events/tags |
| GET | `/api/v1/device/{deviceId}/property-metadata/list` | `getDevicePropertyMetadataList` | Path `deviceId` | `Mono<List<CustomizePropertyExcelInfo>>` | 将设备属性物模型转换为 Excel 行结构 |
| GET | `/api/v1/device/{deviceId}/property-metadata/properties.{format}` | `downloadExportDevicePropertyMetadata` | Path `deviceId,format`，支持 `xlsx/csv` | `Mono<Void>` | 下载设备属性物模型文件 |
| GET | `/api/v1/device/property-metadata/properties_template.{format}` | `downloadExportDevicePropertyMetadataTemplate` | Path `format`，支持 `xlsx/csv` | `Mono<Void>` | 下载设备属性物模型空模板 |
| POST | `/api/v1/device/test` | `test` | 无 | `Flux<DeviceInstanceEntity>` | 查询分类 `-4-` 下产品的设备，测试接口 |

注意事项：

- `addDeviceListToDimension` 标记了 `@Authorize(ignore = true)`。
- `deleteDevicesByProductId` 使用硬编码密码 `yada8888`。
- `checkDeviceStatus` 读取消息日志首条记录，设备无消息时存在空列表风险。
- `addDeviceList` 的路径 `productId` 和请求体中的 `productId` 可能不一致。
- `history/update` 内部部分错误通过 `onErrorResume` 吞掉。
- `/test` 是明显测试接口。

## DeviceDataController

基础路径：`/api/v1/device-data`

整体职责：提供设备数据查询能力，覆盖 ES 和 Redis 最新值、属性历史、事件历史、历史明细自动降采样、区间差值、趋势聚合、直接聚合查询，以及变压器负荷率查询。

核心依赖：

- `DeviceDataService`：查询 ES 最新数据、属性历史、事件历史和聚合数据。
- `ReactiveRedisOperations<String,String>`：读取 `gplink:device:latest:{deviceId}` 最新值缓存。
- `LocalDeviceInstanceService`、`LocalDeviceProductService`：解析设备、产品和物模型信息。
- `DeviceDataStatisticService`：计算区间差值、趋势数据和变压器负荷率。
- `ReactiveRepository<DeviceTagEntity,String>`：未传 `capacity` 时读取设备标签中的容量配置。
- `DeviceLatestRedisConstants`：解析 Redis JSON 或旧格式 `ts|value`。
- `SpecialStatusTagEnum`：对 `_io_status`、`_dev_status` 等特殊状态做展示覆盖。
- `JetLinksDataTypeCodecs`、`DataTypes`：按物模型数据类型生成 `formatValue`。

接口列表：

| HTTP | 完整路径 | 方法名 | 入参/请求体 | 返回类型 | 功能说明 |
|---|---|---|---|---|---|
| GET | `/api/v1/device-data/{deviceId}/properties/es` | `getDeviceDataFromES` | Path `deviceId` | `Flux<DeviceProperty>` | 从 ES 查询设备所有属性最新值 |
| GET | `/api/v1/device-data/{deviceId}/{property}/es` | `getDeviceDataFromES` | Path `deviceId,property` | `Flux<DeviceProperty>` | 从 ES 查询设备单属性最新值 |
| POST | `/api/v1/device-data/devices/properties/es` | `getDeviceDataFromES` | Body `DeiceDataRequestEntity{name,devices,properties,aggregationRequest}` | `Flux<DeviceDataEntity>` | 多设备批量查询 ES 最新属性 |
| POST | `/api/v1/device-data/devices/properties/redis` | `getDeviceDataFromRedisNativeFormat` | Body `DeiceDataRequestEntity` | `Flux<DeviceDataEntity>` | 多设备从 Redis 查询最新属性并按物模型格式化 |
| GET | `/api/v1/device-data/{deviceId}/properties/redis` | `getDeviceDataFromRedisNativeFormat` | Path `deviceId` | `Flux<DeviceProperty>` | 查询单设备 Redis 最新全部属性 |
| GET | `/api/v1/device-data/{deviceId}/{property}/redis` | `getDeviceDataFromRedisNativeFormat` | Path `deviceId,property` | `Mono<DeviceProperty>` | 查询单设备 Redis 最新单属性 |
| POST | `/api/v1/device-data/{deviceId}/{property}/history/test` | `getHistoryDeviceDataPage` | Path `deviceId,property`，Body `QueryParamEntity` | `Mono<PagerResult<DeviceProperty>>` | 原始分页历史查询 |
| POST | `/api/v1/device-data/{deviceId}/{property}/history/no-paging/test` | `getHistoryDeviceData` | Path `deviceId,property`，Body `QueryParamEntity` | `Flux<DeviceProperty>` | 原始不分页历史查询 |
| POST | `/api/v1/device-data/{deviceId}/{property}/history` | `getTestHistoryDeviceDataPage` | Path `deviceId,property`，Body `QueryParamEntity` | `Mono<PagerResult<DeviceProperty>>` | 分页历史查询，并补充 `numberValue/formatValue` |
| POST | `/api/v1/device-data/{deviceId}/{property}/history/no-paging` | `getTestHistoryDeviceData` | Path `deviceId,property`，Body `Map<String,Object>` | `Flux<DeviceProperty>` | 不分页历史查询，超过 2 天自动聚合降采样并超时重试一次 |
| POST | `/api/v1/device-data/{deviceId}/event/{event}/history` | `getHistoryDeviceEvent` | Path `deviceId,event`，Body `QueryParamEntity` | `Flux<DeviceEvent>` | 查询设备事件历史 |
| POST | `/api/v1/device-data/devices/interval` | `getDeviceIntervalData` | Query `startTime,endTime`，Body `DeiceDataRequestEntity` | `Flux<DeviceIntervalDataEntity>` | 查询多设备单属性区间起止值 |
| POST | `/api/v1/device-data/devices/trend/{type}/history` | `getDeviceIntervalDataByType` | Path `type`，Query `startTime,endTime`，Body `DeiceDataRequestEntity` | `Flux<List<DeviceIntervalDataEntity>>` | 旧版按类型查询趋势区间数据 |
| POST | `/api/v1/device-data/devices/interval/{type}/history` | `getDeviceTrendDataByType` | Path `type`，Query `startTime,endTime,limit`，Body `DeiceDataRequestEntity` | `Flux<List<DeviceIntervalDataEntity>>` | 新版趋势区间，支持 `5m/15m/30m/day/month/year` |
| POST | `/api/v1/device-data/devices/interval/{type}/history/test` | `getDeviceTrendDataByTypeTest` | Path `type`，Query `startTime,endTime`，Body `DeiceDataRequestEntity` | `Flux<List<DeviceIntervalDataEntity>>` | 测试趋势接口，仅支持 `day/month/year` |
| POST | `/api/v1/device-data/devices/agg/_query` | `getDeviceAggregationData` | Body `DeiceDataRequestEntity`，需单属性和 `aggregationRequest` | `Flux<Map<String,List<DeviceAggregationDataEntity>>>` | 直接执行 ES 聚合查询 |
| POST | `/api/v1/device-data/transformers/load/rate` | `getTransformerLoadRate` | Query `timestamp,capacity?`，Body `DeiceDataRequestEntity` | `Flux<TransformerDataEntity>` | 查询当前变压器负荷率 |
| POST | `/api/v1/device-data/transformers/load/rate/history` | `getTransformerHourlyLoadRate` | Query `startTime,endTime,capacity?`，Body `DeiceDataRequestEntity` | `Flux<Map<String,List<TransformerDataEntity>>>` | 查询变压器小时负荷率历史 |

注意事项：

- 请求实体类源码名是 `DeiceDataRequestEntity`，拼写不是 `DeviceDataRequestEntity`。
- 多设备 ES 查询直接访问 `properties.isEmpty()`，`properties=null` 时可能 NPE。
- `/history/test`、`/history/no-paging/test`、`/devices/interval/{type}/history/test` 是测试或原始接口。
- Redis 接口使用当前原生 `formatValue` 逻辑，替换了源码中注释掉的旧单位转换/枚举映射逻辑。
- `/history/no-paging` 并不总是原始明细，区间超过 2 天会自动聚合降采样。
- 变压器负荷率实际按属性 `S / capacity` 计算。

## MqttDeviceDataSubscriberController

基础路径：`/api/v1/mqtt/device-data/subscriber`

整体职责：管理 MQTT 设备数据订阅配置，支持单条和批量增改删、Excel/CSV 导入、订阅主题查询，以及按设备取消订阅。新增和批量新增会把订阅归一为 `REPORT + enabled`，校验设备与产品匹配，并按 `productId + deviceId + subscriber + subscribeType` 做覆盖式 upsert，随后刷新订阅转发缓存。

核心依赖：

- `MqttDeviceDataSubscriberService`：负责 CRUD、批量 upsert、缓存刷新、主题查询和属性消息转发。
- `LocalDeviceInstanceService`：校验设备存在以及设备产品归属。
- `ImportExportService`：读取平台文件地址导入内容。
- `ReactorExcel`、`FileUtils`、`DataBufferUtils`、`MqttSubscriberImportRow`：处理上传文件、模板和导入行转换。
- `Authentication`：获取当前登录用户作为导入订阅者。
- `MqttDeviceDataSubscribeEntity`：保存订阅配置并生成上报、读取回复等主题。

接口列表：

| HTTP | 完整路径 | 方法名 | 入参/请求体 | 返回类型 | 功能说明 |
|---|---|---|---|---|---|
| GET | `/api/v1/mqtt/device-data/subscriber` | `query` | Query `QueryParamEntity` | `Mono<PagerResult<MqttDeviceDataSubscribeEntity>>` | 分页查询 MQTT 设备数据订阅者 |
| GET | `/api/v1/mqtt/device-data/subscriber/{id}` | `getById` | Path `id` | `Mono<MqttDeviceDataSubscribeEntity>` | 按 ID 查询订阅配置，不存在抛业务异常 |
| POST | `/api/v1/mqtt/device-data/subscriber` | `add` | Body `MqttDeviceDataSubscribeEntity` | `Mono<String>` | 新增或覆盖订阅；`deviceId="*"` 跳过设备校验 |
| PUT | `/api/v1/mqtt/device-data/subscriber/{id}` | `update` | Path `id`，Body `MqttDeviceDataSubscribeEntity` | `Mono<Boolean>` | 校验后合并非空字段并刷新缓存 |
| DELETE | `/api/v1/mqtt/device-data/subscriber/{id}` | `delete` | Path `id` | `Mono<Boolean>` | 物理删除订阅，通知缓存时临时置为 disabled |
| POST | `/api/v1/mqtt/device-data/subscriber/batch` | `batchAdd` | Body `List<MqttDeviceDataSubscribeEntity>` | `Mono<List<String>>` | 批量去重、校验设备并 upsert |
| POST | `/api/v1/mqtt/device-data/subscriber/import` | `importFromExcelMultipart` | multipart `file` | `Mono<Map<String,Object>>` | 当前登录用户作为 subscriber，从 xlsx/csv 上传文件导入 |
| GET | `/api/v1/mqtt/device-data/subscriber/import/template.{format}` | `downloadImportTemplate` | Path `format`，支持 `xlsx/csv` | `Mono<Void>` | 下载 MQTT 订阅批量导入模板 |
| POST | `/api/v1/mqtt/device-data/subscriber/import?fileUrl=...` | `importFromExcelUrl` | Query `fileUrl` | `Mono<Map<String,Object>>` | 从平台可访问文件地址导入 |
| DELETE | `/api/v1/mqtt/device-data/subscriber/batch` | `batchDelete` | Body `List<String>` | `Mono<Boolean>` | 按 ID 批量删除订阅 |
| GET | `/api/v1/mqtt/device-data/subscriber/product/{productId}/devices` | `getByProductId` | Path `productId`，Query `deviceId?` | `Mono<List<MqttDeviceDataSubscribeEntity>>` | 按产品或产品+设备查询订阅 |
| GET | `/api/v1/mqtt/device-data/subscriber/device/{deviceId}` | `getByDeviceId` | Path `deviceId` | `Mono<List<MqttDeviceDataSubscribeEntity>>` | 按设备 ID 查询订阅 |
| POST | `/api/v1/mqtt/device-data/subscriber/{id}/test` | `testSubscription` | Path `id` | `Mono<String>` | 返回该订阅生成的主题 |
| GET | `/api/v1/mqtt/device-data/subscriber/subscribed-topics` | `getSubscribedTopics` | 无 | `Mono<List<Map<String,Object>>>` | 查询当前服务缓存中的已订阅主题 |
| GET | `/api/v1/mqtt/device-data/subscriber/all-topics` | `getAllConfiguredTopics` | 无 | `Mono<List<Map<String,Object>>>` | 查询所有配置主题及缓存状态 |
| POST | `/api/v1/mqtt/device-data/subscriber/cancel-by-device` | `cancelByDevice` | 参数 `productId,deviceId` | `Mono<Map<String,Object>>` | 按产品和设备删除对应订阅 |
| POST | `/api/v1/mqtt/device-data/subscriber/cancel-by-device-list` | `cancelByDeviceList` | Body `List<String>` | `Mono<Map<String,Object>>` | 按设备 ID 列表批量取消订阅 |

注意事项：

- 导入会把文件内容读入内存，超大文件有内存压力。
- 导入行中的 `subscribeType` 实际会固定为 `REPORT`。
- 新增和更新会强制 `enabled`。
- `deviceId="*"` 表示产品级通配订阅。
- `/subscribed-topics` 标记了 `@Authorize(ignore = true)`。
- `cancelByDevice` 参数未显式标注 `@RequestParam`，依赖 Spring 参数名解析。
- 批量取消的异常会被包装成 `success=false` 的 200 响应。

## ProductController

基础路径：`/api/v1/product`

整体职责：提供产品基础查询、产品物模型查询、产品属性物模型 Excel/CSV 导入更新和导出、批量部署或注销所有产品，以及 ES 索引策略查询。

核心依赖：

- `LocalDeviceProductService`：产品查询、物模型保存、产品部署和注销。
- `ElasticSearchIndexManager`：查询 ES 索引策略。
- `ImportExportService`、`FileUtils`、`ReactorExcel`：读取导入文件并导出 Excel/CSV。
- `CustomizePropertyExcelInfo`、`CustomizePropertyWrapper`：Excel 行和 JetLinks 属性物模型之间转换。
- `JetLinksDeviceMetadataCodec`、`SimpleDeviceMetadata`：解码旧物模型，替换 properties 并保留 functions/events/tags。
- `DeviceRegistry`：同步产品 metadata 配置到注册中心。

接口列表：

| HTTP | 完整路径 | 方法名 | 入参/请求体 | 返回类型 | 功能说明 |
|---|---|---|---|---|---|
| GET | `/api/v1/product/{id}` | `getProductById` | Path `id` | `Mono<DeviceProductEntity>` | 查询产品基本信息 |
| POST | `/api/v1/product/_query` | `queryProductPage` | Body `QueryParamEntity` | `Mono<PagerResult<DeviceProductEntity>>` | 分页查询产品 |
| POST | `/api/v1/product/_query/no-paging` | `queryProducts` | Body `QueryParamEntity` | `Flux<DeviceProductEntity>` | 不分页查询产品 |
| GET | `/api/v1/product/{id}/metadata` | `getProductMetadataById` | Path `id` | `Mono<JSONObject>` | 查询产品完整物模型 |
| GET | `/api/v1/product/{id}/metadata/properties` | `getProductPropertiesById` | Path `id` | `Mono<JSONArray>` | 查询产品属性物模型 |
| GET | `/api/v1/product/{id}/metadata/functions` | `getProductFunctionsById` | Path `id` | `Mono<JSONArray>` | 查询产品功能物模型 |
| GET | `/api/v1/product/index-strategy` | `getIndexStrategy` | Query `index` | `Mono<JSONObject>` | 查询 ES 索引策略 |
| POST | `/api/v1/product/all/deploy` | `deployAllProduct` | 无 | `Flux<JSONObject>` | 激活所有产品，逐条返回结果 |
| POST | `/api/v1/product/all/undeploy` | `undeployAllProduct` | 无 | `Flux<Integer>` | 注销所有产品 |
| POST | `/api/v1/product/{productId}/property-metadata/import` | `importPropertyMetadataByExcel` | Path `productId`，Query `fileUrl` | `Mono<String>` | 从 csv/xlsx 文件导入属性物模型 |
| POST | `/api/v1/product/{productId}/property-metadata/update` | `updateProductPropertyMetadata` | Path `productId`，Body `List<CustomizePropertyExcelInfo>` | `Mono<String>` | 更新产品属性物模型，并同步注册中心配置 |
| GET | `/api/v1/product/{productId}/property-metadata/list` | `getProductPropertyMetadataList` | Path `productId` | `Mono<List<CustomizePropertyExcelInfo>>` | 将产品属性物模型转换为 Excel 行结构 |
| GET | `/api/v1/product/{productId}/property-metadata/properties.{format}` | `downloadExportPropertyMetadata` | Path `productId,format`，支持 `xlsx/csv` | `Mono<Void>` | 下载产品属性物模型文件 |
| GET | `/api/v1/product/property-metadata/properties_template.{format}` | `downloadExportPropertyMetadataTemplate` | Path `format`，支持 `xlsx/csv` | `Mono<Void>` | 下载产品属性物模型空模板 |

注意事项：

- `property-metadata/import` 标注为 `@QueryAction`，但实际会修改产品 metadata。
- 导入和更新会整体替换 properties，仅保留原 functions/events/tags。
- `/all/deploy` 和 `/all/undeploy` 是全量操作，没有过滤条件。
- 部分 metadata 查询未显式做 `notFound` 转换。
- `/index-strategy` 偏运维诊断接口。

## RuleSceneController

基础路径：`/api/v1/rule/scene`

整体职责：管理规则引擎场景 `SceneEntity`，并提供三类标准化场景构建接口：定时读取设备属性、定时调用设备功能、设备属性触发告警规则。

核心依赖：

- `SceneService`：场景 CRUD、启停，以及 `createScene`、`updateScene`。
- `RuleSceneUtil`：生成 timer/device trigger、options、device action、告警触发和解除分支、selector。
- `LocalDeviceInstanceService`：解析产品下全部设备或固定设备列表。
- `FastBeanCopier`：将请求字段复制到已有场景实体。
- `TimerReadPropertyRuleEntity`、`TimerInvokeFunctionEntity`、`AlarmRuleEntity`：承载三类专用规则构建请求。
- `ErrorUtils.notFound`：生成场景不存在错误。

接口列表：

| HTTP | 完整路径 | 方法名 | 入参/请求体 | 返回类型 | 功能说明 |
|---|---|---|---|---|---|
| POST | `/api/v1/rule/scene` | `createAlarmScene` | Body `SceneEntity` | `Mono<Integer>` | 直接插入规则场景 |
| PUT | `/api/v1/rule/scene/{id}` | `updateAlarmScene` | Path `id`，Body `SceneEntity` | `Mono<Integer>` | 查询后复制字段并更新规则场景 |
| GET | `/api/v1/rule/scene/{id}` | `getAlarmSceneById` | Path `id` | `Mono<SceneEntity>` | 按 ID 查询规则场景 |
| DELETE | `/api/v1/rule/scene/{id}` | `deleteAlarmSceneById` | Path `id` | `Mono<Integer>` | 按 ID 删除规则场景 |
| GET | `/api/v1/rule/scene/{id}/_enable` | `enableSceneById` | Path `id` | `Mono<Void>` | 启用规则场景 |
| GET | `/api/v1/rule/scene/{id}/_disable` | `disableSceneById` | Path `id` | `Mono<Void>` | 禁用规则场景 |
| POST | `/api/v1/rule/scene/property/_read` | `createReadPropertyTimer` | Body `TimerReadPropertyRuleEntity` | `Mono<SceneEntity>` | 创建 cron 定时读属性场景 |
| PUT | `/api/v1/rule/scene/{id}/property/_read` | `updateReadPropertyTimer` | Path `id`，Body `TimerReadPropertyRuleEntity` | `Mono<SceneEntity>` | 先禁用再更新定时读属性场景 |
| POST | `/api/v1/rule/scene/function/_invoke` | `createInvokeFunctionTimer` | Body `TimerInvokeFunctionEntity` | `Mono<SceneEntity>` | 创建 cron 定时调用功能场景 |
| PUT | `/api/v1/rule/scene/{id}/function/_invoke` | `updateInvokeFunctionTimer` | Path `id`，Body `TimerInvokeFunctionEntity` | `Mono<SceneEntity>` | 先禁用再更新定时调用功能场景 |
| POST | `/api/v1/rule/scene/alarm/_trigger` | `createAlarmRule` | Body `AlarmRuleEntity` | `Mono<SceneEntity>` | 创建设备属性上报告警规则，包含触发和解除分支 |
| PUT | `/api/v1/rule/scene/{id}/alarm/_trigger` | `updateAlarmRule` | Path `id`，Body `AlarmRuleEntity` | `Mono<SceneEntity>` | 先禁用再更新告警规则 |

注意事项：

- `_enable`、`_disable` 使用 GET 但有状态副作用。
- 三类专用更新接口会先调用 `disabled(id)`，源码中未显式重新启用。
- `deviceIds` 为空字符串表示产品下全部设备，非空时按 `;` 分隔固定设备列表。
- DTO 中存在校验注解，但 controller 方法没有显式 `@Valid`。

## ScheduledTaskController

基础路径：`/api/v1/scheduled-task`

整体职责：管理自定义定时任务配置、启停、手动执行、执行日志查询、错误计数重置，并支持按采集器子设备批量生成或启停采集任务。

核心依赖：

- `ScheduledTaskConfigService`：任务创建、更新、删除、启停、重置错误计数，服务层负责 cron 校验和下次执行时间计算。
- `ScheduledTaskExecutionService`：手动执行读属性或调功能任务。
- `ScheduledTaskExecutionLogService`：分页查询任务执行日志。
- `LocalDeviceInstanceService`：查询采集器下子设备，用于生成采集器任务。
- `ScheduledTaskConfigEntity`、`CollectorScheduledTaskEntity`、`CollectorTaskBatchRequest`：承载任务配置、批量创建和批量启停请求。

接口列表：

| HTTP | 完整路径 | 方法名 | 入参/请求体 | 返回类型 | 功能说明 |
|---|---|---|---|---|---|
| POST | `/api/v1/scheduled-task` | `createTask` | Body `ScheduledTaskConfigEntity` | `Mono<ScheduledTaskConfigEntity>` | 创建定时任务 |
| POST | `/api/v1/scheduled-task/_query` | `queryTasks` | Body `QueryParamEntity` | `Mono<PagerResult<ScheduledTaskConfigEntity>>` | 分页查询定时任务 |
| GET | `/api/v1/scheduled-task/{id}` | `getTask` | Path `id` | `Mono<ScheduledTaskConfigEntity>` | 按 ID 查询定时任务 |
| PUT | `/api/v1/scheduled-task/{id}` | `updateTask` | Path `id`，Body `ScheduledTaskConfigEntity` | `Mono<ScheduledTaskConfigEntity>` | 更新定时任务后重新查询返回 |
| DELETE | `/api/v1/scheduled-task/{id}` | `deleteTask` | Path `id` | `Mono<Void>` | 删除定时任务 |
| POST | `/api/v1/scheduled-task/{id}/enable` | `enableTask` | Path `id` | `Mono<Void>` | 启用任务，写入 `status=1` |
| POST | `/api/v1/scheduled-task/{id}/disable` | `disableTask` | Path `id` | `Mono<Void>` | 禁用任务，写入 `status=0` |
| POST | `/api/v1/scheduled-task/{id}/execute` | `executeTask` | Path `id` | `Mono<Void>` | 查询任务并立即执行 |
| POST | `/api/v1/scheduled-task/{id}/execution-logs/_query` | `queryExecutionLogs` | Path `id`，Body `QueryParamEntity` | `Mono<PagerResult<ScheduledTaskExecutionLogEntity>>` | 按 `taskId` 过滤并按 `executionTime desc` 查询日志 |
| POST | `/api/v1/scheduled-task/{id}/reset-error-count` | `resetErrorCount` | Path `id` | `Mono<Void>` | 将任务错误计数重置为 0 |
| POST | `/api/v1/scheduled-task/{collectorId}/create` | `createTaskByCollectorId` | Path `collectorId`，Body `ScheduledTaskConfigEntity` | `Mono<ScheduledTaskConfigEntity>` | 查询采集器子设备，把子设备 ID 用 `;` 写入任务后创建 |
| POST | `/api/v1/scheduled-task/collectors/create` | `createTaskByCollectorId` | Body `CollectorScheduledTaskEntity{collectorIdList,cronExpression,properties}` | `Flux<ScheduledTaskConfigEntity>` | 为多个采集器生成 `READ_PROPERTY` 任务 |
| POST | `/api/v1/scheduled-task/collectors/batch-enable` | `batchEnableTasksByCollectorIds` | Body `CollectorTaskBatchRequest{collectorIdList,taskName?}` | `Mono<Map<String,Object>>` | 按任务名模式匹配采集器任务并批量启用 |
| POST | `/api/v1/scheduled-task/collectors/batch-disable` | `batchDisableTasksByCollectorIds` | Body `CollectorTaskBatchRequest{collectorIdList,taskName?}` | `Mono<Map<String,Object>>` | 按任务名模式匹配采集器任务并批量禁用 |

注意事项：

- 类级标记了 `@Authorize(ignore = true)`。
- 启停、执行、重置错误计数都有写入或运行时副作用。
- 采集器批量启停依赖任务名 `定时读取采集器{collectorId}%` 模糊匹配，不是稳定外键。
- 采集器无子设备时返回空结果，而不是错误。
