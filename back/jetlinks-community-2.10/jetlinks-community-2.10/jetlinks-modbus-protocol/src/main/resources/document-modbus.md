# Modbus RTU (TCP 透传) 协议

## 协议特性

- 传输层: TCP（网关 = TCP 客户端/服务端,由平台侧的 `tcp-server-gateway` 或 `tcp-client-gateway` 选择）。
- 数据单元: Modbus RTU ADU `[slaveId][FC][data][CRC16]`,CRC16-IBM 多项式 `0xA001`。
- 主从模型: 平台作为 Master,发起请求；从机被动应答。
- 在线保活: 无心跳帧。连接存活即视为网关在线；从机在线由周期性探测 + 最近响应时间判定。

## 支持的功能码

| FC | 作用 | 对应平台消息 |
| --- | --- | --- |
| 0x01 | Read Coils | ReadPropertyMessage(bit) |
| 0x02 | Read Discrete Inputs | ReadPropertyMessage(bit, read-only) |
| 0x03 | Read Holding Registers | ReadPropertyMessage(word) |
| 0x04 | Read Input Registers | ReadPropertyMessage(word, read-only) |
| 0x05 | Write Single Coil | WritePropertyMessage(bit) |
| 0x06 | Write Single Register | WritePropertyMessage(word) |
| 0x0F | Write Multiple Coils | WritePropertyMessage(bit[]) / FunctionInvokeMessage |
| 0x10 | Write Multiple Registers | WritePropertyMessage(word[]) / FunctionInvokeMessage |

异常响应 `FC | 0x80` + exception code (0x01~0x0B) 会被解码为 `*MessageReply.setSuccess(false)` 并填入 `code = modbus-<name>`。

## 设备模型

```
Modbus 网关产品 (TCP 协议)
  └─ 网关设备   mb_<port>        parentId 为空
       └─ Modbus 从机产品
           └─ 从机子设备 mb_<port>_<slaveId>    parentId = mb_<port>, slaveId = <1..247>
```

## 产品/设备配置项

### 网关产品 (scope = product)

| key | 含义 | 默认 |
| --- | --- | --- |
| `responseTimeoutMs` | 单次请求最长等待(ms) | 3000 |
| `probeIntervalMs` | 平台探测周期(ms) | 30000 |
| `keepOnlineTimeout` | 会话保活时长(s) | 120 |
| `secureKey` | 可选软校验密钥(首帧比对) | - |

### 从机产品 (scope = product)

| key | 含义 | 默认 |
| --- | --- | --- |
| `probeFunctionCode` | 探测功能码 | 3 |
| `probeStartAddress` | 探测起始地址 | 0 |
| `probeQuantity` | 探测寄存器数 | 1 |
| `registerMap` | 寄存器映射(见下) | - |

### 从机设备 (scope = device)

| key | 含义 |
| --- | --- |
| `slaveId` | Modbus 从机地址 1~247 |

## registerMap 示例

```json
[
  {"propertyId": "temp",   "fc": 3, "address": 0, "quantity": 1,
   "dataType": "int16",   "scale": 0.1, "offset": 0},
  {"propertyId": "power",  "fc": 3, "address": 2, "quantity": 2,
   "dataType": "uint32",  "byteOrder": "CDAB"},
  {"propertyId": "run",    "fc": 1, "address": 8,
   "dataType": "bit",     "writable": true}
]
```

字段别名:
- `functionCode` / `fc`
- `address` / `addr`
- `quantity` / `qty`
- `dataType` / `type` (支持 `bit`/`int16`/`uint16`/`int32`/`uint32`/`float32`/`int64`/`float64`)
- `byteOrder` / `order` (`ABCD`(默认)/`CDAB`/`BADC`/`DCBA`)

## 限制与约定

1. **单网关串行**: 同一 TCP 连接下最多一个 in-flight 请求。由 `PendingRequestQueue` 保证,符合物理总线半双工特性。
2. **连接首次握手**: V1 不做首帧认证。TCP 连上之后必须在 `gateway.tcp.network.connect-check-timeout` 之内看到首个探测应答(默认 10s,可通过系统属性调大)。
3. **子设备定位**: V1 依赖用户手动建立 `parentId` + `slaveId` 映射,不会从字符串 `mb_<port>_<slaveId>` 自动解析。
4. **CRC**: 低字节在前,ADU 末尾 2 字节。错误 CRC 的帧会被直接丢弃。

## 平台对接要点

- TCP 网络组件的粘拆包配置建议使用 `SCRIPT` 或 `DELIMITED`,配合 `ModbusRequest.expectedResponseLength()` 决定帧边界。
- 探测帧由平台侧的定时任务发出(周期 = `probeIntervalMs`);响应到达后通过 codec.decode → `DeviceOnlineMessage` 将网关 / 从机置为在线。
- 断连后 `keepOnlineTimeout` 内无新流量将自动下线。
