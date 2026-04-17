# 各从机产品 registerMap 配置

在平台 **产品 → 设备接入 → 寄存器映射** 中填写（表格编辑器或直接粘贴 JSON）。

---

## Slave 1 — 温湿度传感器（产品：modbus-slave-temp）

**从机配置：**
- slaveId（设备级）：`1`
- probeFunctionCode：`3`
- probeStartAddress：`0`
- probeQuantity：`1`

**registerMap JSON：**
```json
[
  {"propertyId":"temperature","functionCode":3,"address":0,"quantity":1,"dataType":"INT16","byteOrder":"ABCD","scale":0.1},
  {"propertyId":"humidity",   "functionCode":3,"address":1,"quantity":1,"dataType":"INT16","byteOrder":"ABCD","scale":0.1}
]
```
> 两个属性同为 FC03、地址连续 → 定时任务一次读两个属性时自动合并为单帧 `FC03 addr=0 qty=2`

---

## Slave 2 — 压力流量变送器（产品：modbus-slave-pressure）

**从机配置：**
- slaveId（设备级）：`2`
- probeFunctionCode：`3`
- probeStartAddress：`0`
- probeQuantity：`2`

**registerMap JSON：**
```json
[
  {"propertyId":"pressure",  "functionCode":3,"address":0,"quantity":2,"dataType":"FLOAT32","byteOrder":"ABCD","scale":1},
  {"propertyId":"flow_rate", "functionCode":3,"address":2,"quantity":2,"dataType":"FLOAT32","byteOrder":"ABCD","scale":1}
]
```
> 同 FC03、地址连续 → 合并为 `FC03 addr=0 qty=4`（读 4 个寄存器得到两个 FLOAT32）

---

## Slave 3 — 电能表（产品：modbus-slave-meter）

**从机配置：**
- slaveId（设备级）：`3`
- probeFunctionCode：`3`
- probeStartAddress：`0`
- probeQuantity：`2`

**registerMap JSON：**
```json
[
  {"propertyId":"voltage",      "functionCode":3,"address":0,"quantity":2,"dataType":"FLOAT32","byteOrder":"ABCD","scale":1},
  {"propertyId":"current",      "functionCode":3,"address":2,"quantity":2,"dataType":"FLOAT32","byteOrder":"ABCD","scale":1},
  {"propertyId":"active_power", "functionCode":3,"address":4,"quantity":2,"dataType":"FLOAT32","byteOrder":"ABCD","scale":1},
  {"propertyId":"energy",       "functionCode":3,"address":6,"quantity":2,"dataType":"FLOAT32","byteOrder":"ABCD","scale":1}
]
```
> 4 个属性全为 FC03 连续地址 → 合并为 `FC03 addr=0 qty=8`（一帧读完全部）

---

## Slave 4 — 继电器控制器（产品：modbus-slave-relay）

**从机配置：**
- slaveId（设备级）：`4`
- probeFunctionCode：`1`
- probeStartAddress：`0`
- probeQuantity：`1`

**registerMap JSON：**
```json
[
  {"propertyId":"relay1",         "functionCode":1,"address":0,"quantity":1,"dataType":"BIT","writable":true},
  {"propertyId":"relay2",         "functionCode":1,"address":1,"quantity":1,"dataType":"BIT","writable":true},
  {"propertyId":"relay3",         "functionCode":1,"address":2,"quantity":1,"dataType":"BIT","writable":true},
  {"propertyId":"relay4",         "functionCode":1,"address":3,"quantity":1,"dataType":"BIT","writable":true},
  {"propertyId":"supply_voltage", "functionCode":3,"address":0,"quantity":1,"dataType":"UINT16","byteOrder":"ABCD","scale":0.1}
]
```

> ⚠️ **注意混合 FC**：relay1-4 使用 FC01，supply_voltage 使用 FC03，不能合并为单帧。  
> 定时任务需要**创建两条**：
> - 任务A：读 relay1、relay2、relay3、relay4（FC01 → 合并为 `FC01 addr=0 qty=4`）
> - 任务B：读 supply_voltage（FC03 单独一帧）

---

## Slave 5 — 变频器（产品：modbus-slave-vfd）

**从机配置：**
- slaveId（设备级）：`5`
- probeFunctionCode：`3`
- probeStartAddress：`0`
- probeQuantity：`2`

**registerMap JSON：**
```json
[
  {"propertyId":"output_freq",    "functionCode":3,"address":0,"quantity":2,"dataType":"FLOAT32","byteOrder":"ABCD","scale":1},
  {"propertyId":"output_voltage", "functionCode":3,"address":2,"quantity":2,"dataType":"FLOAT32","byteOrder":"ABCD","scale":1},
  {"propertyId":"output_current", "functionCode":3,"address":4,"quantity":2,"dataType":"FLOAT32","byteOrder":"ABCD","scale":1},
  {"propertyId":"fault_code",     "functionCode":3,"address":6,"quantity":1,"dataType":"UINT16","byteOrder":"ABCD","scale":1},
  {"propertyId":"running_status", "functionCode":1,"address":0,"quantity":1,"dataType":"BIT","writable":true}
]
```

> ⚠️ 同样混合 FC，定时任务需两条：
> - 任务A：读 output_freq、output_voltage、output_current、fault_code（FC03 → 合并为 `FC03 addr=0 qty=7`）
> - 任务B：读 running_status（FC01 单独一帧）

---

## 导入后补全设备级 slaveId

CSV 导入不含设备配置，导入后在平台 **设备详情 → 设备配置** 中逐台填写，或批量 API：

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
  echo "  set slaveId=${SID} for ${DEV}"
done
```
