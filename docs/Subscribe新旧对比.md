# `@Subscribe` 新旧版本功能对比

> 对比时间: 2026-04-10
> 旧版依据: `docs/Subscribe统计.md`
> 新版依据: `back/jetlinks-community-2.10/jetlinks-community-2.10` 中当前实际存在的 `@Subscribe`
> 对比范围: 仅对比 `@Subscribe` 注解方式实现的订阅功能，不包含手工 `Subscription.builder()` 创建的订阅

---

## 1. 一样的功能

以下功能在旧版统计中存在，在新版 `back` 中也能找到对应的 `@Subscribe` 能力。

### 1.1 设备消息写入时序库

- 旧版:
  - `TimeSeriesMessageWriterConnector.writeDeviceMessageToTs`
  - Topic: `/device/**`
- 新版:
  - `device-manager/.../TimeSeriesMessageWriterConnector.java`
  - `@Subscribe(topics = "/device/**", id = "device-message-ts-writer", priority = 100)`
- 结论:
  - 保留
  - 新版还增加了和 Redis latest 的联动写入逻辑

### 1.2 设备最新数据保存

- 旧版:
  - `DatabaseDeviceLatestDataService.save`
  - Topic: `/device/**`
- 新版:
  - `device-manager/.../DatabaseDeviceLatestDataService.java`
  - `device-manager/.../RedisDeviceLatestDataService.java`
- 结论:
  - 保留
  - 新版从“数据库 latest”扩展成了“数据库 + Redis latest”

### 1.3 设备消息计数

- 旧版:
  - `DeviceMessageMeasurementProvider.incrementMessage`
  - Topic: `/device/*/*/message/**`
- 新版:
  - `device-manager/.../DeviceMessageMeasurementProvider.java`
- 结论:
  - 保留

### 1.4 设备自动注册

- 旧版:
  - `DeviceMessageBusinessHandler.autoRegisterDevice`
  - Topic: `/device/*/*/register`
- 新版:
  - `device-manager/.../DeviceMessageBusinessHandler.java`
- 结论:
  - 保留

### 1.5 子设备自动绑定

- 旧版:
  - `DeviceMessageBusinessHandler.autoBindChildrenDevice`
  - Topic: `/device/*/*/message/children/*/register`
- 新版:
  - `device-manager/.../DeviceMessageBusinessHandler.java`
- 结论:
  - 保留

### 1.6 子设备自动解绑

- 旧版:
  - `DeviceMessageBusinessHandler.autoUnbindChildrenDevice`
  - Topic: `/device/*/*/message/children/*/unregister`
- 新版:
  - `device-manager/.../DeviceMessageBusinessHandler.java`
- 结论:
  - 保留

### 1.7 派生物模型更新

- 旧版:
  - `DeviceMessageBusinessHandler.updateMetadata`
  - Topic: `/device/*/*/metadata/derived`
- 新版:
  - `device-manager/.../DeviceMessageBusinessHandler.java`
- 结论:
  - 保留

### 1.8 设备标签更新

- 旧版:
  - `DeviceMessageBusinessHandler.updateDeviceTag`
  - Topic: `/device/*/*/message/tags/update`
- 新版:
  - `device-manager/.../DeviceTagSynchronizer.java`
- 结论:
  - 功能保留
  - 实现类从 `DeviceMessageBusinessHandler` 调整到了 `DeviceTagSynchronizer`

### 1.9 告警配置保存/删除、规则绑定/解绑

- 旧版:
  - `DefaultAlarmRuleHandler.handleAlarmConfig`
  - `DefaultAlarmRuleHandler.removeAlarmConfig`
  - `DefaultAlarmRuleHandler.handleUnBind`
  - `DefaultAlarmRuleHandler.handleBind`
  - `AlarmSceneHandler.HandleAlarmConfigDelete`
  - `AlarmSceneHandler.handleAlarmConfigCRU`
- 新版:
  - `rule-engine-manager/.../DefaultAlarmRuleHandler.java`
  - `rule-engine-manager/.../AlarmSceneHandler.java`
- 结论:
  - 保留

### 1.10 通知历史和通知消息订阅

- 旧版:
  - `NotifyHistoryService.handleNotify` 或 `ElasticSearchNotifyHistoryRepository.handleNotify`
  - `NotificationService.subscribeNotifications`
- 新版:
  - `notify-manager/.../TimeSeriesNotifyHistoryRepository.java`
  - `notify-manager/.../NotificationService.java`
- 结论:
  - 保留
  - 通知历史实现从旧版的多种存储方式，变成当前基于时序库的实现

### 1.11 调试追踪开关

- 旧版:
  - `DeviceDebugSubscriptionProvider.handleTraceEnable`
  - Topic: `/_sys/_trace/opt`
- 新版:
  - `network-manager/.../DeviceDebugSubscriptionProvider.java`
- 结论:
  - 保留

---

## 2. 旧版有而新版没有的功能

以下功能在旧版统计中有记录，但在新版 `back` 当前的 `@Subscribe` 中未找到对应实现。

### 2.1 `standard-manager` 中的业务订阅

- `MessagePublishService.redisPublisher`
  - 订阅属性上报/属性读取回复后写 Redis
- `MessagePublishService.onlineMessageHandler`
  - 订阅上下线并转发 MQTT
- `AlarmSubscribeService.alarmEventMessageHandler`
  - 订阅设备端 `event/warning`
- `AlarmSubscribeService.alarmTriggerMessageHandler`
  - 订阅 `/alarm/*/*/*/record`
- `AlarmSubscribeService.alarmRelieveMessageHandler`
  - 订阅 `/alarm/*/*/*/relieve`
- `SpecialMessagePublishHandler.reportYDGATEPropertyMessageHandler`
  - YDGATE 特殊属性转在线/离线
- `SpecialMessagePublishHandler.reportYDGWPropertyMessageHandler`
  - YDGW 特殊属性转在线/离线
- `DeviceValueHandler.saveDeviceValue`
  - 针对指定产品保存设备值

### 2.2 `saas-manager` 中的业务订阅

- `RentDeviceMessageHandler.reportPropertyMessageHandler`
  - `MB_TRANSFORMER` 采集器属性拆分
- `RentDeviceMessageHandler.reportRentDevicePropertyMessageHandler`
  - 租赁设备属性上报处理
- `RentDeviceMessageHandler.reportLockPropertyMessageHandler`
  - 门锁/水表/网关设备属性处理
- `RentDeviceMessageHandler.lockBindEventMessageHandler`
  - LoRa 网关事件绑定
- `RentDeviceMessageHandler.registerMessageHandler`
  - 设备注册后的业务动作
- `MessageHandler.functionReplyInvokeMessageHandler`
  - 功能调用回复处理
- `MessageHandler.onlineMessageHandler`
  - 设备在线后的业务动作

### 2.3 `data-manager` 中的离线告警

- `DeviceMessageHandler.createOfflineAlarm`
- `DeviceMessageHandler.updateOfflineAlarm`

### 2.4 旧版统计里的平台订阅，但新版未找到对应 `@Subscribe`

- `/device/*/*/unregister`
  - 旧版统计中列了 `unRegisterDevice`
  - 新版当前未找到 `@Subscribe("/device/*/*/unregister")`
- `DefaultDeviceDataManager.upgradeDeviceFirstPropertyTime`
  - 旧版有属性首次上报时间更新
  - 新版未找到对应 `@Subscribe`
- `DeviceStatusMeasurementProvider.incrementOnline`
  - 旧版统计列为在线计数
  - 新版未找到对应 `@Subscribe`
- `DeviceStatusMeasurementProvider.incrementOffline`
  - 旧版统计列为离线计数
  - 新版未找到对应 `@Subscribe`

---

## 3. 新版有而旧版没有的功能

以下功能在新版 `back` 中存在，但旧版统计文档没有列出。

### 3.1 透明消息处理

- `TransparentDeviceMessageConnector.handleMessage`
  - Topic: `/device/*/*/message/direct`
  - 作用: 处理透传消息并解码成设备消息

### 3.2 透明编解码器动态加载/移除

- `TransparentDeviceMessageConnector.doLoadCodec`
  - Topic: `/_sys/transparent-codec/load`
- `TransparentDeviceMessageConnector.doRemoveCodec`
  - Topic: `/_sys/transparent-codec/removed`
- 作用:
  - 动态管理透明消息 codec

### 3.3 通知通道动态注册/注销

- `NotificationDispatcher.register`
  - Topic: `/_sys/notify-channel/register`
- `NotificationDispatcher.unregister`
  - Topic: `/_sys/notify-channel/unregister`
- 作用:
  - 动态刷新通知通道实例

### 3.4 规则引擎日志与事件持久化

- `TimeSeriesRuleEngineLogService.handleEvent`
  - Topic: `/rule-engine/*/*/event/${rule.engine.event.level:error}`
- `TimeSeriesRuleEngineLogService.handleLog`
  - Topic: `/rule-engine/*/*/logger/${rule.engine.logging.level:info,warn,error}`
- 作用:
  - 记录规则引擎事件和日志

### 3.5 属性指标缓存清理

- `DefaultPropertyMetricManager.handleMetricChangedEvent`
  - Topic: `/_sys/thing-property-metric/clear-cache`
- 作用:
  - 属性指标变更后清理缓存

---

## 4. 需要特别说明的点

### 4.1 新版并不是所有“旧能力”都真正消失了

有些旧版统计中的能力，在新版里可能只是：

- 改成了非 `@Subscribe` 的方式实现
- 改成事件流或其他组件处理
- 被更通用的平台能力替代

例如：

- `online/offline` 在新版里虽然没找到对应的 `@Subscribe` 方法，但在 `DeviceMessageBusinessHandler` 中找到了通过 `Subscription.builder()` 构建的订阅逻辑。
- 因此如果只比较 `@Subscribe`，它属于“旧版有而新版没有”。
- 如果比较“系统是否还具备该订阅能力”，则应进一步分析非注解式订阅实现。

### 4.2 旧版更偏业务定制，新版更偏平台通用

从当前代码看：

- 旧版中大量 `@Subscribe` 用于 Gaopu/租赁/门锁/水表/YDGATE/YDGW 等业务场景
- 新版保留的主要是 JetLinks 平台通用能力
- 新版新增的也主要是平台内核能力，如透明消息、规则日志、通知通道、属性指标缓存

---

## 5. 总结

### 相同

- 设备消息写库
- latest 数据保存
- 设备消息计数
- 自动注册/子设备绑定解绑
- 标签更新
- 物模型派生更新
- 告警配置与绑定关系同步
- 通知历史和通知消息处理
- 调试追踪开关

### 旧版有而新版没有

- 大量业务定制订阅
- 设备端 warning 告警处理
- `/alarm/.../record`、`/alarm/.../relieve`
- YDGATE/YDGW 特殊逻辑
- 租赁设备/门锁/水表/采集器相关订阅
- 功能调用回复处理
- 某些在线/离线业务逻辑
- 旧版统计中的 `unregister`、首次属性上报时间、在线离线计数等注解订阅

### 新版有而旧版没有

- 透明消息订阅
- 透明 codec 动态加载/删除
- 通知通道注册/注销
- 规则引擎日志/事件订阅
- 属性指标缓存清理

