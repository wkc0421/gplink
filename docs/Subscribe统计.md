# @Subscribe 事件订阅统计

> 统计时间: 2026-04-10
> 平台: JetLinks Community IoT
> 注解来源: `org.jetlinks.community.gateway.annotation.Subscribe`

---

## 一、standard-manager 模块 (org.gaopu.api)

### 1. MessagePublishService

**文件**: `jetlinks-manager/standard-manager/src/main/java/org/gaopu/api/service/MessagePublishService.java`

#### 1.1 redisPublisher - 属性消息 Redis 缓存
```java
@Subscribe(topics = {
    "/device/*/*/message/property/report",
    "/device/*/*/message/property/read/reply"
})
public Mono<Integer> redisPublisher(DeviceMessage msg)
```
**功能**: 订阅所有设备属性上报/读属性回复消息，通过 Sinks 多播到本地处理管道，写入 Redis 最新值缓存（Hash 结构，TTL 7天），带内存压力保护和熔断器。

**处理流程**:
- 消息 -> Sinks 多播器 -> processMessage -> 内存压力检查 -> buildHash -> writeToRedis
- Redis Key: `device:latest:{deviceId}`
- 值编码: `timestamp:value`

---

#### 1.2 onlineMessageHandler - 上下线消息转发 MQTT
```java
@Subscribe(topics = {
    "/device/*/*/online",
    "/device/*/*/offline",
})
public Mono<Void> onlineMessageHandler(DeviceMessage msg)
```
**功能**: 订阅设备上下线事件，构造标准 MQTT 消息 payload，通过 GP_MQTT客户端 发布到 MQTT 主题。

**Topic 格式**: `GPLink/{productId}/{deviceId}/{msgType}` (ONLINE/OFFLINE)

---

### 2. AlarmSubscribeService

**文件**: `jetlinks-manager/standard-manager/src/main/java/org/gaopu/api/service/AlarmSubscribeService.java`

#### 2.1 alarmEventMessageHandler - 设备端告警事件处理
```java
@Subscribe(topics = {
    "/device/*/*/message/event/warning"
})
public Mono<Void> alarmEventMessageHandler(DeviceMessage msg)
```
**功能**: 接收设备端上报的告警事件（event/warning），解析不同格式的告警数据（三种格式：AlarmId、Alarm_Log_Id、alarmId），通过 MQTT 转发告警信息。

**支持格式**:
- **格式一** (AlarmId): 包含 AlarmId, AlarmMsg, AlarmGradeId, AlarmTime, AlarmStatus, AlarmRecovery, HiddenDanger, FunId, AlarmCurrVal, ConfirmTime, RecoTime 等字段
- **格式二** (Alarm_Log_Id): 包含 Alarm_Log_Id, Alarm_Msg, Alarm_Level_Id, Alarm_Time, Alarm_Release, Release_Time 等字段
- **格式三** (alarmId): 包含 alarmId, status, desc, level, point, AlarmStart, AlarmEnd, AlarmGenValue, AlarmEndValue 等字段

---

#### 2.2 alarmTriggerMessageHandler - 系统告警触发处理
```java
@Subscribe(topics = {
    "/alarm/*/*/*/record"
})
public Mono<Void> alarmTriggerMessageHandler(AlarmHistoryInfo alarm)
```
**功能**: 订阅告警触发记录事件（由规则引擎产生），解析 AlarmHistoryInfo，补充设备信息后通过 MQTT 转发。

**处理**: 从 alarmInfo JSON 中解析 property 和 value（查找 `_current` 结尾字段），查询真实 AlarmRecordEntity 获取 ID。

---

#### 2.3 alarmRelieveMessageHandler - 告警解除处理
```java
@Subscribe(topics = {
    "/alarm/*/*/*/relieve"
})
public Mono<Void> alarmRelieveMessageHandler(AlarmHistoryInfo alarm)
```
**功能**: 订阅告警解除事件，将 isAlarming 设为 false，解析 property/value 后通过 MQTT 转发。

---

### 3. SpecialMessagePublishHandler

**文件**: `jetlinks-manager/standard-manager/src/main/java/org/gaopu/api/handler/SpecialMessagePublishHandler.java`

#### 3.1 reportYDGATEPropertyMessageHandler - YDGATE 康海管理机上下线同步
```java
@Subscribe(topics = {
    "/device/YD2037Y/*/message/property/report",
    "/device/YD2037Y/*/message/property/read/reply",
})
public Mono<Void> reportYDGATEPropertyMessageHandler(DeviceMessage msg)
```
**功能**: 针对 YDGATE 康海管理机设备，解析属性中的 `_io_status`:
- `_io_status = 0.0` -> 发送离线消息
- `_io_status = 1.0` -> 发送在线消息

---

#### 3.2 reportYDGWPropertyMessageHandler - YDGW 雅达管理机上下线同步
```java
@Subscribe(topics = {
    "/device/YD2037YMB/*/message/property/report",
    "/device/YD2037YMB/*/message/property/read/reply",
    "/device/mb_40000_child/*/message/property/report",
    "/device/mb_40000_child/*/message/property/read/reply",
})
public Mono<Void> reportYDGWPropertyMessageHandler(DeviceMessage msg)
```
**功能**: 针对 YDGW 雅达管理机设备，解析属性中的 `_dev_status`:
- `_dev_status = 1.0` -> 发送离线
- `_dev_status = 0.0` -> 发送在线

若是 ReadPropertyMessageReply，还会生成一条仅含 `_dev_status` 的系统属性上报，触发规则引擎。

---

### 4. DeviceValueHandler

**文件**: `jetlinks-manager/standard-manager/src/main/java/org/gaopu/api/handler/DeviceValueHandler.java`

#### 4.1 saveDeviceValue - 设备值持久化
```java
@Subscribe(topics = {
    "/device/model_creator/*/message/property/report",
    "/device/model_creator/*/message/property/read/reply",
    "/device/YD2037Y/*/message/property/report",
    "/device/YD2037Y/*/message/property/read/reply"
})
public Mono<Void> saveDeviceValue(DeviceMessage msg)
```
**功能**: 订阅特定产品设备的属性上报/读属性回复，将属性值解析后批量保存到 DeviceValueEntity（设备值记录表）。

**ID 格式**: `{deviceId}.{propertyId}`
**字段**: value, timestamp

---

## 二、saas-manager 模块 (org.jetlinks.community.app)

### 5. XBAppService

**文件**: `jetlinks-manager/saas-manager/src/main/java/org/jetlinks/community/app/service/XBAppService.java`

#### 5.1 (活跃订阅，仅注释记录)
```java
// @Subscribe(topics = { ... }) // 已注释
public Mono<Void> ...
```
**说明**: 部分订阅已被注释，详情需查看源码。

---

### 6. RentDeviceMessageHandler

**文件**: `jetlinks-manager/saas-manager/src/main/java/org/jetlinks/community/app/entity/handler/RentDeviceMessageHandler.java`

#### 6.1 reportPropertyMessageHandler - 变压器采集器属性分解
```java
@Subscribe(topics = {
    "/device/MB_TRANSFORMER/*/message/property/report",
})
public Flux<Void> reportPropertyMessageHandler(DeviceMessage msg)
```
**功能**: 订阅变压器采集器（MB_TRANSFORMER）属性上报，将采集器下的子设备属性拆分，解析 key 格式 `{子设备ID}_{属性名}` -> 拆分出 deviceId 和 propertyId，保存到对应子设备。

**Key 解析**: `collectorDeviceId_key1_key2_propertyName` -> deviceId = `collectorDeviceId_key1_key2`, propertyId = `propertyName`
**特殊处理**: `dev_status` 映射为 `comm`

---

#### 6.2 reportRentDevicePropertyMessageHandler - 租赁设备属性上报
```java
@Subscribe(topics = {
    "/device/RENT_DDS3366D_2PS/*/message/property/report",
    "/device/RENT_DDS3366D_2PS/*/message/property/read/reply",
    "/device/RENT_DTSY3366D/*/message/property/report",
    "/device/RENT_DTSY3366D/*/message/property/read/reply",
    "/device/DCG301_4G_DDS3366D_2PS/*/message/property/report",
    "/device/DCG301_4G_DDS3366D_2PS/*/message/property/read/reply",
    "/device/DCG301_4G_DTSY3366D/*/message/property/report",
    "/device/DCG301_4G_DTSY3366D/*/message/property/read/reply",
    "/device/BT_RENT_DDS3366D_2PS/*/message/property/report",
    "/device/BT_RENT_DDS3366D_2PS/*/message/property/read/reply",
    "/device/BT_RENT_DDS3366D_2PS_CHILD/*/message/property/report",
    "/device/BT_RENT_DDS3366D_2PS_CHILD/*/message/property/read/reply",
})
public Mono<Void> reportPropertyMessageHandler(DeviceMessage msg)
```
**功能**: 订阅租赁设备属性上报，更新 RentDeviceUpdateEntity 状态，发布 MQTT 消息。

**解析属性**:
- `Esy` + `Etz` -> remaining_quantity (剩余用量)
- `E` -> current_reading (当前读数)
- `SwitchState` -> power_status (开关状态)
- `PowerPreservationState` -> protected_status (保电状态)
- `U/Ua/Ub/Uc` -> voltage/voltage_b/voltage_c (电压)
- `I/Ia/Ib/Ic` -> current (电流)
- 等其他属性...

---

#### 6.3 reportLockPropertyMessageHandler - 门锁/水表等设备属性上报
```java
@Subscribe(topics = {
    "/device/HLS_BT_LOCK/*/message/property/report",
    "/device/HLS_BT_LOCK/*/message/property/read/reply",
    "/device/MM010295_485/*/message/property/report",
    "/device/MM010295_485/*/message/property/read/reply",
    "/device/MM010296_485/*/message/property/report",
    "/device/MM010296_485/*/message/property/read/reply",
    "/device/MM010295/*/message/property/report",
    "/device/MM010295/*/message/property/read/reply",
    "/device/YM_BT_WATER/*/message/property/report",
    "/device/YM_BT_WATER/*/message/property/read/reply",
    "/device/LORA_A6B_GATEWAY/*/message/property/report",
    "/device/LORA_A6B_GATEWAY/*/message/property/read/reply",
    "/device/LORA_HLS_BT_LOCK/*/message/property/report",
    "/device/LORA_HLS_BT_LOCK/*/message/property/read/reply",
    "/device/JYL_4G_WATER/*/message/property/report",
    "/device/JYL_4G_WATER/*/message/property/read/reply",
    "/device/LORA_WATER_GATEWAY/*/message/property/report",
    "/device/LORA_WATER_GATEWAY/*/message/property/read/reply",
    "/device/LORA_WATER_CHILD/*/message/property/report",
    "/device/LORA_WATER_CHILD/*/message/property/read/reply",
    "/device/DTSD3366D_4P/*/message/property/report",
    "/device/DTSD3366D_4P/*/message/property/read/reply",
    "/device/YDZL_DTSY3366D/*/message/property/report",
    "/device/YDZL_DTSY3366D/*/message/property/read/reply",
    "/device/YDZL_DDS3366D_2PS/*/message/property/report",
    "/device/YDZL_DDS3366D_2PS/*/message/property/read/reply",
})
public Mono<Void> reportLockPropertyMessageHandler(DeviceMessage msg)
```
**功能**: 订阅门锁、水表、网关等设备属性上报，构造 MQTT 消息发布。

---

#### 6.4 lockBindEventMessageHandler - 门锁绑定事件
```java
@Subscribe(topics = {
    "/device/LORA_A6B_GATEWAY/*/message/event/*",
})
public Mono<Void> lockBindEventMessageHandler(DeviceMessage msg)
```
**功能**: 订阅 LoRa 网关事件，解析 fmac/cmac，查询子设备，通过 MQTT 下发绑定命令到网关。

---

#### 6.5 registerMessageHandler - 设备注册处理
```java
@Subscribe(topics = {
    allDeviceRegisterEvent  // 动态topic
})
public Mono<Void> registerMessageHandler(String deviceId)
```
**功能**: 订阅设备注册事件，根据产品类型触发相应功能调用（如 LORA_WATER_CHILD 调用 downMeterFile）。

---

### 7. MessageHandler

**文件**: `jetlinks-manager/saas-manager/src/main/java/org/jetlinks/community/app/entity/handler/MessageHandler.java`

#### 7.1 functionReplyInvokeMessageHandler - 功能调用回复处理
```java
@Subscribe(topics = {
    "/device/DTSY3366D/*/message/function/reply",
    "/device/DDS3366D_2PS/*/message/function/reply",
    "/device/DDS3366D_2PS_DLT645/*/message/function/reply",
    "/device/BT_RENT_DDS3366D_2PS/*/message/function/reply",
    "/device/BT_RENT_DDS3366D_2PS_CHILD/*/message/function/reply",
})
public Mono<Void> functionReplyInvokeMessageHandler(DeviceMessage msg)
```
**功能**: 订阅设备功能调用回复，根据 functionId 触发属性更新:
- `Reset/Gift/Initialize/Set/Refund/Register/Charge` -> 更新 Esy, Etz, State_3
- `NotPreserve/Preserve/SwitchOff/SwitchOn` -> 更新 State_3

---

#### 7.2 onlineMessageHandler - 设备上线处理
```java
@Subscribe(topics = {
    "/device/*/*/online",
})
public Mono<Void> onlineMessageHandler(DeviceMessage msg)
```
**功能**: 订阅设备上线事件，若设备为子设备类型则跳过；检查设备是否支持 UpdateData 功能，若支持则调用。

---

## 三、rule-engine-manager 模块 (org.jetlinks.community.rule.engine)

### 8. DefaultAlarmRuleHandler

**文件**: `jetlinks-manager/rule-engine-manager/src/main/java/org/jetlinks/community/rule/engine/alarm/DefaultAlarmRuleHandler.java`

#### 8.1 handleAlarmConfig - 告警配置保存
```java
@Subscribe(value = TOPIC_ALARM_CONFIG_SAVE, features = {Subscription.Feature.local, Subscription.Feature.broker})
public Mono<Void> handleAlarmConfig(AlarmConfigEntity entity)
```
**功能**: 订阅告警配置保存事件，将配置写入本地缓存存储。

---

#### 8.2 removeAlarmConfig - 告警配置删除
```java
@Subscribe(value = TOPIC_ALARM_CONFIG_DELETE, features = {Subscription.Feature.local, Subscription.Feature.broker})
public Mono<Void> removeAlarmConfig(AlarmConfigEntity entity)
```
**功能**: 订阅告警配置删除事件，清除本地缓存中的配置。

---

#### 8.3 handleUnBind - 告警规则解绑
```java
@Subscribe(value = TOPIC_ALARM_RULE_UNBIND, features = {Subscription.Feature.local, Subscription.Feature.broker})
public void handleUnBind(AlarmRuleBindEntity entity)
```
**功能**: 订阅告警规则解绑事件，从内存中的 `ruleAlarmBinds` Map 移除绑定关系。

---

#### 8.4 handleBind - 告警规则绑定
```java
@Subscribe(value = TOPIC_ALARM_RULE_BIND, features = {Subscription.Feature.local, Subscription.Feature.broker})
public void handleBind(AlarmRuleBindEntity entity)
```
**功能**: 订阅告警规则绑定事件，将绑定关系加入内存 Map。

---

### 9. AlarmSceneHandler

**文件**: `jetlinks-manager/rule-engine-manager/src/main/java/org/jetlinks/community/rule/engine/alarm/AlarmSceneHandler.java`

#### 9.1 HandleAlarmConfigDelete - 告警配置删除
```java
@Subscribe(value = "/_sys/alarm/config/deleted", features = Subscription.Feature.broker)
public Mono<Void> HandleAlarmConfigDelete(AlarmConfigEntity alarmConfig)
```
**功能**: 订阅告警配置删除事件，执行配置删除逻辑。

---

#### 9.2 handleAlarmConfigCRU - 告警配置创建/保存/修改
```java
@Subscribe(value = "/_sys/alarm/config/created,saved,modified", features = Subscription.Feature.broker)
public Mono<Void> handleAlarmConfigCRU(AlarmConfigEntity alarmConfig)
```
**功能**: 订阅告警配置变更事件（created/saved/modified），执行相应 CRUD 操作。

---

## 四、notify-manager 模块 (org.jetlinks.community.notify.manager)

### 10. NotifyHistoryService

**文件**: `jetlinks-manager/notify-manager/src/main/java/org/jetlinks/community/notify/manager/service/NotifyHistoryService.java`

#### 10.1 handleNotify - 通知历史记录保存
```java
@Subscribe("/notify/**")
public Mono<Void> handleNotify(SerializableNotifierEvent event)
```
**功能**: 订阅所有通知事件，将通知写入数据库。

---

### 11. NotificationService

**文件**: `jetlinks-manager/notify-manager/src/main/java/org/jetlinks/community/notify/manager/service/NotificationService.java`

#### 11.1 subscribeNotifications - 通知订阅
```java
@Subscribe("/notifications/**")
public Mono<Void> subscribeNotifications(Notification notification)
```
**功能**: 订阅通知消息，写入通知实体。

---

### 12. ElasticSearchNotifyHistoryRepository

**文件**: `jetlinks-manager/notify-manager/src/main/java/org/jetlinks/community/notify/manager/service/ElasticSearchNotifyHistoryRepository.java`

#### 12.1 handleNotify - 通知历史写入 ES
```java
@Subscribe("/notify/**")
public Mono<Void> handleNotify(SerializableNotifierEvent event)
```
**功能**: 订阅通知事件，将通知历史写入 Elasticsearch。

---

## 五、network-manager 模块 (org.jetlinks.community.network.manager)

### 13. DeviceDebugSubscriptionProvider

**文件**: `jetlinks-manager/network-manager/src/main/java/org/jetlinks/community/network/manager/debug/DeviceDebugSubscriptionProvider.java`

#### 13.1 handleTraceEnable - 调试追踪开关
```java
@Subscribe(value = "/_sys/_trace/opt", features = {Subscription.Feature.broker, Subscription.Feature.local})
public Mono<Void> handleTraceEnable(TraceOpt opt)
```
**功能**: 订阅追踪开关事件，启用/禁用设备调试追踪。

---

## 六、device-manager 模块 (org.jetlinks.community.device)

### 14. TimeSeriesMessageWriterConnector

**文件**: `jetlinks-manager/device-manager/src/main/java/org/jetlinks/community/device/message/writer/TimeSeriesMessageWriterConnector.java`

#### 14.1 writeDeviceMessageToTs - 设备消息写入时序库
```java
@Subscribe(topics = "/device/**", id = "device-message-ts-writer")
public Mono<Void> writeDeviceMessageToTs(DeviceMessage message)
```
**功能**: 订阅所有设备消息，写入时序数据库。

---

### 15. DefaultDeviceDataManager

**文件**: `jetlinks-manager/device-manager/src/main/java/org/jetlinks/community/device/message/DefaultDeviceDataManager.java`

#### 15.1 upgradeDeviceFirstPropertyTime - 更新首次属性上报时间
```java
@Subscribe(topics = {
    "/device/*/*/message/property/report",
    "/device/*/*/message/property/read,write/reply"
}, features = Subscription.Feature.local)
public Mono<Void> upgradeDeviceFirstPropertyTime(DeviceMessage message)
```
**功能**: 订阅属性上报/读写回复消息，更新设备的首次属性上报时间配置。

---

### 16. DeviceStatusMeasurementProvider

**文件**: `jetlinks-manager/device-manager/src/main/java/org/jetlinks/community/device/measurements/status/DeviceStatusMeasurementProvider.java`

#### 16.1 incrementOnline - 在线计数
```java
@Subscribe("/device/*/*/online")
public Mono<Void> incrementOnline(DeviceMessage msg)
```
**功能**: 订阅设备上线事件，增加在线计数器 (Micrometer)。

---

#### 16.2 incrementOffline - 离线计数
```java
@Subscribe("/device/*/*/offline")
public Mono<Void> incrementOffline(DeviceMessage msg)
```
**功能**: 订阅设备下线事件，增加离线计数器 (Micrometer)。

---

### 17. DeviceMessageMeasurementProvider

**文件**: `jetlinks-manager/device-manager/src/main/java/org/jetlinks/community/device/measurements/message/DeviceMessageMeasurementProvider.java`

#### 17.1 incrementMessage - 消息计数
```java
@Subscribe("/device/*/*/message/**")
public Mono<Void> incrementMessage(DeviceMessage message)
```
**功能**: 订阅所有设备消息，增加消息计数指标。

---

### 18. DeviceMessageBusinessHandler

**文件**: `jetlinks-manager/device-manager/src/main/java/org/jetlinks/community/device/service/DeviceMessageBusinessHandler.java`

#### 18.1 autoRegisterDevice - 自动注册设备
```java
@Subscribe("/device/*/*/register")
public Mono<Void> autoRegisterDevice(DeviceRegisterMessage message)
```
**功能**: 订阅设备注册消息，自动注册新设备到平台。

---

#### 18.2 autoBindChildrenDevice - 自动绑定子设备
```java
@Subscribe("/device/*/*/message/children/*/register")
public Mono<Void> autoBindChildrenDevice(ChildDeviceMessage message)
```
**功能**: 订阅子设备注册消息，自动绑定子设备到网关设备。

---

#### 18.3 autoUnbindChildrenDevice - 自动解绑子设备
```java
@Subscribe("/device/*/*/message/children/*/unregister")
public Mono<Void> autoUnbindChildrenDevice(ChildDeviceMessage message)
```
**功能**: 订阅子设备注销消息，自动解除子设备与网关的绑定。

---

#### 18.4 unRegisterDevice - 设备注销
```java
@Subscribe("/device/*/*/unregister")
public Mono<Void> unRegisterDevice(DeviceUnRegisterMessage message)
```
**功能**: 订阅设备注销消息，执行设备注销操作。

---

#### 18.5 updateDeviceTag - 更新设备标签
```java
@Subscribe("/device/*/*/message/tags/update")
public Mono<Void> updateDeviceTag(UpdateTagMessage message)
```
**功能**: 订阅设备标签更新消息，更新设备标签信息。

---

#### 18.6 updateMetadata - 更新物模型
```java
@Subscribe("/device/*/*/metadata/derived")
public Mono<Void> updateMetadata(DerivedMetadataMessage message)
```
**功能**: 订阅设备物模型派生消息，合并更新设备物模型。

---

### 19. DatabaseDeviceLatestDataService

**文件**: `jetlinks-manager/device-manager/src/main/java/org/jetlinks/community/device/service/data/DatabaseDeviceLatestDataService.java`

#### 19.1 save - 保存设备最新数据
```java
@Subscribe(topics = "/device/**", features = Subscription.Feature.local)
public void save(DeviceMessage message)
```
**功能**: 订阅所有设备消息，保存设备最新数据到数据库表（含属性和事件）。

---

## 七、data-manager 模块 (org.jetlinks.community.sf)

### 20. DeviceMessageHandler

**文件**: `jetlinks-manager/data-manager/src/main/java/org/jetlinks/community/sf/service/DeviceMessageHandler.java`

#### 20.1 createOfflineAlarm - 创建离线告警
```java
@Subscribe(topics = {
    "/device/DTSD3366M_MQTT/*/offline",
    "/device/sf_io_module/*/offline",
    "/device/sf_u32_module/*/offline",
    "/device/sf_management_machine/*/offline",
})
public Mono<Integer> createOfflineAlarm(DeviceMessage msg)
```
**功能**: 订阅特定设备离线事件，创建离线告警记录。

---

#### 20.2 updateOfflineAlarm - 更新离线告警
```java
@Subscribe(topics = {
    "/device/DTSD3366M_MQTT/*/online",
    "/device/sf_io_module/*/online",
    "/device/sf_u32_module/*/online",
    "/device/sf_management_machine/*/online",
})
public Mono<Integer> updateOfflineAlarm(DeviceMessage msg)
```
**功能**: 订阅特定设备上线事件，更新离线告警状态。

---

## 八、已注释的订阅（供参考）

以下订阅在源码中已被注释，迁移时需注意：

| 文件 | 位置 | 说明 |
|------|------|------|
| DeviceDataPublishService.java | 行28 | 属性上报订阅已注释 |
| PDWorkOrderService.java | 行23 | 工单相关订阅已注释 |
| PDAlarmRecordService.java | 行27 | 告警记录订阅已注释 |
| RentDeviceMessageHandler.java | 多处 | 上下线、水表、采集器等订阅已注释 |
| MessageHandler.java | 行42 | 属性上报订阅已注释 |
| AlarmHandler.java | 行34 | 告警处理已注释 |
| AlarmReprocessedService.java | 行57 | 告警重处理已注释 |
| RuleLogHandler.java | 行15,21 | 规则日志已注释 |

---

## 九、订阅主题分类汇总

### 设备消息类
| Topic Pattern | 数量 | 主要功能 |
|---------------|------|----------|
| `/device/*/*/message/property/report` | 9 | 属性上报处理 |
| `/device/*/*/message/property/read/reply` | 7 | 读属性回复处理 |
| `/device/*/*/online` | 4 | 设备上线 |
| `/device/*/*/offline` | 4 | 设备下线 |
| `/device/*/*/register` | 1 | 设备注册 |
| `/device/*/*/unregister` | 1 | 设备注销 |
| `/device/*/*/message/tags/update` | 1 | 标签更新 |
| `/device/*/*/metadata/derived` | 1 | 物模型更新 |
| `/device/*/*/message/children/*/register` | 1 | 子设备注册 |
| `/device/*/*/message/children/*/unregister` | 1 | 子设备注销 |

### 告警类
| Topic Pattern | 数量 | 主要功能 |
|---------------|------|----------|
| `/device/*/*/message/event/warning` | 1 | 设备端告警事件 |
| `/alarm/*/*/*/record` | 1 | 告警触发记录 |
| `/alarm/*/*/*/relieve` | 1 | 告警解除 |
| `/_sys/alarm/config/*` | 2 | 告警配置变更 |
| `/_sys/device-alarm-rule/bind` | 1 | 告警规则绑定 |
| `/_sys/device-alarm-rule/unbind` | 1 | 告警规则解绑 |

### 通知类
| Topic Pattern | 数量 | 主要功能 |
|---------------|------|----------|
| `/notify/**` | 2 | 通知事件 |
| `/notifications/**` | 1 | 通知消息 |

### 系统类
| Topic Pattern | 数量 | 主要功能 |
|---------------|------|----------|
| `/_sys/_trace/opt` | 1 | 调试追踪开关 |

---

## 十、迁移注意事项

1. **订阅优先级**: 部分订阅使用 `features = Subscription.Feature.local` 仅本地处理，使用 `broker` 则会跨节点广播
2. **消息去重**: 同一消息可能被多个订阅处理（如设备上下线同时触发告警和统计）
3. **异步处理**: 大部分订阅返回 `Mono<Void>` 或 `Mono<Integer>`，消息处理为异步
4. **错误处理**: 部分订阅有熔断器保护（如 MessagePublishService 的 Redis 写入）
5. **已注释订阅**: 迁移前需确认旧版已注释的订阅是否需要重新启用
