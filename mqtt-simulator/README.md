# MQTT 设备模拟器

这是一个独立于 JetLinks 的 MQTT 设备压测工具，目录位于 [mqtt-simulator](/D:/project/gplink_ai/gplink/mqtt-simulator)。

它采用单体结构：
- Go 后端负责任务持久化、MQTT 高并发发送、SSE 运行监控和静态资源托管
- 静态前端位于 [web](/D:/project/gplink_ai/gplink/mqtt-simulator/web)，由后端直接提供页面

## 快速运行

先安装：
- Go 1.22+
- 可访问的 MQTT broker

进入目录后可直接运行：

```powershell
cd D:\project\gplink_ai\gplink\mqtt-simulator
go mod tidy
go run .
```

也可以直接双击或执行 [start.bat](/D:/project/gplink_ai/gplink/mqtt-simulator/start.bat)：

```cmd
cd /d D:\project\gplink_ai\gplink\mqtt-simulator
start.bat
```

默认监听：
- `http://127.0.0.1:8099`

可通过环境变量修改：

```powershell
$env:MQTT_SIMULATOR_ADDR=":9000"
$env:MQTT_SIMULATOR_DATA_DIR="D:\mqtt-simulator-data"
go run .
```

## 后台运行 / 部署

### 方式一：Windows 后台启动

适合测试机或内网服务器。

先编译：

```powershell
cd D:\project\gplink_ai\gplink\mqtt-simulator
go build -o mqtt-simulator.exe .
```

后台启动：

```powershell
Start-Process -FilePath ".\mqtt-simulator.exe" -WorkingDirectory "D:\project\gplink_ai\gplink\mqtt-simulator"
```

如果要指定端口和数据目录：

```powershell
$env:MQTT_SIMULATOR_ADDR=":8099"
$env:MQTT_SIMULATOR_DATA_DIR="D:\mqtt-simulator-data"
Start-Process -FilePath ".\mqtt-simulator.exe" -WorkingDirectory "D:\project\gplink_ai\gplink\mqtt-simulator"
```

停止时可按进程名结束：

```powershell
Get-Process mqtt-simulator -ErrorAction SilentlyContinue | Stop-Process -Force
```

### 方式二：Windows 计划任务自启动

适合开机自动运行。

1. 先编译出 `mqtt-simulator.exe`
2. 打开“任务计划程序”
3. 新建任务，触发器选择“开机时”
4. 操作选择启动程序，指向 `mqtt-simulator.exe`
5. 起始位置设为模拟器目录
6. 如需环境变量，可先写一个启动脚本再由计划任务调用

### 方式三：NSSM 包装成 Windows 服务

适合长期后台部署。

安装 `nssm` 后执行：

```cmd
nssm install mqtt-simulator D:\project\gplink_ai\gplink\mqtt-simulator\mqtt-simulator.exe
```

然后在 NSSM 界面里设置：
- AppDirectory: `D:\project\gplink_ai\gplink\mqtt-simulator`
- Environment:
  - `MQTT_SIMULATOR_ADDR=:8099`
  - `MQTT_SIMULATOR_DATA_DIR=D:\mqtt-simulator-data`

安装后启动服务：

```cmd
nssm start mqtt-simulator
```

这是最适合“后台长期跑”的方式。

## 已实现能力

- 任务配置管理：新增、编辑、删除、启动、停止
- 物模型导入：支持粘贴 JSON 或上传文件，只解析 `properties`
- payload 预览：`POST /api/preview/payload`
- 高并发发送模型：单任务单 MQTT 连接、调度协程 + worker pool + 有界队列
- 运行统计：累计发送、失败数、每秒/每分钟速率、平均 payload 大小、最近错误
- SSE 实时推送：`GET /api/stream/tasks`
- SQLite 持久化：任务配置、最近运行状态、`E` 点位递增值

## 目录结构

- [main.go](/D:/project/gplink_ai/gplink/mqtt-simulator/main.go)
- [internal/server/server.go](/D:/project/gplink_ai/gplink/mqtt-simulator/internal/server/server.go)
- [internal/runtime/manager.go](/D:/project/gplink_ai/gplink/mqtt-simulator/internal/runtime/manager.go)
- [internal/runtime/payload.go](/D:/project/gplink_ai/gplink/mqtt-simulator/internal/runtime/payload.go)
- [internal/store/sqlite.go](/D:/project/gplink_ai/gplink/mqtt-simulator/internal/store/sqlite.go)
- [web/index.html](/D:/project/gplink_ai/gplink/mqtt-simulator/web/index.html)
- [web/app.js](/D:/project/gplink_ai/gplink/mqtt-simulator/web/app.js)

## 配置字段

每个任务包含以下核心字段：

- `name`
- `productId`
- `messageType`
- `metadata`
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

每次发送只对应一个设备：

```json
{
  "type": "change",
  "sn": "product-001",
  "time": "2026-04-07 21:20:50",
  "data": {
    "000001": [
      { "id": "Ua", "desc": "A相电压", "value": "235.031" }
    ]
  }
}
```

规则：
- `id <- property.id`
- `desc <- property.name`
- 不输出 `quality`
- `E` 对同一设备严格递增，并持久化到 SQLite
- `float` 默认输出 3 位小数
- `enum` 从枚举值中取样
- `string` 生成占位字符串

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

## 测试脚本

先安装测试依赖：

```powershell
npm install
```

可执行：

```powershell
npm run test:smoke
npm run test:task-flow
npm run test:concurrency
```

说明：
- `test:smoke` 只验证页面和 payload 预览
- `test:task-flow` 验证创建、启动、停止完整流程
- `test:concurrency` 验证高频发送和 `E` 值递增

其中后两者默认使用本机 `127.0.0.1:2883` 的 MQTT broker，如地址不同，请修改测试脚本中的 `brokerUrl`。

## 当前限制

- 服务重启后只恢复任务配置和最近统计，不自动重新启动发送任务
- 当前只实现“单任务单连接”压测模式，不包含“每设备一个连接”模式
- TLS 当前采用 `InsecureSkipVerify=true` 的简化策略，适合测试环境
