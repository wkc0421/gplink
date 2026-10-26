# GpLink2 四个月迟到表码轻载实测证据（2026-09-24）

本记录只涉及隔离产品 `codex_counter_late4m_20260924_1525`、设备 `codex_counter_late4m_20260924_1525_d001`、数值属性 `counter_kwh`。所有时间为北京时间（UTC+08）。通过 GpLink2 测试服 `192.168.5.19:8848` 的认证 API 操作；未直接写数据库、未运行 `cagg.test.url`、未删除对象。本轮只写入两个不同采样时间的值：2026-05-25 09:50=10 与 2026-05-24 09:50=5。前者因初次产品未发布而重试同一时间和值，最终原始表仍只有两行。

## 前置状态与过程

| 时间 | 实际请求或独立检查 | 实际状态和脱敏结果 | 预期对比 |
| --- | --- | --- | --- |
| 15:34:43 | GET `/device-product/codex_counter_late4m_20260924_1525`、GET `/device-instance/codex_counter_late4m_20260924_1525_d001` | 均 HTTP 404 `code=not_found` | ID 未占用，通过 |
| 15:35:09–15:35:22 | POST `/device-product`，GET 读回；POST `/api/v1/device`，GET 读回 | 四次均 HTTP 200；产品和设备 ID、设备所属产品正确；产品 `state=0`，配置 GET HTTP 200 `result=[]` | 隔离资源创建，通过 |
| 15:35:41 | POST `/api/v1/device/{deviceId}/counter_kwh/history/write`，body `[{'value':10,'timestamp':1779673800000}]` | HTTP 200 `result=[]`，但 15:37:11 原始历史 GET 仍 HTTP 200 且无 `result`；15:37:19 数据库无对应原始表 | **前置失败：HTTP 成功不代表持久化**，此时产品尚未发布，停止后续操作 |
| 15:39:21–15:39:37 | POST `/device-product/{productId}/deploy`；GET 产品；数据库只读核对 | 发布 HTTP 200 `result=1`；GET `state=1`；数据库确认 `properties_codex_counter_late4m_20260924_1525` 及小时、日、月 CAGG 已注册，原 09:50 样本仍为 0 行 | 发布后存储就绪，通过 |
| 15:39:55–15:40:13 | 重发**同一** 2026-05-25 09:50=10，GET 原始历史及数据库只读核对 | 写入 HTTP 200；GET HTTP 200 `total=1`，`timestamp=1779673800000,value=10`；数据库精确时间 1 行，配置和任务仍为 0 | 后继围栏样本确实落库，通过 |
| 15:40:24–15:42:46 | PUT `/device-product/{productId}/history-statistics/counter_kwh`，body `{'enabled':true}`；GET 配置与 AUTO 任务 | PUT/GET 均 HTTP 200，`enabled=true,startTime=1790179200000,status=IDLE`；AUTO 任务 `6ce452fc-5268-4c41-8d64-cbadb0e9e026` GET HTTP 200，`SUCCESS,processed=16,total=16,from=1790179200000,to=1790233200000,lastSuccess=1790235730478`；配置覆盖 9/24 00:00–15:00 | 首次启用从北京时间当天 00:00、AUTO 完成，通过 |
| 15:42:52–15:43:07 | POST 同一 history/write，body `[{'value':5,'timestamp':1779587400000}]`；GET 原始历史 | 写入 HTTP 200；GET HTTP 200 `total=2`，5/24 09:50=5、5/25 09:50=10 | 四个月迟到原始值落库，通过 |
| 15:47:11–15:47:53 | GET `/device-product/{productId}/history-statistics/tasks/9ef52d99-afee-4183-aa30-8a454944a416` | HTTP 200；`kind=DIRTY,status=SUCCESS,from=1779588000000,to=1779670800000,processed=24,total=24,lastSuccess=1790236031501` | 范围精确为 5/24 10:00 到 5/25 09:00 的 24 个小时边界，在后继 09:50 前截断，通过 |

上述请求体中的单引号仅用于文档展示；实际发送的是 UTF-8 JSON 双引号。所有 API 响应外层均为 `status:200,message:"success"`，除前置唯一性检查的 404。响应时间戳来自服务器，未包含密码或 Token。

## 原始数据、三层聚合与任务对账

独立数据库只读检查在 15:48:12 确认：原始表只有上述两条隔离设备记录；2026 年 5 月整月的 HOURLY、DAILY、MONTHLY 刷新证明均为 `source_version=1`，依次完成于 15:47:11.012、15:47:11.216、15:47:11.472。物化实体的 5/24 小时桶=5、5/25 小时桶=10；5/24 日桶=5、5/25 日桶=10；5 月月桶 `sum=15,count=2,last=10@5/25 09:50`。三层实时视图分别有 2、2、1 个非空桶，总和均为 15。DIRTY 快照覆盖精确 24 个小时边界，5/25 00:00 和 09:00 的边界值均为 5；没有越过后继值生成 5/25 10:00 的旧快照。变更队列已清空，任务无失败。

使用每次唯一的列别名绕过接口结果缓存，POST `/api/v1/device-data/devices/agg/_query` 的实测如下：

| 时间 | 请求范围和聚合 | HTTP | 脱敏结果 | 预期对比 |
| --- | --- | --- | --- | --- |
| 15:48:05 | 1h，5/24 09:00–11:00，LAST / SUM | 200 / 200 | 09:00 为 5 / 5；10:00、11:00 无值 / SUM 0 | 原始 5，一致，通过 |
| 15:48:05 | 1h，5/25 09:00–11:00，LAST / SUM | 200 / 200 | 09:00 为 10 / 10；后续空桶无值 / SUM 0 | 后继 10，一致，通过 |
| 15:50:04–15:50:12 | 1d，5/24 00:00–5/26 00:00，SUM / LAST | 200 / 200 | 5/24 为 5 / 5，5/25 为 10 / 10；5/26 端点空桶为 0 / 无值 | 两天与原始一致，通过 |
| 15:50:18–15:50:26 | 1M，5/1 00:00–6/1 00:00，SUM / LAST | 200 / 200 | 5 月为 15 / 10；6/1 端点空桶为 0 / 无值 | 五月两条原始值一致，通过 |

最初四个 1d/1M 查询并发发起时，本地命令工具在 10 秒处返回会话句柄，调用端未保存该句柄，因此那组请求**没有可用的最终 HTTP 证据**。随后在数据库确认无活动查询后，逐个串行重查，单次耗时约 0.7–2.5 秒并取得上表 HTTP 200；不能将第一次没有捕获输出认定为服务端超时。

差值 API（请求体 `{'devices':['codex_counter_late4m_20260924_1525_d001'],'properties':['counter_kwh']}`）：

| 时间 | 方法与路径 | HTTP 与脱敏结果 | 预期对比 |
| --- | --- | --- | --- |
| 15:50:47 | POST `/api/v1/device-data/devices/interval?startTime=1779587400000&endTime=1779673800000` | 200，`startValue=5,endValue=10` | 差值 5，通过 |
| 15:50:48 | POST `/api/v1/device-data/devices/interval/day/history?startTime=1779667200000&endTime=1779678000000` | 200，5/25 08–09 为 5→5、09–10 为 5→10、10–11 为 10→10 | 后继采样落在 09–10 段；边界与原始一致，通过 |

15:51:09 日志检查：GpLink2 服务 `Running`、PID 10344；最近 15000 行分别搜索上述 DIRTY 任务 ID、隔离产品 ID、`Counter CAGG refresh failed`，均为 0 匹配。成功路径未产生日志记录，因此完成证明以任务 API、原始读回和数据库物化/刷新证明为准。

## 已发现的前置状态问题与范围

新产品未发布时，`history/write` 返回 HTTP 200 `result=[]`，但 90 秒后原始表仍不存在。复现顺序：创建产品和设备（产品 `state=0`）→写 5/25 09:50=10→API 与数据库读回均无值；发布产品后重写同一条才落库。源码 `LegacyDeviceController.java:219–244` 将每条上报交给单条 `DeviceDataService.saveDeviceMessage`；`DeviceDataService.java:98–108` 注明单条保存可能缓冲，后续保存失败不会在此调用返回错误。这个现象不影响后续已发布产品下的四个月补报结论，但 history/write 的成功响应不能单独作为持久化证据。

本场景仅 1 台隔离设备、1 个属性、2 条原始采样、24 个 DIRTY 边界。它证明四个月迟到时的分层刷新与围栏截断，**不代表** 1,000 台×50 属性×5 分钟×5 年规模或性能验收。
