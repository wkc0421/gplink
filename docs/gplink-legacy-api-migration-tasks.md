# gplink1.0 legacy API migration tasks

本文档用于指导 ralphy 将旧项目 `wkc0421/gplink1.0` 中 `standard-manager` 的部分接口迁移到当前项目 `saas-manager`。

## 范围

旧项目来源：

- `gplink1.0/jetlinks-manager/standard-manager/src/main/java/org/gaopu/api/web`

当前项目目标模块：

- `back/jetlinks-community-2.10/jetlinks-community-2.10/jetlinks-manager/saas-manager`

本轮迁移范围：

- `SystemController`
- `ProductController`
- `RuleSceneController`
- `AlarmController`
- `DeviceController`
- 基础兼容 DTO / util / service wrapper

本轮明确不做：

- `ClusterController`
- 所有 ES 相关接口和 ES 实现逻辑
- `DeviceDataController`
- `ProductController` 中 `/index-strategy`
- `DeviceController` 中直接写 ES / 删 ES 历史数据的接口

## 总体实现原则

1. 旧接口路径、HTTP method、入参字段、出参字段保持一致。
2. 新代码放在 `saas-manager` 独立 legacy 包下，建议包名：
   - `gp.saas.legacy.web`
   - `gp.saas.legacy.dto`
   - `gp.saas.legacy.service`
   - `gp.saas.legacy.util`
3. 优先复用当前项目已有 Service，不复制核心业务 Service。
4. 旧项目中只为接口兼容存在的 DTO 和 util 可以复制到 `saas-manager`。
5. 涉及 ES 的接口先不实现；如果必须保留路由，应返回明确的“不支持”响应，但默认建议本轮不注册这些路由。
6. 所有新增 Controller 的基础路径仍使用旧路径 `/api/v1/*`，保证旧客户端无需改 URL。
7. 实现时不要修改当前已有 Controller 的路径，避免和现有 JetLinks 接口冲突。

## Task 0: 基础兼容层

### 目标

补齐 Task 1-6 需要的旧版 DTO、工具类和少量兼容 service wrapper，使后续 Controller 代码保持清晰。

### 需要迁移的 DTO

- `SimpleDeviceInstanceEntity`
- `SimpleDeviceProperty`
- `DeviceStatusEntity`
- `CustomizePropertyExcelInfo`
- `AlarmRuleEntity`
- `TimerReadPropertyRuleEntity`
- `TimerInvokeFunctionEntity`
- `SynchronizationAlarmRecordEntity`
- `DiYaAlarmRecordEntity`

### 需要迁移的 util

- `RuleSceneUtil`
- 与属性元数据导入导出相关的 wrapper/helper

### 本项目实现方式

- DTO 字段、getter/setter、JSON 字段名保持旧项目一致。
- 如果旧 DTO 中引用旧项目包名，迁移后改为 `gp.saas.legacy.dto`。
- `RuleSceneUtil` 应只负责构造旧接口需要的 `SceneRule`，不要改变当前项目 `SceneService`。
- 旧项目中与 ES、Redis latest、统计查询相关的 DTO 和 util 本轮不迁移。

### 验收标准

- `saas-manager` 能编译。
- DTO 不依赖 `standard-manager` 旧包名。
- 没有引入 ES 相关 Service 依赖。

## Task 1: SystemController

### 旧接口

基础路径：`/api/v1/system`

| Method | Path | 入参 | 返回 |
|---|---|---|---|
| GET | `/gc` | query `password` | `Mono<JSONObject>` |
| GET | `/soft-restart` | query `password` | `Mono<JSONObject>` |
| GET | `/memory-analysis` | query `password` | `Mono<JSONObject>` |

### 旧版逻辑

- 固定访问密码为 `yada8888`。
- `/gc`
  - 密码错误时返回 `Mono.empty()`。
  - 密码正确时记录 GC 前内存，执行 `System.gc()` 和 `System.runFinalization()`，返回 GC 前后内存变化。
- `/soft-restart`
  - 密码错误时返回 JSON：`success=false`、`message=无效的访问密码`。
  - 密码正确时执行轻量刷新流程，返回刷新状态、bean 数量、执行时间。
- `/memory-analysis`
  - 密码错误时返回 JSON：`success=false`、`message=无效的访问密码`。
  - 密码正确时返回 JVM 内存、堆内存、非堆内存、内存池、GC、线程、类加载等诊断数据。

### 本项目实现方式

- 在 `gp.saas.legacy.web.SystemController` 新增 Controller。
- 复用 Spring `ApplicationContext`。
- 使用 JDK `ManagementFactory` 获取 JVM 指标。
- 不依赖 ES、Redis、设备服务。
- 保持旧版 JSON key，不做字段重命名。

### 验收标准

- 三个接口路径和旧版一致。
- 密码错误行为和旧版一致。
- 返回 JSON 包含旧版字段。

## Task 2: ProductController 基础接口

### 旧接口

基础路径：`/api/v1/product`

本任务实现：

| Method | Path | 入参 | 返回 |
|---|---|---|---|
| GET | `/{id}` | path `id` | `Mono<DeviceProductEntity>` |
| POST | `/_query` | body `QueryParamEntity` | `Mono<PagerResult<DeviceProductEntity>>` |
| POST | `/_query/no-paging` | body `QueryParamEntity` | `Flux<DeviceProductEntity>` |
| GET | `/{id}/metadata` | path `id` | `Mono<JSONObject>` |
| GET | `/{id}/metadata/properties` | path `id` | `Mono<JSONArray>` |
| GET | `/{id}/metadata/functions` | path `id` | `Mono<JSONArray>` |
| POST | `/all/deploy` | 无 | `Flux<JSONObject>` |
| POST | `/all/undeploy` | 无 | `Flux<Integer>` |

本任务不实现：

| Method | Path | 原因 |
|---|---|---|
| GET | `/index-strategy` | ES 相关，本轮跳过 |

### 旧版逻辑

- `/{id}` 调用 `LocalDeviceProductService.findById`。
- `/_query` 调用 `LocalDeviceProductService.queryPager`。
- `/_query/no-paging` 调用 `LocalDeviceProductService.query`。
- metadata 接口读取 `DeviceProductEntity.metadata`，按 JSON 返回全部物模型、`properties` 或 `functions`。
- `/all/deploy` 查询所有产品，逐个执行部署。
- `/all/undeploy` 查询所有产品，逐个执行取消部署。

### 本项目实现方式

- 新增 `gp.saas.legacy.web.LegacyProductController`。
- 复用当前项目 `LocalDeviceProductService`。
- 复用当前项目设备注册中心部署能力。
- metadata 解析保持旧版 FastJSON 返回结构。
- 不引入 `ElasticSearchIndexManager`。

### 验收标准

- 查询接口返回类型和旧版一致。
- metadata 返回 JSON 数组字段和旧版一致。
- `/index-strategy` 不在本轮新增。

## Task 3: ProductController 属性元数据接口

### 旧接口

基础路径：`/api/v1/product`

| Method | Path | 入参 | 返回 |
|---|---|---|---|
| POST | `/{productId}/property-metadata/import` | path `productId`, query `fileUrl` | `Mono<String>` |
| POST | `/{productId}/property-metadata/update` | path `productId`, body `List<CustomizePropertyExcelInfo>` | `Mono<String>` |
| GET | `/{productId}/property-metadata/list` | path `productId` | `Mono<List<CustomizePropertyExcelInfo>>` |
| GET | `/{productId}/property-metadata/properties.{format}` | path `productId`, `format` | `Mono<Void>` |
| GET | `/property-metadata/properties_template.{format}` | path `format` | `Mono<Void>` |

### 旧版逻辑

- 从 Excel/CSV 导入属性扩展信息。
- 将 `CustomizePropertyExcelInfo` 转为物模型属性。
- 读取产品原 metadata，保留旧有 functions、events、tags。
- 替换或更新 properties。
- 导出接口生成属性元数据文件。
- 模板接口返回属性元数据导入模板。

### 本项目实现方式

- 可以放在 `LegacyProductController` 中，也可以拆成 `LegacyProductMetadataController`。
- 复用 `LocalDeviceProductService` 保存产品 metadata。
- 复用当前项目 `JetLinksDeviceMetadataCodec` 编解码物模型。
- 复用当前项目 `ImportExportService` 或现有 Excel 工具能力。
- `CustomizePropertyExcelInfo` 字段必须和旧版一致。
- 不实现任何 ES 索引刷新或 ES 策略逻辑。

### 验收标准

- 导入、更新后产品 metadata 能被当前设备产品正常读取。
- `list` 返回旧版 `CustomizePropertyExcelInfo` 格式。
- 导出文件列名和旧版一致。

## Task 4: RuleSceneController

### 旧接口

基础路径：`/api/v1/rule/scene`

| Method | Path | 入参 | 返回 |
|---|---|---|---|
| POST | `/` | body `SceneEntity` | `Mono<Integer>` |
| PUT | `/{id}` | path `id`, body `SceneEntity` | `Mono<Integer>` |
| GET | `/{id}` | path `id` | `Mono<SceneEntity>` |
| DELETE | `/{id}` | path `id` | `Mono<Integer>` |
| GET | `/{id}/_enable` | path `id` | `Mono<Void>` |
| GET | `/{id}/_disable` | path `id` | `Mono<Void>` |
| POST | `/property/_read` | body `TimerReadPropertyRuleEntity` | `Mono<SceneEntity>` |
| PUT | `/{id}/property/_read` | path `id`, body `TimerReadPropertyRuleEntity` | `Mono<SceneEntity>` |
| POST | `/function/_invoke` | body `TimerInvokeFunctionEntity` | `Mono<SceneEntity>` |
| PUT | `/{id}/function/_invoke` | path `id`, body `TimerInvokeFunctionEntity` | `Mono<SceneEntity>` |
| POST | `/alarm/_trigger` | body `AlarmRuleEntity` | `Mono<SceneEntity>` |
| PUT | `/{id}/alarm/_trigger` | path `id`, body `AlarmRuleEntity` | `Mono<SceneEntity>` |

### 旧版逻辑

- 基础 CRUD 调用 `SceneService`。
- `_enable` 和 `_disable` 调用 `SceneService.enabled/disabled`。
- 定时读属性：
  - 根据 cron 表达式构造 timer trigger。
  - 根据产品和设备列表查询目标设备。
  - 使用 `RuleSceneUtil` 构造读属性分支。
- 定时调用功能：
  - 根据 cron 表达式构造 timer trigger。
  - 根据产品和设备列表查询目标设备。
  - 使用 `RuleSceneUtil` 构造功能调用分支。
- 告警触发：
  - 根据产品、设备、条件项构造设备触发规则。
  - 创建告警触发分支和恢复分支。
- update 类接口旧版会先禁用场景，再更新规则。

### 本项目实现方式

- 新增 `gp.saas.legacy.web.LegacyRuleSceneController`。
- 复用当前项目 `SceneService`。
- 复用当前项目 `LocalDeviceInstanceService` 查询产品下设备。
- 迁移 `RuleSceneUtil`，保持生成的 `SceneRule` 结构与旧版一致。
- 不改当前项目场景规则核心实现。

### 验收标准

- CRUD、启用、禁用行为与旧版一致。
- 定时读属性、定时调用功能、告警触发生成的 `SceneEntity` 可被当前项目 `SceneService` 保存。
- 旧客户端传入的 DTO 不需要改字段。

## Task 5: AlarmController

### 旧接口

基础路径：`/api/v1/alarm`

| Method | Path | 入参 | 返回 |
|---|---|---|---|
| GET | `/{id}/mqtt` | path `id` | `Mono<Object>` |
| POST | `/mqtt/synchronize` | body `SynchronizationAlarmRecordEntity` | `Mono<Void>` |
| POST | `/config` | body `AlarmConfigEntity` | `Mono<AlarmConfigEntity>` |
| PUT | `/config/{id}` | path `id`, body `AlarmConfigEntity` | `Mono<Integer>` |
| GET | `/config/{id}` | path `id` | `Mono<AlarmConfigEntity>` |
| DELETE | `/config/{id}` | path `id` | `Mono<Integer>` |
| GET | `/config/{id}/_enable` | path `id` | `Mono<Void>` |
| GET | `/config/{id}/_disable` | path `id` | `Mono<Void>` |
| GET | `/config/{id}/_bind` | path `id`, query `ruleId` | `Mono<Integer>` |
| GET | `/config/{id}/_unbind` | path `id`, query `ruleId` | `Mono<Integer>` |

### 旧版逻辑

- `/{id}/mqtt`
  - 根据告警记录 ID 查询告警记录。
  - 调用旧版 `AlarmPublishService.sendMqttAlarmRecord` 推送 MQTT。
- `/mqtt/synchronize`
  - 将同步告警 DTO 转为低压告警记录 DTO。
  - 组装 MQTT payload：
    - `MsgType=MQData`
    - `Style=Report`
    - `Sender=GPLink`
    - `Time=当前时间`
    - `DataObject=[{Key:"Alarm",Value:...}]`
  - topic 为 `IOT/{managerId}/Ctrl`，`managerId` 来自 `deviceId.split("_")[0]`。
- config CRUD 调用 `AlarmConfigService`。
- bind/unbind 调用 `AlarmRuleBindService`。

### 本项目实现方式

- 新增 `gp.saas.legacy.web.LegacyAlarmController`。
- 复用当前项目：
  - `AlarmRecordService`
  - `AlarmConfigService`
  - `AlarmRuleBindService`
  - `SceneService`
- 新增兼容 `LegacyMessagePublishService`，把旧版 MQTT payload 发布逻辑适配到当前项目已有消息发布能力。
- 如果当前项目没有等价 MQTT 发布 Service，先实现 service 接口和空实现，并在文档或 TODO 中标明需要接入真实 MQTT 发布链路。
- 不引入 ES。

### 验收标准

- 告警配置 CRUD、启用、禁用、绑定、解绑可正常调用。
- `/mqtt/synchronize` 生成的 topic 和 payload 与旧版一致。
- 如果 MQTT 发布链路暂未接入，代码必须显式隔离，不影响其他接口编译和运行。

## Task 6: DeviceController 基础接口

### 旧接口

基础路径：`/api/v1/device`

本任务实现：

| Method | Path | 入参 | 返回 |
|---|---|---|---|
| GET | `/{id}` | path `id` | `Mono<DeviceInstanceEntity>` |
| GET | `/{id}/status` | path `id` | `Mono<DeviceStatusEntity>` |
| POST | `/` | body `SimpleDeviceInstanceEntity` | `Mono<DeviceInstanceEntity>` |
| POST | `/{productId}/devices` | path `productId`, body `List<SimpleDeviceInstanceEntity>` | `Flux<DeviceInstanceEntity>` |
| POST | `/{productId}/{orgId}/devices` | path `productId`, `orgId`, body list | `Mono<Integer>` |
| PUT | `/{id}` | path `id`, body `SimpleDeviceInstanceEntity` | `Mono<Integer>` |
| DELETE | `/{id}` | path `id` | `Mono<Integer>` |
| POST | `/product/{productId}` | path `productId`, query `password` | `Mono<Integer>` |
| POST | `/_query` | body `QueryParamEntity` | `Mono<PagerResult<DeviceInstanceEntity>>` |
| POST | `/_query/no-paging` | body `QueryParamEntity` | `Flux<DeviceInstanceEntity>` |
| GET | `/{id}/metadata` | path `id` | `Mono<JSONObject>` |
| GET | `/{id}/metadata/properties` | path `id` | `Mono<JSONArray>` |
| GET | `/{id}/metadata/functions` | path `id` | `Mono<JSONArray>` |
| POST | `/{id}/{functionId}` | path `id`, `functionId`, body `Map<String,Object>` | `Flux<?>` |
| POST | `/{id}/{property}/read` | path `id`, `property` | `Mono<Map<String,Object>>` |
| GET | `/bind` | query `gatewayId`, `deviceId` | `Mono<Boolean>` |
| POST | `/{deviceId}/property-metadata/update` | path `deviceId`, body `List<CustomizePropertyExcelInfo>` | `Mono<String>` |
| GET | `/{deviceId}/property-metadata/list` | path `deviceId` | `Mono<List<CustomizePropertyExcelInfo>>` |
| GET | `/{deviceId}/property-metadata/properties.{format}` | path `deviceId`, `format` | `Mono<Void>` |
| GET | `/property-metadata/properties_template.{format}` | path `format` | `Mono<Void>` |
| POST | `/test` | 无 | `Flux<DeviceInstanceEntity>` |

本任务不实现：

| Method | Path | 原因 |
|---|---|---|
| POST | `/{id}/{property}/history/write` | 历史数据写入，本轮不做 |
| POST | `/{id}/{property}/history/update` | 旧版直接写 ES，本轮不做 |
| DELETE | `/{deviceId}/history/delete` | 旧版直接删 ES，本轮不做 |

### 旧版逻辑

- 创建设备：
  - 将 `SimpleDeviceInstanceEntity` 转为 `DeviceInstanceEntity`。
  - 设置 productId、productName、creator 信息。
  - 插入后执行 deploy/reload。
- 批量创建设备：
  - 遍历入参列表，按产品批量创建设备。
  - 带 `orgId` 的接口额外调用组织绑定。
- 更新设备：
  - 旧版主要更新设备名称。
- 删除设备：
  - 先 unregister，再删除数据库记录。
- 按产品删除：
  - 使用固定密码 `yada8888`。
  - 删除指定产品下全部设备。
- 查询接口：
  - 委托 `LocalDeviceInstanceService`。
- metadata 接口：
  - 优先读取设备派生物模型。
  - 没有派生物模型时回退产品物模型。
- 调用功能：
  - 通过 `DeviceRegistry` 获取设备操作器。
  - 将请求 body 包装成旧版 `input` 结构后调用功能。
- 读取属性：
  - 通过设备操作器读取单个属性。
  - 返回旧版 `Map<String,Object>` 结构。
- 网关绑定：
  - 检查循环依赖。
  - 更新子设备 parentId。
  - 写入 `DeviceConfigKey.parentGatewayId`。
  - 触发协议的 child bind 回调。
- 设备属性元数据：
  - 与产品属性元数据类似，但写入设备派生 metadata。

### 本项目实现方式

- 新增 `gp.saas.legacy.web.LegacyDeviceController`。
- 复用当前项目：
  - `LocalDeviceInstanceService`
  - `LocalDeviceProductService`
  - `DeviceRegistry`
  - `OrganizationService`
  - `ImportExportService`
- 设备 metadata 继续使用当前项目物模型 codec。
- 网关绑定逻辑优先参考当前项目已有 `GatewayDeviceController`。
- 历史数据写入、更新、删除全部跳过，不引入 ES。

### 验收标准

- 基础设备 CRUD 可用。
- 设备创建后可在当前项目中部署/查询。
- metadata 返回字段和旧版一致。
- 网关绑定不破坏当前项目网关设备逻辑。
- ES 历史接口不在本轮实现。

## ralphy 执行建议

建议拆成以下独立任务顺序执行：

1. 新增 legacy DTO / util 基础包，确保项目可编译。
2. 实现 `SystemController`。
3. 实现 `ProductController` 基础查询、metadata、deploy/undeploy，跳过 `/index-strategy`。
4. 实现 `ProductController` 属性元数据导入导出。
5. 实现 `RuleSceneController`。
6. 实现 `AlarmController` 配置类接口。
7. 实现 `AlarmController` MQTT payload 组装，消息发布用兼容 service 隔离。
8. 实现 `DeviceController` 基础 CRUD 和查询。
9. 实现 `DeviceController` metadata、功能调用、属性读取。
10. 实现 `DeviceController` 网关绑定和设备属性元数据。

每个任务完成后必须执行：

- `mvn -pl jetlinks-manager/saas-manager -am compile`
- 如果依赖模块路径不匹配，则在 `back/jetlinks-community-2.10/jetlinks-community-2.10` 下执行对应模块编译。

## 本轮不做接口清单

以下接口后续单独评估，不应混入本轮 ralphy 自动开发：

- `ClusterController` 全部接口。
- `DeviceDataController` 全部接口。
- `ProductController.GET /api/v1/product/index-strategy`
- `DeviceController.POST /api/v1/device/{id}/{property}/history/write`
- `DeviceController.POST /api/v1/device/{id}/{property}/history/update`
- `DeviceController.DELETE /api/v1/device/{deviceId}/history/delete`

原因：

- 当前项目本轮要求不使用 ES。
- 旧版 `DeviceDataController` 和设备历史数据接口强依赖 ES/Redis latest/统计服务。
- 这些接口需要单独设计非 ES 实现，否则无法保证输入输出一致。
