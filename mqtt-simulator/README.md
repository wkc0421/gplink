# MQTT 设备模拟器

这是一个独立于 JetLinks 的 MQTT 设备压测工具，目录位于 [mqtt-simulator](/D:/project/gplink_ai/gplink/mqtt-simulator)。它采用单体服务结构：

- Go 后端负责任务持久化、MQTT 高并发发送、SSE 运行监控和静态资源托管
- 静态前端位于 [web](/D:/project/gplink_ai/gplink/mqtt-simulator/web)，直接由后端提供页面

## 已实现内容

- 任务配置管理：新增、编辑、删除、启动、停止
- 物模型导入：支持粘贴 JSON 或上传文件，只解析 `properties`
- payload 预览：调用 `POST /api/preview/payload`
- 高并发发送模型：单任务单 MQTT 连接、调度协程 + worker pool + 有界队列
- 运行统计：累计发送、失败数、每秒/每分钟速率、平均 payload 大小、最近错误
- SSE 实时推送：`GET /api/stream/tasks`
- SQLite 持久化：任务配置、最近运行状态、`E` 点位递增值

## 目录结构

- [main.go](/D:/project/gplink_ai/gplink/mqtt-simulator/main.go)
- [internal/server/server.go](/D:/project/gplink_ai/gplink/mqtt-simulator/internal/server/server.go)
- [internal/runtime/manager.go](/D:/project/gplink_ai/gplink/mqtt-simulator/internal/runtime/manager.go)
- [internal/runtime/payload.go](/D:/project/gplink_ai/gplink/mqtt-simulator/internal/runtime/payload.go)
- [web/index.html](/D:/project/gplink_ai/gplink/mqtt-simulator/web/index.html)
- [web/app.js](/D:/project/gplink_ai/gplink/mqtt-simulator/web/app.js)

## 配置字段

每个任务包含以下核心字段：

- `name`：任务名称
- `productId`
- `messageType`：消息根字段 `type`
- `metadata`：导入后得到的属性列表
- `deviceCount`
- `messagesPerMinute`
- `brokerUrl`
- `username`
- `password`
- `clientId`
- `clientIdPrefix`
- `topicTemplate`

默认主题模板为 `IOT/{productId}/Data`。

## 消息格式

每次发送只对应一个设备，格式如下：

```json
{
  "type": "change",
  "sn": "product-001",
  "time": "2026-04-07 21:20:50",
  "data": {
    "product-001-000001": [
      { "id": "Ua", "desc": "A相电压", "value": "235.031" }
    ]
  }
}
```

规则如下：

- `id <- property.id`
- `desc <- property.name`
- `quality` 不输出
- `E` 对同一设备严格递增，并持久化到 SQLite
- `float` 类型默认输出 3 位小数
- `enum` 类型从枚举值中取样
- `string` 类型生成占位字符串

## HTTP API

- `POST /api/products/import-model`
- `GET /api/tasks`
- `POST /api/tasks`
- `PUT /api/tasks/:id`
- `DELETE /api/tasks/:id`
- `POST /api/tasks/:id/start`
- `POST /api/tasks/:id/stop`
- `GET /api/tasks/:id/stats`
- `GET /api/stream/tasks`
- `POST /api/preview/payload`

## 运行方式

先安装 Go 1.22 或更高版本，然后在 [mqtt-simulator](/D:/project/gplink_ai/gplink/mqtt-simulator) 目录执行：

```powershell
go mod tidy
go run .
```

默认监听 `:8099`，可通过环境变量调整：

```powershell
$env:MQTT_SIMULATOR_ADDR=":9000"
$env:MQTT_SIMULATOR_DATA_DIR="D:\mqtt-simulator-data"
go run .
```

启动后访问 [http://localhost:8099](http://localhost:8099)。

## 当前限制

- 服务重启后只恢复任务配置和最近统计，不自动重新启动发送任务
- 当前实现使用单任务单连接模式，不包含“每设备一个连接”的连接压测模式
- TLS 目前采用 `InsecureSkipVerify=true` 的简化策略，适合测试环境
