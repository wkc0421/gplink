# GpLink2 v12 `history/write` 实测证据（2026-09-24）

范围仅为测试服 `192.168.5.19:8848` 的隔离产品 `codex_counter_writeguard_20260924_1605`、隔离设备 `codex_counter_writeguard_20260924_1605_d001`。所有时间为北京时间（UTC+08）。主任务已独立核对部署的 `D:\GpLink2\application.jar` SHA-256 为 `707AD1C7AE3CBB8532A59F940140F232876A62E487944D5AB28470A6092CF51B`，服务和健康检查为 HTTP 200。本记录中的 API 与原始表证据来自部署后的实测；未直接写数据库、未改源码、未提交或推送。

## 请求和结果

| 时间 | 实际请求 / 状态 | 脱敏响应与独立读回 | 预期对比 |
| --- | --- | --- | --- |
| 16:08:17 | GET 新产品与设备 | 均 HTTP 404 `code=not_found` | 隔离 ID 未占用，通过 |
| 16:08:32–16:08:46 | POST `/device-product`、POST `/api/v1/device`；GET 产品与原始历史 | 创建和 GET 均 HTTP 200；产品 `state=0`，设备隶属该产品；`counter_kwh` 原始历史 GET HTTP 200 且无 `result` | 未发布且尚无原始数据，通过 |
| 16:08:53 | POST `/api/v1/device/{deviceId}/counter_kwh/history/write`，JSON `[ {"value":12.5,"timestamp":1790129700000} ]`（采样时间 9/23 10:15） | **HTTP 409**，`{"message":"device product is not deployed","status":409,"code":"conflict","timestamp":1790237333337}` | 未发布产品必须拒绝写入，通过 |
| 16:09:05 | GET `/device-instance/{deviceId}/property/counter_kwh/_query?pageSize=100`；独立数据库只读检查 | API HTTP 200 且无 `result`；约 16:09 数据库中原始表 `properties_codex_counter_writeguard_20260924_1605` 不存在，因此目标原始行 0 | 409 后无副作用，通过 |
| 16:12:16–16:12:21 | POST `/device-product/{productId}/deploy`；GET 产品 | 发布 HTTP 200 `result=1`，GET HTTP 200 `state=1` | 发布状态读回，通过 |
| 16:12:27–16:13:56 | 对**同一采样**再次 POST `counter_kwh/history/write`；GET 原始历史；独立数据库只读检查 | 写入 HTTP 200 `result=[]`、响应时间戳 `1790237547227`；GET HTTP 200 `total=1`，`timestamp=1790129700000,value=numberValue=12.5`；数据库在 16:13:56 核对该设备/属性/时间恰 1 行，值 12.5 | 发布后有效数值属性持久化，通过 |
| 16:13:41–16:14:20 | POST `/api/v1/device/{deviceId}/missing_counter/history/write`，JSON `[ {"value":3.5,"timestamp":1790129760000} ]`（9/23 10:16）；API 与数据库复查 | **HTTP 200** `result=[]`、响应时间戳 `1790237621061`；`missing_counter` 历史 GET HTTP 200 `total=0`，原 `counter_kwh` 仍 `total=1`；数据库精确时间/属性 `missing_counter` 为 0 行，`counter_kwh` 原采样仍 1 行 | **失败：不存在的属性被静默忽略并返回成功** |

上述产品、设备和属性名称均带测试标识；有效属性只写入一次，错误属性没有生成原始行。数据库检查由独立只读代理完成，与 API 读回一致。

## 日志与定位

16:14:06 检查日志时，GpLink2 服务为 `Running`、PID 11496。对最近 15000 行分别搜索隔离产品 ID、`missing_counter` 和 `device product is not deployed`，均 0 匹配；409 和静默忽略路径没有可用于证明持久化的日志，所以以上以 HTTP 状态及数据库读回为准。

候选 v12 源码 `LegacyDeviceController.java:232–259` 已在发送前校验产品 `registered` 状态及设备注册状态，并使用 `saveDeviceMessage(Publisher)` 批量保存，因此未发布产品的 409 和发布后有效属性的持久化符合预期。但该路径在 `LegacyDeviceController.java:256` 直接把 URL 路径参数 `property` 写入消息，没有先按设备物模型核对属性是否存在。`missing_counter` 负例给出了可复现的剩余失败：已发布的同一隔离设备，以不存在属性发有效数值和时间，接口 HTTP 200 而原始数据 0 行。本记录未修改该源码。
