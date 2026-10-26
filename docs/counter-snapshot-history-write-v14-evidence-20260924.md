# GpLink2 v14 `history/write` 回归实测（2026-09-24）

测试服为 `192.168.5.19:8848`。主任务已独立读回部署的 `D:\GpLink2\application.jar` SHA-256 `7157568230F9783EFF08B1A7772291A59F42ECF4F209553CF734B0A0A8942007`，服务及健康检查 HTTP 200。本次仅使用已建的隔离产品 `codex_counter_writeguard_20260924_1605`、设备 `codex_counter_writeguard_20260924_1605_d001`；未改源码、未部署、未提交或推送。时间均为北京时间（UTC+08）。

| 时间 | 实际 HTTP 与脱敏响应 | API / 数据库读回 | 预期对比 |
| --- | --- | --- | --- |
| 16:34:08 | GET 产品 HTTP 200 `state=1`，物模型仅有可存储数值属性 `counter_kwh`；GET 历史 HTTP 200：`counter_kwh total=1`（9/23 10:15=12.5），`missing_counter total=0` | 写入前唯一性与隔离范围确认 | 通过 |
| 16:34:17 | POST `/api/v1/device/{deviceId}/missing_counter/history/write`，body `[{"value":3.5,"timestamp":1790129760000}]`（9/23 10:16，复用 v12 的失败输入）→ **HTTP 400** `{"message":"device property not found: missing_counter","status":400,"code":"bad_request","timestamp":1790238857125}` | 16:34:24 历史 GET HTTP 200 `missing_counter total=0`；独立数据库 16:36:26 查该设备/属性/时间为 **0 行** | v12 同一请求为 HTTP 200 且静默未写；v14 明确拒绝，**通过** |
| 16:34:50 | POST `/api/v1/device/{deviceId}/counter_kwh/history/write`，body `[{"value":13.5,"timestamp":1790129820000}]`（新采样 9/23 10:17）→ **HTTP 200** `result=[]`，响应时间戳 `1790238890792` | 16:34:56 历史 GET HTTP 200 `total=2`：10:17=13.5、旧 10:15=12.5；独立数据库 16:36:26 查新采样**恰 1 行**，`value=numberValue=13.5`，旧采样仍恰 1 行/12.5 | 有效属性持久化且不影响旧值，**通过** |

独立数据库检查针对原始表 `properties_codex_counter_writeguard_20260924_1605`，按设备 ID、属性名、采样时间精确过滤；上述三组行数依次为 `missing_counter@10:16=0`、`counter_kwh@10:17=1`、`counter_kwh@10:15=1`。未直接写数据库，也未输出数据库凭据。

16:35:14 日志检查：GpLink2 `Running`、PID 10876；最近 15000 行搜索隔离产品 ID、`device property not found: missing_counter` 和 `missing_counter` 均为 0 匹配。HTTP 400 与原始表 0 行共同证明拒绝路径；成功写入以 API 和数据库读回证明。候选 v14 源码 `LegacyDeviceController.java:255–263` 使用设备物模型核对属性是否存在，并对配置为不存储的属性返回冲突。该隔离产品只有 `counter_kwh`，没有“不存储”属性，因此此分支**未执行实测**。
