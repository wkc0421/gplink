# Modbus 模拟器 — 平台配置指南

配套脚本：
- `../modbus_tcp_client_sim.js`：TCP Client 模式，模拟网关主动连入平台，默认 5 台网关、每台 20 个从机。
- `../modbus_5slave_sim.py`：TCP Server 模式，模拟器监听端口，平台主动拨号连接，默认 1 台网关、5 个从机。

配套导入文件：
- `modbus_device_import_5gw_100slaves.xlsx`：5 台网关、100 台子设备，第一张工作表可直接用于设备导入。
- 如需重新生成：`powershell -NoProfile -ExecutionPolicy Bypass -File generate_modbus_device_import_xlsx.ps1`

---

## 快速开始

### 模式A：网关主动连入平台（TCP Client 模拟器）

平台侧先创建 **TCP 服务** 网络组件，例如监听 `0.0.0.0:4000`，再创建 **TCP 服务设备网关**，协议选择 `Modbus RTU (TCP 透传)`。

```bash
cd mqtt-simulator
node modbus_tcp_client_sim.js --host 127.0.0.1 --port 4000
```

默认行为：
- 启动 5 条 TCP client 连接：`mb_4000` 到 `mb_4004`
- 每条连接模拟 20 个 Modbus 从机：`slaveId=1..20`
- 从机类型每 5 个循环一次：温湿度、压力流量、电能表、继电器、变频器
- 设备 ID 与导入表一致：`{网关ID}_{slaveId}`，例如 `mb_4000_1`

可按需调整：

```bash
node modbus_tcp_client_sim.js --host 192.168.1.10 --port 4000 --gateways 5 --slaves-per-gateway 20 --verbose
```

> 注意：Modbus RTU 帧本身不携带网关设备 ID。TCP Server 模式下，平台侧仍需要能把一条 TCP 连接归属到对应网关设备；否则只能看到 socket 连接，无法把后续读写请求路由到正确网关会话。若连接 10 秒左右被断开，可临时增大 `-Dgateway.tcp.network.connect-check-timeout=300s` 便于排查。

### 模式B：平台主动拨号模拟器（TCP Server 模拟器）

```bash
# 启动模拟器（监听 0.0.0.0:4000）
python modbus_5slave_sim.py

# 指定端口
python modbus_5slave_sim.py --port 502
```

模拟器启动后作为 **TCP Server** 等待平台连入。平台侧需创建 **TCP Client** 网络组件连接到模拟器。

---

## 平台配置步骤

### 第一步：上传协议包

系统管理 → 协议管理 → 新增  
上传 `jetlinks-modbus-protocol-*.jar` → 启用

### 第二步：创建网络组件

网络组件 → 新增

| 字段 | 值 |
|------|----|
| 名称 | `modbus-client-4000` |
| 类型 | TCP 客户端 |
| 远端地址 | 模拟器所在机器 IP（本机填 `127.0.0.1`） |
| 远端端口 | `4000` |

### 第三步：创建接入网关

设备接入 → 接入网关 → 新增

| 字段 | 值 |
|------|----|
| 名称 | `Modbus测试网关` |
| 类型 | TCP 客户端设备网关 |
| 网络组件 | modbus-client-4000 |
| 协议 | Modbus RTU (TCP 透传) |

保存后点击**启动**。

### 第四步：创建产品（共 6 个）

#### 网关产品

| 字段 | 值 |
|------|----|
| 名称 | `Modbus网关` |
| 产品 ID | `modbus-gateway` |
| 设备类型 | 网关设备 |
| 传输协议 | TCP |
| 消息协议 | Modbus RTU (TCP 透传) |
| 接入方式 | Modbus测试网关 |

网关产品无需配置寄存器映射，协议参数保持默认即可。

#### 从机产品（5个）

每个产品的创建方式相同，**接入方式**均选 `Modbus测试网关`，**设备类型**选`直连设备`。

| 产品名称 | 产品 ID | 物模型文件 |
|---------|---------|-----------|
| 温湿度传感器 | `modbus-slave-temp` | `slave1_temp_metadata.json` |
| 压力流量变送器 | `modbus-slave-pressure` | `slave2_pressure_metadata.json` |
| 电能表 | `modbus-slave-meter` | `slave3_meter_metadata.json` |
| 继电器控制器 | `modbus-slave-relay` | `slave4_relay_metadata.json` |
| 变频器 | `modbus-slave-vfd` | `slave5_vfd_metadata.json` |

每个产品创建后：  
1. 进入产品 → **功能定义** → 导入物模型（粘贴对应 JSON 文件内容）  
2. 进入产品 → **设备接入** → 填写寄存器映射（见下方各从机配置）

### 第五步：配置各从机产品的寄存器映射

#### Slave 1 — 温湿度传感器

设备接入配置：

| 参数 | 值 |
|------|----|
| slaveId（设备级，导入后单独设置） | `1` |
| probeFunctionCode | `3` |
| probeStartAddress | `0` |
| probeQuantity | `1` |

registerMap（粘贴到寄存器映射字段）：
```json
[
  {"propertyId":"temperature","functionCode":3,"address":0,"quantity":1,"dataType":"INT16","byteOrder":"ABCD","scale":0.1},
  {"propertyId":"humidity",   "functionCode":3,"address":1,"quantity":1,"dataType":"INT16","byteOrder":"ABCD","scale":0.1}
]
```

---

#### Slave 2 — 压力流量变送器

| 参数 | 值 |
|------|----|
| slaveId | `2` |
| probeFunctionCode | `3` |
| probeStartAddress | `0` |
| probeQuantity | `2` |

registerMap：
```json
[
  {"propertyId":"pressure",  "functionCode":3,"address":0,"quantity":2,"dataType":"FLOAT32","byteOrder":"ABCD","scale":1},
  {"propertyId":"flow_rate", "functionCode":3,"address":2,"quantity":2,"dataType":"FLOAT32","byteOrder":"ABCD","scale":1}
]
```

---

#### Slave 3 — 电能表

| 参数 | 值 |
|------|----|
| slaveId | `3` |
| probeFunctionCode | `3` |
| probeStartAddress | `0` |
| probeQuantity | `2` |

registerMap：
```json
[
  {"propertyId":"voltage",      "functionCode":3,"address":0,"quantity":2,"dataType":"FLOAT32","byteOrder":"ABCD","scale":1},
  {"propertyId":"current",      "functionCode":3,"address":2,"quantity":2,"dataType":"FLOAT32","byteOrder":"ABCD","scale":1},
  {"propertyId":"active_power", "functionCode":3,"address":4,"quantity":2,"dataType":"FLOAT32","byteOrder":"ABCD","scale":1},
  {"propertyId":"energy",       "functionCode":3,"address":6,"quantity":2,"dataType":"FLOAT32","byteOrder":"ABCD","scale":1}
]
```

---

#### Slave 4 — 继电器控制器

| 参数 | 值 |
|------|----|
| slaveId | `4` |
| probeFunctionCode | `1` |
| probeStartAddress | `0` |
| probeQuantity | `1` |

registerMap：
```json
[
  {"propertyId":"relay1",         "functionCode":1,"address":0,"quantity":1,"dataType":"BIT","writable":true},
  {"propertyId":"relay2",         "functionCode":1,"address":1,"quantity":1,"dataType":"BIT","writable":true},
  {"propertyId":"relay3",         "functionCode":1,"address":2,"quantity":1,"dataType":"BIT","writable":true},
  {"propertyId":"relay4",         "functionCode":1,"address":3,"quantity":1,"dataType":"BIT","writable":true},
  {"propertyId":"supply_voltage", "functionCode":3,"address":0,"quantity":1,"dataType":"UINT16","byteOrder":"ABCD","scale":0.1}
]
```

> ⚠️ relay1-4 用 FC01，supply_voltage 用 FC03，**定时任务需分两条**：  
> - 任务A：选 relay1、relay2、relay3、relay4（FC01 合并一帧）  
> - 任务B：选 supply_voltage（FC03 单帧）

---

#### Slave 5 — 变频器

| 参数 | 值 |
|------|----|
| slaveId | `5` |
| probeFunctionCode | `3` |
| probeStartAddress | `0` |
| probeQuantity | `2` |

registerMap：
```json
[
  {"propertyId":"output_freq",    "functionCode":3,"address":0,"quantity":2,"dataType":"FLOAT32","byteOrder":"ABCD","scale":1},
  {"propertyId":"output_voltage", "functionCode":3,"address":2,"quantity":2,"dataType":"FLOAT32","byteOrder":"ABCD","scale":1},
  {"propertyId":"output_current", "functionCode":3,"address":4,"quantity":2,"dataType":"FLOAT32","byteOrder":"ABCD","scale":1},
  {"propertyId":"fault_code",     "functionCode":3,"address":6,"quantity":1,"dataType":"UINT16","byteOrder":"ABCD","scale":1},
  {"propertyId":"running_status", "functionCode":1,"address":0,"quantity":1,"dataType":"BIT","writable":true}
]
```

> ⚠️ 同样混合 FC，**定时任务需分两条**：  
> - 任务A：output_freq、output_voltage、output_current、fault_code（FC03 合并一帧 `addr=0 qty=7`）  
> - 任务B：running_status（FC01 单帧）

---

### 第六步：导入设备

设备管理 → 设备实例 → 导入

1. 先导入 `gateway_devices.csv`（网关设备）
2. 再导入 `slave_devices.csv`（5台从机设备）

### 第七步：设置从机 slaveId

CSV 导入不含设备级配置，导入后需为每台从机设置 slaveId。

**方式A：逐台手动**  
设备详情 → 设备配置 → slaveId 填对应值

**方式B：批量脚本**
```bash
TOKEN="your_token"
BASE="http://localhost:8848/api"

for ENTRY in "mb_4000_1:1" "mb_4000_2:2" "mb_4000_3:3" "mb_4000_4:4" "mb_4000_5:5"; do
  DEV="${ENTRY%%:*}"
  SID="${ENTRY##*:}"
  curl -s -X PATCH "${BASE}/device/instance/${DEV}/config" \
    -H "Content-Type: application/json" \
    -H "X-Access-Token: ${TOKEN}" \
    -d "{\"slaveId\": ${SID}}"
  echo "set slaveId=${SID} for ${DEV}"
done
```

---

## 配置定时采集任务

设备管理 → 定时任务 → 新增，执行模式选**串行**。

| 任务名称 | 产品 | 采集属性 | Cron | 说明 |
|---------|------|---------|------|------|
| 温湿度采集 | 温湿度传感器 | temperature, humidity | `0/30 * * * * ?` | FC03 合并一帧 |
| 压力流量采集 | 压力流量变送器 | pressure, flow_rate | `0/30 * * * * ?` | FC03 合并一帧 |
| 电能采集 | 电能表 | voltage, current, active_power, energy | `0 * * * * ?` | FC03 合并一帧 |
| 继电器状态采集 | 继电器控制器 | relay1, relay2, relay3, relay4 | `0/10 * * * * ?` | FC01 合并一帧 |
| 继电器电压采集 | 继电器控制器 | supply_voltage | `0 * * * * ?` | FC03 单帧 |
| 变频器采集 | 变频器 | output_freq, output_voltage, output_current, fault_code | `0/10 * * * * ?` | FC03 合并一帧 |
| 变频器状态采集 | 变频器 | running_status | `0/10 * * * * ?` | FC01 单帧 |

---

## 验证清单

- [ ] 模拟器启动，终端显示"等待平台连接"
- [ ] 平台接入网关状态"运行中"
- [ ] `mb_4000` 网关设备上线
- [ ] 5台从机设备在 30s 内上线（探测帧有响应）
- [ ] 设备详情 → 属性 → 刷新，能看到正确数值
  - 温度：20~35°C，湿度：40~80%
  - 压力：~150kPa，流量：~30m³/h
  - 电压：~220V，电流：~10A
  - 继电器：全部 false（初始断开）
  - 变频器：频率 0Hz（初始停止）
- [ ] 向 relay1 写入 `true` → 模拟器控制台显示 `WRITE relay1 → ON`
- [ ] 向 running_status 写入 `true` → 变频器频率变为约 48~50Hz
- [ ] 定时任务启动后，模拟器每隔指定周期收到批量读请求
