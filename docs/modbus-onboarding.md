# Modbus RTU over TCP 设备接入指南

> 适用版本：GPLink / JetLinks Community 2.10  
> 协议包：`modbus-rtu.v1`（`jetlinks-modbus-protocol-*.jar`）

---

## 目录

1. [背景与架构](#1-背景与架构)
2. [前置条件](#2-前置条件)
3. [接入步骤总览](#3-接入步骤总览)
4. [第一步：上传协议包](#4-第一步上传协议包)
5. [第二步：创建网络组件](#5-第二步创建网络组件)
6. [第三步：创建设备接入网关](#6-第三步创建设备接入网关)
7. [第四步：创建产品](#7-第四步创建产品)
8. [第五步：配置物模型](#8-第五步配置物模型)
9. [第六步：创建设备](#9-第六步创建设备)
10. [第七步：配置寄存器映射](#10-第七步配置寄存器映射)
11. [验证在线与数据](#11-验证在线与数据)
12. [附录A：寄存器映射字段参考](#附录a寄存器映射字段参考)
13. [附录B：功能码速查表](#附录b功能码速查表)
14. [附录C：数据类型与字节序说明](#附录c数据类型与字节序说明)
15. [附录D：设备导入模板（CSV）](#附录d设备导入模板csv)
16. [附录E：物模型模板（JSON）](#附录e物模型模板json)
17. [常见问题排查](#常见问题排查)

---

## 1. 背景与架构

### Modbus RTU 协议要点

Modbus RTU 是工业控制中最常见的串行通信协议，采用**主从半双工**模式：
- **主机（Master）** 发起所有请求。
- **从机（Slave）** 只响应，不主动上报。
- 每帧格式：`[从机地址1B][功能码1B][数据域nB][CRC16_2B]`

### 接入模型

```
                  ┌──────────────────────┐
  Modbus 网关     │     GPLink 平台       │
  (TCP Server) ◄─┤ TCP Client 拨号连接   │  ← 模式B（平台主动发起）
  (TCP Client) ──►  TCP Server 等待连入   │  ← 模式A（网关主动连入）
                  └──────────────────────┘
        │
   RS-485/RS-232 总线
        │
   ┌────┴────┬────────┬────────┐
   从机1    从机2    从机3   ...从机247
  (子设备) (子设备) (子设备)
```

**平台侧设备结构（必须预先创建）：**

```
Modbus 网关产品
  └── Modbus 网关设备（对应1个 TCP 连接）
       ├── Modbus 从机产品
       │     └── 从机设备1（slaveId=1，父设备=网关设备）
       │     └── 从机设备2（slaveId=2，父设备=网关设备）
       └── ...
```

> **关键理解**：Modbus 从机**不会主动上线**，平台周期性发送探测帧（默认每 30 s 发一次 FC=0x03，读 1 个寄存器）来判断从机是否在线。

---

## 2. 前置条件

| 条件 | 说明 |
|------|------|
| GPLink 平台已运行 | 后端 + 前端均正常访问 |
| 协议 JAR 已构建 | `mvn -pl jetlinks-modbus-protocol package -DskipTests` |
| Modbus 网关设备就位 | 网关已连接至 RS-485 总线，从机已通电 |
| 网络可达 | 平台与网关之间 TCP 可互通（防火墙放行对应端口） |

---

## 3. 接入步骤总览

```
上传协议包 → 创建网络组件 → 创建接入网关 → 创建产品(网关+从机) →
配置物模型 → 创建设备(网关+从机) → 配置寄存器映射 → 验证在线
```

---

## 4. 第一步：上传协议包

1. 构建协议 JAR：
   ```bash
   cd back/jetlinks-community-2.10/jetlinks-community-2.10
   mvn -pl jetlinks-modbus-protocol package -DskipTests
   # 产物：jetlinks-modbus-protocol/target/jetlinks-modbus-protocol-*.jar
   ```

2. 平台菜单 → **系统管理 → 协议管理 → 新增**：
   - 名称：`Modbus RTU TCP`
   - 类型：`jar`
   - 上传：选择上一步产出的 JAR 文件
   - 保存并**启用**

> 启用后在"接入网关"的协议选择框中即可看到 `Modbus RTU (TCP 透传)`。

---

## 5. 第二步：创建网络组件

根据网关与平台的连接方向选择其中一种：

### 模式A：网关主动连入平台（TCP Server）

平台菜单 → **网络组件 → 新增**：

| 字段 | 值 |
|------|----|
| 名称 | `modbus-tcp-server-4000`（自定义） |
| 类型 | `TCP 服务（TCP Server）` |
| 本地端口 | `4000`（根据实际修改，需防火墙放行） |
| 解析方式 | 固定长度 / 脚本（见注意事项） |

### 模式B：平台主动拨号网关（TCP Client）

平台菜单 → **网络组件 → 新增**：

| 字段 | 值 |
|------|----|
| 名称 | `modbus-tcp-client-gw1`（自定义） |
| 类型 | `TCP 客户端（TCP Client）` |
| 远端地址 | 网关的 IP，如 `192.168.1.100` |
| 远端端口 | 网关监听端口，如 `502` |

> **粘包注意**：Modbus RTU over TCP 没有长度前缀，建议选解析方式 **脚本模式**，脚本可参考：以 CRC 结尾、最短 4 字节为一帧（具体实现依网关型号而定）。若网关总是完整发送一帧则可选**固定长度**临时规避。

---

## 6. 第三步：创建设备接入网关

平台菜单 → **设备接入 → 接入网关 → 新增**：

### 模式A 对应配置

| 字段 | 值 |
|------|----|
| 名称 | `Modbus网关_4000` |
| 类型 | `TCP 服务设备网关` |
| 网络组件 | 选择上一步创建的 TCP Server |
| 协议 | `Modbus RTU (TCP 透传)` |

### 模式B 对应配置

| 字段 | 值 |
|------|----|
| 名称 | `Modbus网关_gw1` |
| 类型 | `TCP 客户端设备网关` |
| 网络组件 | 选择上一步创建的 TCP Client |
| 协议 | `Modbus RTU (TCP 透传)` |

保存后点击**启动**，状态变为"运行中"。

---

## 7. 第四步：创建产品

需要创建**两个**产品：Modbus 网关产品 和 Modbus 从机产品。

### 4.1 Modbus 网关产品

平台菜单 → **设备管理 → 产品 → 新增**：

| 字段 | 值 |
|------|----|
| 产品名称 | `Modbus网关` |
| 设备类型 | `网关设备` |
| 传输协议 | `TCP` |
| 消息协议 | `Modbus RTU (TCP 透传)` |
| 接入方式 | 选择上一步创建的接入网关 |

创建后进入产品 → **设备接入**，填写网关级协议参数（可保持默认）：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `responseTimeoutMs` | `3000` | 单次请求超时（毫秒），复杂从机可适当增大 |
| `probeIntervalMs` | `30000` | 探测周期（毫秒），越小越灵敏但占用总线更多 |
| `keepOnlineTimeout` | `120` | 无流量在线保活时长（秒） |
| `secureKey` | （留空） | 可选软校验密钥，通常留空 |

### 4.2 Modbus 从机产品

平台菜单 → **设备管理 → 产品 → 新增**：

| 字段 | 值 |
|------|----|
| 产品名称 | `Modbus从机_温度传感器`（按实际设备命名） |
| 设备类型 | `直连设备`（子设备） |
| 传输协议 | `TCP` |
| 消息协议 | `Modbus RTU (TCP 透传)` |
| 接入方式 | 与网关产品相同的接入网关 |

创建后进入产品 → **设备接入**，填写从机级协议参数：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `slaveId` | `1` | 从机地址（1~247），**每个从机必须唯一** |
| `probeFunctionCode` | `3` | 探测使用的功能码，通常填 `3`（读保持寄存器） |
| `probeStartAddress` | `0` | 探测起始寄存器地址 |
| `probeQuantity` | `1` | 探测读取的寄存器个数 |
| `registerMap` | （见下文） | 寄存器映射，通过表格编辑器填写 |

---

## 8. 第五步：配置物模型

物模型（属性）的 `propertyId` 必须与 `registerMap` 中的 `propertyId` **完全一致**，平台才能将寄存器原始值映射为业务属性。

### 在线编辑

产品 → **功能定义 → 物模型 → 属性 → 新增**，按下方示例逐条添加。

### 批量导入

产品 → **功能定义 → 导入物模型**，粘贴下方 JSON（见 [附录E](#附录e物模型模板json)），按需修改。

**常用类型映射**：

| Modbus `dataType` | 物模型数据类型 | JetLinks 类型字符串 |
|-------------------|---------------|---------------------|
| `INT16` / `UINT16` | 整数 | `int` |
| `INT32` / `UINT32` | 长整型 | `long` |
| `FLOAT32` / `FLOAT64` | 浮点数 | `float` / `double` |
| `BIT` | 布尔 | `boolean` |

---

## 9. 第六步：创建设备

### 9.1 创建 Modbus 网关设备

产品选择"Modbus网关" → **设备实例 → 新增**：

| 字段 | 值 |
|------|----|
| 设备ID | `mb_4000`（建议以网口命名，唯一） |
| 设备名称 | `车间A Modbus网关` |
| 产品 | `Modbus网关` |

> 网关设备创建后**不需要**手动配置父设备，它本身就是父节点。

### 9.2 创建 Modbus 从机设备

产品选择"Modbus从机_xxx" → **设备实例 → 新增**：

| 字段 | 值 |
|------|----|
| 设备ID | `mb_4000_1`（建议格式：`{网关ID}_{slaveId}`） |
| 设备名称 | `1号温度传感器` |
| 产品 | 对应从机产品 |
| 父设备 | `mb_4000`（选择对应网关设备） |

> **批量创建**：参见 [附录D](#附录d设备导入模板csv) 的 CSV 导入模板。

---

## 10. 第七步：配置寄存器映射

### 为什么要配置寄存器映射？

Modbus 协议本身不携带语义，只有寄存器地址和原始数值。寄存器映射表告诉平台：

> "读地址 0x0000 的保持寄存器，得到 2 字节整数，乘以 0.1，这就是属性 `temperature` 的值。"

### 配置入口

从机产品 → **设备接入** → 找到"寄存器映射"字段 → 点击**表格编辑器**（前端已集成）。

### 表格字段说明

| 列名 | 必填 | 说明 |
|------|------|------|
| 属性ID | ✅ | 与物模型属性的 `propertyId` 完全一致 |
| 功能码 | ✅ | 见下方功能码说明 |
| 起始地址 | ✅ | 寄存器起始地址（十进制，0 起） |
| 数量 | 自动 | 寄存器个数；INT32/FLOAT32 自动为 2，可留空 |
| 数据类型 | ✅ | 见数据类型说明 |
| 字节序 | 默认ABCD | 见字节序说明；大多数设备默认 `ABCD` |
| 比例系数 | 默认1 | 原始值 × scale = 最终值（用于单位换算） |
| 可写 | 否 | 勾选后平台允许下发写属性指令 |

### 示例配置（温度传感器）

| 属性ID | 功能码 | 起始地址 | 数量 | 数据类型 | 字节序 | 比例系数 | 可写 |
|--------|--------|---------|------|----------|--------|---------|------|
| temperature | 0x03 保持寄存器(读) | 0 | 1 | INT16 | ABCD | 0.1 | 否 |
| humidity | 0x03 保持寄存器(读) | 1 | 1 | INT16 | ABCD | 0.1 | 否 |
| setpoint | 0x03 保持寄存器(读) | 10 | 1 | UINT16 | ABCD | 1 | 是 |
| alarm | 0x01 线圈(读) | 0 | 1 | BIT | ABCD | 1 | 否 |
| relay | 0x01 线圈(读) | 1 | 1 | BIT | ABCD | 1 | 是 |

---

## 11. 验证在线与数据

### 检查步骤

1. **网关在线**：设备列表中网关设备状态变为"在线"（TCP 连接建立后即上线）。

2. **从机在线**：从机设备在探测帧收到响应后变为"在线"。默认探测周期 30 s，若 30 s 内未变在线，先检查：
   - 从机 `slaveId` 是否与硬件一致
   - 探测地址（`probeStartAddress`/`probeQuantity`）是否有效

3. **读取属性**：
   - 设备详情 → **属性** → 点击**刷新**
   - 平台下发 `ReadPropertyMessage`，从机返回值后属性更新

4. **写入属性**：
   - 在"可写"属性行点击**设置**，输入值后提交
   - 查看设备日志确认 FC06 / FC16 帧已发送并收到 echo

### 日志排查

```
# 平台控制台日志关键词
Modbus encode  → 请求帧已构建
Modbus decode  → 响应帧已解析
sweepExpired   → 发现超时请求（说明从机无响应）
not found      → registerMap 中找不到对应属性（检查 propertyId 拼写）
```

---

## 附录A：寄存器映射字段参考

寄存器映射最终以 **JSON 数组**形式存储在从机产品配置中（字段名：`registerMap`）。  
前端表格编辑器会自动序列化/反序列化。若需手动填写或通过 API 导入，格式如下：

```json
[
  {
    "propertyId": "temperature",
    "functionCode": 3,
    "address": 0,
    "quantity": 1,
    "dataType": "INT16",
    "byteOrder": "ABCD",
    "scale": 0.1,
    "offset": 0,
    "writable": false
  },
  {
    "propertyId": "pressure",
    "functionCode": 3,
    "address": 2,
    "quantity": 2,
    "dataType": "FLOAT32",
    "byteOrder": "CDAB",
    "scale": 1,
    "writable": false
  },
  {
    "propertyId": "relay",
    "functionCode": 1,
    "address": 0,
    "quantity": 1,
    "dataType": "BIT",
    "writable": true
  }
]
```

**字段名别名（均被解析器接受）**：

| 标准字段 | 可用别名 |
|----------|----------|
| `propertyId` | `property`, `id` |
| `functionCode` | `fc` |
| `address` | `addr` |
| `quantity` | `qty` |
| `dataType` | `type` |
| `byteOrder` | `order` |

---

## 附录B：功能码速查表

| 功能码 | 十六进制 | 操作 | 数据对象 | 最大数量 |
|--------|---------|------|---------|---------|
| 1 | `0x01` | 读 | 线圈（可读写位） | 2000 |
| 2 | `0x02` | 读 | 离散输入（只读位） | 2000 |
| 3 | `0x03` | 读 | 保持寄存器（可读写字） | 125 |
| 4 | `0x04` | 读 | 输入寄存器（只读字） | 125 |
| 5 | `0x05` | 写单个 | 线圈 | 1 |
| 6 | `0x06` | 写单个 | 保持寄存器 | 1 |
| 15 | `0x0F` | 写多个 | 线圈 | 1968 |
| 16 | `0x10` | 写多个 | 保持寄存器 | 123 |

**使用原则**：
- 读模拟量（温度、压力、流量）→ FC 03 或 FC 04
- 读开关量状态 → FC 01 或 FC 02
- 写单个值 → FC 06（寄存器）或 FC 05（线圈）
- 批量写（V1 暂不支持，每次只处理第一个属性） → FC 16 / FC 15

---

## 附录C：数据类型与字节序说明

### 数据类型

| `dataType` 值 | 寄存器数 | 字节数 | 说明 |
|---------------|---------|--------|------|
| `BIT` | 1（按位） | 1 | 线圈 0/1，配合 FC01/FC02/FC05/FC0F |
| `INT16` | 1 | 2 | 有符号 16 位整数（-32768~32767） |
| `UINT16` | 1 | 2 | 无符号 16 位整数（0~65535） |
| `INT32` | 2 | 4 | 有符号 32 位整数 |
| `UINT32` | 2 | 4 | 无符号 32 位整数 |
| `FLOAT32` | 2 | 4 | IEEE 754 单精度浮点 |
| `INT64` | 4 | 8 | 有符号 64 位整数 |
| `FLOAT64` | 4 | 8 | IEEE 754 双精度浮点 |

### 字节序（`byteOrder`）

Modbus 规范每个寄存器是大端序（高字节先），但多个寄存器组合时字序（word order）各厂商不同。

| `byteOrder` | 字节排列 | 说明 |
|-------------|---------|------|
| `ABCD` | 高字先，字内大端 | **标准大端**，PLC 常用，不确定时优先试此值 |
| `CDAB` | 低字先，字内大端 | 字节交换（"中间字节交换"），部分施耐德/ABB 设备 |
| `BADC` | 高字先，字内小端 | 字交换，部分欧姆龙设备 |
| `DCBA` | 低字先，字内小端 | 完全小端，部分 WAGO 设备 |

> **判断方法**：用 Modbus 调试工具（如 Modbus Poll）读同一地址，对比原始 16 进制与期望物理值，即可确认字节序。

### `scale` 与 `offset`

```
最终值 = 原始整数值 × scale + offset
```

| 场景 | scale | offset | 示例 |
|------|-------|--------|------|
| 寄存器存 × 10 的温度（如 253 = 25.3°C） | 0.1 | 0 | `scale: 0.1` |
| 4-20mA 映射为 0-100% | 按比例 | 按偏置 | 视传感器量程计算 |
| 直接存物理值 | 1 | 0 | 默认值 |

---

## 附录D：设备导入模板（CSV）

### 网关设备导入（CSV）

保存为 `modbus_gateway_import.csv`，通过 **设备管理 → 设备实例 → 导入** 上传：

```csv
id,name,productId,parentId
mb_4000,车间A Modbus网关,modbus-gateway-product,
mb_4001,车间B Modbus网关,modbus-gateway-product,
mb_4002,车间C Modbus网关,modbus-gateway-product,
```

> 字段说明：
> - `id`：设备唯一标识，建议 `mb_{端口/名称}` 格式
> - `name`：显示名称，任意
> - `productId`：网关产品的"产品ID"（在产品详情页可查看）
> - `parentId`：网关设备无父设备，留空

### 从机设备导入（CSV）

保存为 `modbus_slave_import.csv`：

```csv
id,name,productId,parentId
mb_4000_1,1号温度传感器,modbus-slave-temp,mb_4000
mb_4000_2,2号温度传感器,modbus-slave-temp,mb_4000
mb_4000_3,1号压力传感器,modbus-slave-pressure,mb_4000
mb_4001_1,B区温度传感器,modbus-slave-temp,mb_4001
mb_4001_5,B区变频器,modbus-slave-vfd,mb_4001
```

> 字段说明：
> - `id`：建议格式 `{网关ID}_{slaveId}`，便于运维定位
> - `productId`：从机对应产品的 ID
> - `parentId`：**必填**，填对应网关设备 ID（否则平台找不到 TCP 会话）

### 导入后补全设备配置

CSV 导入**不包含设备级配置**（如 `slaveId`），导入后需手动补全，或通过 API 批量写入：

```bash
# 为从机设备设置 slaveId（以设备 mb_4000_1 为例，slaveId=1）
curl -X PATCH "http://localhost:8848/api/device/instance/mb_4000_1/config" \
  -H "Content-Type: application/json" \
  -H "X-Access-Token: <token>" \
  -d '{"slaveId": 1}'
```

**批量脚本示例**（根据设备 ID 后缀自动提取 slaveId）：

```bash
#!/bin/bash
TOKEN="<your_token>"
BASE="http://localhost:8848/api"

# 设备 ID 格式：mb_{网关}_{slaveId}
for DEV_ID in mb_4000_1 mb_4000_2 mb_4000_3; do
  SLAVE_ID="${DEV_ID##*_}"
  curl -s -X PATCH "${BASE}/device/instance/${DEV_ID}/config" \
    -H "Content-Type: application/json" \
    -H "X-Access-Token: ${TOKEN}" \
    -d "{\"slaveId\": ${SLAVE_ID}}" > /dev/null
  echo "Set slaveId=${SLAVE_ID} for ${DEV_ID}"
done
```

---

## 附录E：物模型模板（JSON）

可直接在产品 → **功能定义 → 导入物模型** 中粘贴，按需删改。

### 通用温湿度传感器（FC03，INT16×10 = 实际值）

```json
{
  "properties": [
    {
      "id": "temperature",
      "name": "温度",
      "valueType": {
        "type": "float",
        "unit": "摄氏度",
        "min": -40,
        "max": 125,
        "decimalPlace": 1
      },
      "expands": {
        "readOnly": true,
        "storageType": "direct-tdengine"
      }
    },
    {
      "id": "humidity",
      "name": "湿度",
      "valueType": {
        "type": "float",
        "unit": "%RH",
        "min": 0,
        "max": 100,
        "decimalPlace": 1
      },
      "expands": {
        "readOnly": true,
        "storageType": "direct-tdengine"
      }
    }
  ],
  "functions": [],
  "events": [],
  "tags": []
}
```

### 变频器（FC03，FLOAT32+BIT 混合）

```json
{
  "properties": [
    {
      "id": "outputFrequency",
      "name": "输出频率",
      "valueType": {
        "type": "float",
        "unit": "Hz",
        "min": 0,
        "max": 60,
        "decimalPlace": 2
      },
      "expands": { "readOnly": true }
    },
    {
      "id": "outputVoltage",
      "name": "输出电压",
      "valueType": {
        "type": "float",
        "unit": "V",
        "decimalPlace": 1
      },
      "expands": { "readOnly": true }
    },
    {
      "id": "outputCurrent",
      "name": "输出电流",
      "valueType": {
        "type": "float",
        "unit": "A",
        "decimalPlace": 2
      },
      "expands": { "readOnly": true }
    },
    {
      "id": "runStatus",
      "name": "运行状态",
      "valueType": {
        "type": "boolean",
        "trueText": "运行中",
        "falseText": "停止"
      },
      "expands": { "readOnly": true }
    },
    {
      "id": "faultCode",
      "name": "故障代码",
      "valueType": { "type": "int" },
      "expands": { "readOnly": true }
    },
    {
      "id": "frequencySetpoint",
      "name": "频率设定值",
      "valueType": {
        "type": "float",
        "unit": "Hz",
        "min": 0,
        "max": 60,
        "decimalPlace": 2
      },
      "expands": { "readOnly": false }
    }
  ],
  "functions": [],
  "events": [
    {
      "id": "faultOccurred",
      "name": "故障发生",
      "level": "warn",
      "valueType": {
        "type": "object",
        "properties": [
          { "id": "faultCode", "name": "故障代码", "valueType": { "type": "int" } }
        ]
      }
    }
  ],
  "tags": []
}
```

### 多路继电器控制器（FC01读线圈 + FC05写线圈）

```json
{
  "properties": [
    {
      "id": "relay1",
      "name": "继电器1",
      "valueType": {
        "type": "boolean",
        "trueText": "闭合",
        "falseText": "断开"
      },
      "expands": { "readOnly": false }
    },
    {
      "id": "relay2",
      "name": "继电器2",
      "valueType": {
        "type": "boolean",
        "trueText": "闭合",
        "falseText": "断开"
      },
      "expands": { "readOnly": false }
    },
    {
      "id": "relay3",
      "name": "继电器3",
      "valueType": {
        "type": "boolean",
        "trueText": "闭合",
        "falseText": "断开"
      },
      "expands": { "readOnly": false }
    },
    {
      "id": "relay4",
      "name": "继电器4",
      "valueType": {
        "type": "boolean",
        "trueText": "闭合",
        "falseText": "断开"
      },
      "expands": { "readOnly": false }
    }
  ],
  "functions": [],
  "events": [],
  "tags": []
}
```

**配套 `registerMap`（从机产品设备接入填写）：**

```json
[
  {"propertyId":"relay1","functionCode":1,"address":0,"dataType":"BIT","writable":true},
  {"propertyId":"relay2","functionCode":1,"address":1,"dataType":"BIT","writable":true},
  {"propertyId":"relay3","functionCode":1,"address":2,"dataType":"BIT","writable":true},
  {"propertyId":"relay4","functionCode":1,"address":3,"dataType":"BIT","writable":true}
]
```

---

## 常见问题排查

### Q: 网关设备始终"离线"

**可能原因 & 解决方法**：

1. TCP 连接未建立：确认网络可达（`telnet <ip> <port>`）；检查防火墙规则。
2. 协议包未启用：系统管理 → 协议管理，确认状态为"已启用"。
3. 接入网关未启动：设备接入页面，点击"启动"。
4. 超时参数过短：在平台启动参数中加 `-Dgateway.tcp.network.connect-check-timeout=300s`（尤其 TCP Client 模式网关响应慢时）。

---

### Q: 从机设备始终"离线"

**可能原因 & 解决方法**：

1. `slaveId` 配置错误：进入设备配置，确认 `slaveId` 与硬件一致（十进制）。
2. 探测地址无效：部分从机只有特定地址段有寄存器；尝试调整 `probeStartAddress`。
3. 探测功能码不支持：部分从机只支持 FC04（输入寄存器），将 `probeFunctionCode` 改为 `4`。
4. 总线碰撞：多个平台实例或其他主机同时访问同一总线，改为单主机独占。

---

### Q: 属性值读取失败（日志显示 not found）

`registerMap` 中没有该属性 ID 的映射条目。检查：
- `registerMap` 中的 `propertyId` 与物模型的属性 ID **大小写完全一致**
- 从机产品配置已保存（点击"保存"按钮）

---

### Q: 读出的数值不对

1. **比例系数错**：确认 `scale` 是否正确（如原始值 253 对应 25.3°C，则 `scale=0.1`）。
2. **字节序错**：用 Modbus Poll 读原始十六进制，手动用不同字节序解析，找到正确的 `byteOrder`。
3. **数据类型错**：INT16 与 UINT16 区别仅在最高位符号位；负数场景确认是否用 INT16。
4. **地址偏移**：部分厂商文档中地址从 1 开始，而协议地址从 0 开始，需减 1。

---

### Q: 写属性无响应

1. 物模型属性未设置"可写"（`readOnly: false`）。
2. `registerMap` 对应条目 `writable` 未设置为 `true`。
3. 写操作需要 FC 05/06/0F/10；读功能码（FC01~04）对应的属性不能写。

---

### Q: 超时后网关总线卡死，后续所有请求无响应

已在 `ModbusRtuCodec` 中实现 `sweepAllExpired()` 定时清除超时请求。若仍发生：
- 增大 `responseTimeoutMs`（如改为 5000）
- 检查是否存在其他主机占线
- 重启接入网关（操作 → 停止 → 启动）

---

*最后更新：2026-04-17 | 协议版本：modbus-rtu.v1*
