# GpLink2 测试服累计表码与 TimescaleDB 实测记录（2026-09-24）

## 范围与环境

- 源码工作区：`E:\gplink_git\gplink`。后端从独立候选工作树 `E:\gplink_git\gplink-counter-isolated-20260924` 构建；候选仅叠加本轮 Timescale 原始/CAGG、计数器快照、差值 API、配置页和本轮失败修复。候选状态扫描无 TCP/Modbus/gpSaaS 等无关路径；v14 额外修复的 13 个明确源码/测试文件已逐项哈希同步回原工作区，候选与原工作区本轮源码无差异。原工作区的其他未提交改动未带入 JAR。未提交或推送。
- 测试服：`192.168.5.19`，NSSM `GpLink2` 运行中，`D:\GpLink2\application.jar`。前端 `D:\tool\nginx-1.26.3\html\gpLink2`，Nginx 2 进程、HTTP 200。运行配置连接 `gplink`。
- 当前数据库：PostgreSQL **16.13** / TimescaleDB **2.26.1**。目标验收版本是 PostgreSQL 16 / TimescaleDB **2.29.1**；本文所有数据库实测只能算 **2.26.1 辅助验证**。
- 按用户最新说明，实际联调使用测试服已有的 `gplink` PostgreSQL 数据库；未另建数据库。所有新写入均限于以 `codex_counter_` 开头、带 `20260924` 日期标识的隔离产品/设备及其属性；该数据库 URL 未用于 `cagg.test.url`。
- 测试服服务器时钟比本机高约 **4 分 56 秒**；记录中的任务/日志时间均以测试服北京时间为准，整点延迟的真实时钟精度不能由本机对表推定。
- 隔离产品：主产品 `codex_counter_20260924_1059`，属性 `counter_kwh`，设备 `codex_counter_20260924_d001` 至 `d052`；另有负例产品 `codex_counter_negative_20260924_1325`、4 个月补报产品 `codex_counter_late4m_20260924_1525`、写入守卫产品 `codex_counter_writeguard_20260924_1605`。测试设备均离线，无真实设备连接；仅这些明确标识的对象被新增/改写。
- 数据库备份：`D:\GpLink2\backup\gplink-pre-counterfix-20260924.dump`，`pg_dump -Fc -Z 6` 成功，56,229,723 字节，SHA256 `68156F8D562DBD48EC0FB4E11D60BA150A1FF8EF618CAF7CDA8556CB49B0B19A`，`pg_restore --list` 退出 0；未执行恢复。较早 JAR 回滚点 `D:\GpLink2\backup\application-20260924-142040.jar`。未执行离线安装/重装脚本，未删除或重建任何现有数据库对象，未把业务库 URL 传给 `cagg.test.url`。
- 空值分母直写测试前另备份 `D:\GpLink2\backup\gplink-pre-avg-null-20260924-134649.dump`，56,412,596 字节，SHA256 `16B52D6079459386D6C5F03339CF59815197259DAD86D49B9EA7D84ED32E5B6B`，`pg_restore --list` 5146 行、退出 0。测试仅向隔离设备 d003 的唯一 09:20 原始时间键插入一行显式 NULL。
- 日/月补报修复版部署前，按实际启用的 `local` 配置再次只读备份当前 `gplink`：`D:\GpLink2\backup\gplink-pre-cagg-refresh-v10-20260924.dump`，56,513,873 字节，SHA256 `DB63256726D125ABBCAFED91B348832FF208038F3439AC9D8DC664DDAD89001B`；`pg_restore --list` 5161 行、退出 0。未恢复。此前只看 `application.yml` 会落到 `jetlinks` 默认库并认证失败，现已核对 `application-local.yml` 的有效覆盖值，凭据未输出。
- v11 部署前对当前测试库再次只读备份：`D:\GpLink2\backup\gplink-pre-cagg-refresh-v11-20260924.dump`，56,535,471 字节，SHA256 `6339CD7FD94E94AD825E7A65BEFF3ACB07C0C42E7E1EEE5C9B2774AEF240AE04`，`pg_restore --list` 5161 行、退出 0；未恢复。
- v14 部署前对包含上述隔离测试数据的当前 `gplink` 库做 `pg_dump -Fc -Z6`：`D:\GpLink2\backup\gplink-pre-cagg-refresh-v14-20260924.dump`，56,717,213 字节，SHA256 `FA2B0539926D2BBB0E1887458FFAA0570260175B5EC7D85A87C14FCBC5D31D4C`，`pg_restore --list` 5340 行、退出 0；未恢复。v12 部署前备份 `D:\GpLink2\backup\gplink-pre-cagg-refresh-v12-20260924.dump`，56,644,624 字节，SHA256 `10E360240D213E9DCC53797E1F64707A609C862525FD063E0AA712BFE363F5F2`，同样通过 `pg_restore --list`。
- 当前后端 v14 JAR SHA256 `7157568230F9783EFF08B1A7772291A59F42ECF4F209553CF734B0A0A8942007`，274,667,963 字节；16:31:41 远端读回与隔离候选包一致，NSSM `Running`、`/actuator/health` HTTP 200 `UP`。部署脚本紧接重启时健康连接失败，后续独立 HTTP 请求通过。v12 JAR 回滚点 `D:\GpLink2\backup\application-20260924-162603.jar`，v11 为 `D:\GpLink2\backup\application-20260924-160045.jar`。前端复查 828 文件、49,814,778 字节，按 `relative/path|SHA256|length` 排序的清单 SHA256 `5284C14488BA37FF1DBC19B7A0D0D2CBC704708B98300F4D2AB239410DFEB911`；`index.html` SHA256 `F27A2AE3214CBAAECF801989EB425E195353B8E426ED9E51921E18CC0695D724`。
- 最早保留的本日部署前 JAR 副本 `D:\GpLink2\backup\application-20260924-105205.jar` 独立只读哈希 SHA256 `1E69E45DFFE553621C526ECA095F0E313B21141D558A41FEDB6AF642123BD723`，274,526,802 字节；它提供回滚基线，但不能凭文件名推定其中包含本轮源码。本报告以每次构建/部署前后的实测哈希判断实际版本。

## 先修复的实际失败

| 失败证据 | 根因与改动 | 修复后读回 |
|---|---|---|
| 有效 `PUT history-statistics/counter_kwh` HTTP 500，SQLSTATE 25006；另一次 PUT 200 但 GET 空 | `CounterSnapshotService.writeRows` 的 `RETURNING` 在只读事务/首行取消时未可靠提交；改为显式可写事务并完整消费，相关写入合入事务 | PUT 200，GET 配置持久化；13:18 后最近 6000 行日志未再见 25006 |
| 取消任务后 task CANCELLED，config 仍 PENDING 且保留 taskId | 同一事务清除匹配配置任务指针/待处理范围；重复取消幂等 | POST 200，GET task CANCELLED、config IDLE 且无 taskId/pending |
| 批量创建 51 台设备后 roster 0 | 插入发布 `EntityCreatedEvent`，同步器原只监听 Saved/Modify；增加 Created | 新建 d052 自动进入 roster，52/52；不需部署设备 |
| REBUILD `SUCCESS 51/51`、DIRTY `SUCCESS 665/665`，hour/day 快照均 0；52 台设备任务还可能在 50 台分页后假成功 | `writeSnapshots` 在异步读取前构造空批次，改 `Mono.defer`；翻页条件改显式布尔分支 | REBUILD 52/52 写入 52 行；25 边界任务 1300/1300；当天 14 边界 728/728、逐时 52/52 |
| 无值的差值字段在全局 NON_NULL 序列化下消失 | DTO 显式 `JsonInclude(ALWAYS)` | HTTP 200 响应含 `startValue:null,endValue:null` |
| 显式 null 通过旧 `history/write` 返回 HTTP 500 | DTO 的 value/timestamp 本来要求非空，`Map.of` 遇 null NPE；改为整批写入前校验，非法输入应返回 400 且无部分写入 | v8：14:10:38 纯 null 和 14:10:48 有效值后接 null 均 HTTP 400 `bad_request`；14:11:07 独立 SQL 确认 d004 原始记录、三个目标键、脏标记均 0，版本未变。3 个定向单测通过。 |
| 外部原始表直写后手工 `_rebuild` 假成功，CAGG 未刷新 | 原任务 `a0f51ee8…` 13:51:40 `SUCCESS 104/104`，但 raw d003 09 桶 3 行、hour CAGG `sample_count=2`，刷新证明仍为 13:31:40/source_version=4；旧证明被复用。修复为建任务时递增 source_version、旧证明不能复用，显式物理刷新 `force=>true`，刷新失败不得记 SUCCESS；运行范围内快照读回走新鲜回退 | v8：新任务 `059e1626…` POST/GET 均 HTTP 200，14:13:45 `SUCCESS 104/104`；独立查库 14:14:37 确认 raw 3/数值2、小时 CAGG **实体表与视图**均 `sample_count=3,numeric_count=2,sum=12`，刷新证明 source_version=5/14:13:45.916；10:00 快照 9@09:30/source_version5。API COUNT3/AVG6，同一旧缓存键复查 COUNT3。2 项回归测试通过。 |
| 手工重算取消后旧快照可能重新命中 | 重算创建时 source_version 加1并将范围挂在 config.pending；取消清除 pending，未处理旧快照仍可见。v9 快照读取加入未成功 REBUILD 任务范围/版本反连接，新增部分索引；任务历史须保留以维持失效证明 | v9：重算任务 `38bf8d05…` POST 200 后立即取消 POST 200、`CANCELLED 0/104`，config IDLE/source_version6；旧 d003 10:00 快照仍在、source_version5，但相同快照读取 SQL 返回0行，随后 interval/history HTTP200 仍为 null→9。14:29:17 单边界 EXPLAIN 使用新索引，执行0.167ms、shared hit7；3项回归测试通过。恢复任务 `2e4c25a5…` HTTP200、14:31:37 `SUCCESS 104/104`，14:32:55 查新快照版本7、值9@09:30，快照 SQL 重返1行，CAGG仍3/2。 |
| 非法配置属性/不存在任务被泛化为 HTTP 500 | 异常处理保留 `ResponseStatusException` 状态 | 非数值属性 PUT 400 `bad_request`；跨产品/不存在 task GET 404 `not_found` |
| `AGGREGATION.md` 有 3 年示例，与 5 年配置冲突 | 文档两处改 5 年 | 当前文档与模板、数据库保留策略一致 |
| 五天补报 DIRTY `SUCCESS` 但历史完整日聚合空 | 25 小时批次跨午夜，原日刷新只挑单批内完整日，故日日跳过；原代码没有月层刷新。v10 改按北京时间闭合日、成熟月分别刷新 hour→day→month，要求证明连续覆盖整窗；沿用 CAGG 结构/策略兼容检查，不兼容回退 raw，物理刷新错误传播供任务重试 | 本地无需 DB 的 CounterSnapshot*Test 17/17；v10 JAR 15:13:06 已读回匹配并健康 HTTP200。当时自然迟到复验未完成；后续 v11 和 v14 通过，见最新增补。 |
| v10 再补报仍跳过日刷新 | d005 9/19 09:51=6 于15:17:57写入 HTTP200，DIRTY `bfaa1acd…` 15:18:26 `SUCCESS 126/126`；raw 两行 sum11/last6、小时实体 sum11/count2、快照值6，但 DAILY proof/实体0。完整 1d LAST 空、SUM0；部分日 LAST6/SUM11、部分月 LAST6/SUM11。根因 `hasRefreshProof` SQL 比较 `from_time<to AND to_time>from` 的参数误传 `(from,to)`，刚发布的小时证明复查总是 false，日层未进入 | v11 修正参数 `(to,from)` 并用真实区间过滤单测复现；CounterSnapshot*Test 17/17。v11 JAR 15:26:50 远端哈希读回一致、健康HTTP200；后续自然补报成功，见最新增补。 |
| 已有日物化过期时同键聚合可能读旧值 | 结构 ready 选路未核对补报脏范围和当前证明；旧聚合缓存键也不含源/快照发布版本，Token 对 `agg` 集合路由的名单授权不完整 | v12 新鲜度门禁及版本键/逐设备授权。d005 原始已 SUM26、日物化仍18 时同一预热请求 HTTP200 返回26；Token 允许200/越权403、撤销读回。保守全局证明影响旧历史性能，列入未达上线条件。 |
| 未发布产品或不存在属性 `history/write` HTTP200 却不落库 | 单消息异步缓冲且未校验产品/设备注册及实际元数据 | v14：未发布 HTTP409、原始表不存在；发布后有效属性 HTTP200、DB恰1行；缺失属性原输入 HTTP400、API/DB 0行。有效写入改同步批量路径。 |
| 约 5 小时补报 DIRTY `SUCCESS 6/6`，小时物化仍旧值 | `late-cagg-threshold:24` 跳过少于24边界的 CAGG 刷新 | v14 移除该跳过配置，当前日只刷新受影响的已闭合小时。d050 自然 DIRTY `df77b738… SUCCESS 6/6` 后，10:00 小时物化 SUM7.25→15.5、COUNT1→2、proof source13、dirty0。 |

## 测试矩阵

状态中的“通过”只表示当前测试服 **TimescaleDB 2.26.1** 的辅助实测；不能替代 2.29.1 验收。

| 项 | 状态 | HTTP / 数据库 / 日志证据与预期比较 |
|---|---|---|
| 1. 新产品初始化 | 通过（结构） | 创建隔离产品前 raw/hour/day/month 不存在；创建 HTTP 200 后，原始 hypertable、三层 CAGG、索引和刷新/保留策略读回存在。hour→day→month **依赖链已配置**且 `materialized_only=false`；实际物化进度见下方独立核对。 |
| 1. 5 年保留与配置 | 通过 | 运行模板 raw `retention-policy:5y`、CAGG `retention:5y`、snapshot `retention:5y`；数据库 raw/hour/day/month/hour-snapshot/day-snapshot retention job 均 `drop_after=5 years`，最近 Success、failures=0。raw 压缩 30d。 |
| 1. 既有 raw 补建 CAGG、后续开启日/月 | 未执行 | 本次测试产品从新建开始即启用三层；未在共享库拆改已有对象做旧表迁移试验。 |
| 1. 重复启动/不兼容结构回退 | 通过（部分） | 多次受控 JAR 重启服务均 Running；13:06:44 日志对四个既有不兼容 CAGG 告警并回退 raw，没有自动删除/重建；未人为制造不兼容结构。 |
| 1. 目标 DB 版本 | 环境阻塞 | 当前 TS 2.26.1，目标 2.29.1；隔离产品 hour 刷新 job 1次成功，day/month 0次，不能报三层定时物化全通过。 |
| 1. 三层实际物化核对 | 通过（隔离成熟桶） | 13:53–13:56 初始独立查实体：hour 6 行、day 1 行、当时开放的 9 月 month 0 行；视图 `materialized_only=false`，开放桶视图值不能算已物化。后续独立 4 个月补报复验：2026-05 原始两行 5+10；HOURLY/DAILY/MONTHLY 均有 source1 proof，月物化实体 SUM15/COUNT2/LAST10@05-25 09:50，视图和 API 1M 同值。见下方证据。 |
| 2. raw 与 1h/2h/day/month 聚合对账 | 通过 | d001 今日 10:50=10、11:50=12.5；d051=20、25.5。2h 桶 HTTP 200：SUM 22.5/45.5、COUNT 2/2、AVG 11.25/22.75、FIRST/TOP 10/20、LAST 12.5/25.5；raw SQL 对账一致。1d 与 1M HTTP 200，分别今日/本月值 22.5。 |
| 2. 90min、部分桶、100y | 通过（结果） | 90min HTTP 200 的 10:30 桶 SUM 22.5/45.5，2h 的 10:00 桶同值；100y HTTP 200，2000-01-01 桶 22.5。实际 raw/CAGG 路由的 SQL 访问轨迹未完整抓取。 |
| 2. 北京时间跨日/月/年、闰日及同时间排序 | 通过（已测午夜/当前月结果）/未执行（跨年、闰日、严格排序） | 25 个边界覆盖北京时间前日 00:00 至今日 00:00，日零点复用值；1M 当前月 API 返回与 raw 一致。尚未造出 2 月 29 日、跨年样本，也未单独断言两设备同时间结果顺序，不能以时区代码单测代替服务器实测。 |
| 2. 筛选/别名/限额 | 通过（所测） | 嵌套 `(d001 OR d051) AND d001` HTTP 200：d001=22.5、d051=0；别名含 SQL 标点 HTTP 200 且值正常，`time` 冲突 400；0h、反向范围、超大结果单元、两列分别 400。其余列数/桶数边界未逐一跑满。 |
| 2. 非空 AVG 分母 | 通过（修复后） | 备份后向隔离 raw d003 09:20 插入一条显式 null：raw 3 行、非空数值 2、SUM12/AVG6。旧 CAGG `sample_count=2` 暴露重算假成功；v8 手工重算 HTTP 200/任务 SUCCESS104/104 后，CAGG 物化实体及视图均 `sample_count=3,numeric_count=2,sum=12`，新缓存键 API COUNT3/AVG6；重放旧缓存键 COUNT3。 |
| 2. 5h/5d/4mo 补报 | 通过（5h、5d、4mo，见最新增补） | d001 前日23:50=9 于次日13:15补写；DIRTY `8916a616… SUCCESS 11/11`，11 个边界为9@前日23:50。d005 五天前09:50、09:51、09:52 先后补写5、6、7，每次 history/write HTTP200；v8/v10 曾 `SUCCESS` 却漏刷完整日（见上表）。v11 最终自然 DIRTY `61a0bce9…` 15:32:11 `SUCCESS 126/126`，raw三行 sum18/count3/last7@09:52；hour/day 9/19实体及视图各1行同值，source10 HOURLY proof6/DAILY proof5，小时/日零点快照=7@09:52；9月开放月份月视图1行同值，月实体/proof保持0符合实时设计。新 alias 完整1d LAST7/SUM18、部分日和部分月同值，HTTP200；旧 alias 再查完整1d LAST7，旧缓存是否同键未独立证明。4个月成熟月已实测通过，见最新增补与独立证据。 |
| 2. 监控/超时取消连接 | 通过（DB监控口径）/未执行（取消连接） | 13:56:28 按 `CaggMonitorService` 同口径 SQL：小时 failures0、运行延迟1839秒、物化水位延迟6989秒；日 0/-1/50189秒；月 0/-1/-1，`-1` 是无成功运行或空水位。`/actuator/metrics` 与 `/actuator/prometheus` 均 HTTP404，不能从 HTTP 读取指标。未做故障注入、超时取消后连接池复核。 |
| 3. 管理 API | 通过（所测） | 主/别名 GET HTTP 200；有效 PUT 200、独立 GET 持久化；_rebuild POST 200 返回 taskId，GET 任务由 PENDING 到 SUCCESS；_cancel POST 200，GET CANCELLED 且配置 IDLE。 |
| 3. 非数值属性限制 | 通过 | 负例产品 GET 配置 `[]`；`PUT /device-product/codex_counter_negative_20260924_1325/history-statistics/serial_text` HTTP 400 `bad_request`；再次 GET 仍 `[]`。 |
| 3. 首次启用/停用/重新启用/新设备 | 通过 | 首次 `startTime=1790179200000`（北京今日00:00）；停用 HTTP 200 后 hour 166/day1 行保留；再启用 AUTO 任务 `SUCCESS 52/52`，13:00 边界 52 行，config IDLE；d052 创建后自动注册。 |
| 3. 配置页真实浏览器交互 | 通过（可见项）/未执行（条件态） | 14:37 Python Playwright 实际登录 `:18088`，从产品列表打开隔离产品“历史统计配置”：显示唯一 `Counter kWh (counter_kwh · float)`、启用开关已开、IDLE/版本6、起点09-24 00:00、覆盖至14:00、最近成功14:31:37、失败“—”。点击“历史回填 / 范围重算”弹出起止时间和提交按钮；未提交。网络 GET `/api/device-product/.../history-statistics` HTTP200，打开弹窗无写请求。[页面截图](E:/gplink_git/gplink-counter-isolated-20260924/evidence/history-statistics-page-20260924.png)、[弹窗截图](E:/gplink_git/gplink-counter-isolated-20260924/evidence/history-statistics-rebuild-dialog-20260924.png)。当时 taskId 为空，进度、失败详情、取消按钮条件态未实测。 |
| 3. 属性删除/改类型后暂停 | 未执行 | 未改测试产品元数据以免破坏其余测量；源码与单测不替代服务器实测。 |
| 4. 25 小时边界和零点复用 | 通过 | `_rebuild` 前日00:00→今日00:00 HTTP 200，任务 `ebb5bb90… SUCCESS 1300/1300`（52×25），25 个小时边界各52行/52设备；今日00:00 的 hour/day 中 d001 都为9，sample_time=前日23:50；无值设备两层均 NULL/NO_VALUE。 |
| 4. 空值、幂等、覆盖更新 | 通过（所测） | 今日00:00–13:00 重算 `e36ba3cb… SUCCESS 728/728`；此前01–09每小时仅2/52的旧缺口变为每小时52/52；所有14个边界 source_version=2（后续 d003 数值写入再重算成4）。无历史设备保留 NULL 而非0，d001/d051 值和样本时间与 raw 一致。重复范围重算未增加重复主键行。 |
| 4. 取消、恢复、租约/多实例 | 通过（取消、旧快照失效和恢复）/环境阻塞（多实例） | v9 任务 `38bf8d05…` 创建/取消均 HTTP200，CANCELLED 0/104、config IDLE；旧快照行保留但 anti-join 读取0行，原始回退差值 HTTP200 null→9。随后任务 `2e4c25a5…` HTTP200/SUCCESS104/104，快照版本7且 SQL 重新返回1行。另有受控重启后 AUTO/REBUILD 正常。单实例服务，未做多实例竞争、租约抢占。 |
| 4. 整点+5分和在线只读 | 未执行（精确时序/SQL证明） | 13:00 AUTO 的 last_success=13:11:40，发生在13:09重新启用后，不能单独证明连续启用时恰好+5分；服务器时钟还快约4分56秒。在线请求的无 CAGG refresh/快照回写尚缺严格 SQL 轨迹。 |
| 5. 差值接口、别名与空值 | 通过 | 普通 `/api/v1`、`/v1` POST 均 200；d001 11:00–12:00=10→12.5，d051=20→25.5；无值 d002 明确 `startValue:null,endValue:null`；未来终点为空；反向范围400。 |
| 5. v8 显式 null 的历史差值 | 通过 | POST `/api/v1/device-data/devices/interval/day/history?startTime=1790211600000&endTime=1790215200000` HTTP200，d003 `startValue:null,endValue:9`；09:20 null 未被当零，10:00 边界取09:30的最后非空值9。 |
| 5. 分段与规整 | 通过 | day/history 08:30–23:30 HTTP 200，16个小时段，首08:00、末次日00:00；月历史按日、年历史按月，Jul–Sep 年历史 HTTP 200 三段且仅末段 end=12.5；普通区间 11:00:30–12:00:40 吸附整点。历史首段无过去值保持 null，不用未来值补过去。 |
| 5. 缺失快照回退/访问表 | 通过（值）/未执行（严格表路径） | 部分缺失段前日23:00→今日00:00 HTTP 200 `null→9`；全部缺失段前前日22:00→23:00 HTTP 200 `null→null`。满快照请求的 `pg_stat_user_tables` 快照 chunk 读取增加而 raw/CAGG chunk 未增，仅是弱指示，接口缓存未关闭，不能宣称 SQL 级零访问。 |
| 5. 普通区间首值兜底、数据库故障 | 未执行 | 历史分段无过去值保持 null 的路径已测；普通 interval 在原始起点无值且请求携带首值时的兜底，以及数据库故障明确报错，没有做受控故障注入或相应隔离请求。 |
| 6. 权限与缓存 | 通过（Token 名单、重复请求后的越权拒绝、跨产品任务、旧路径）/未执行（普通用户、跨实例、实际缓存命中计数） | 两枚仅用于隔离资源的 API Token：产品名单令本产品 GET200、另一隔离产品 GET403、d001 GET200；仅 d051 设备名单令 d051 GET200、d001/产品 GET403。产品 Token 连续两次 interval d001 HTTP200 `10→12.5`，随后设备 Token 访问相同请求 HTTP403；其 d051 请求 HTTP200 `20→25.5`，混合 d001+d051 HTTP403。未独立观测 Caffeine 命中计数，不能证明第二次真的命中。两枚测试 Token 均 POST revoke HTTP200、GET revoked/无 token。用另一产品 ID 查询/取消本隔离任务均 HTTP404，原任务仍 SUCCESS；Redis/PG latest HTTP200 同为12.5@11:50（测试时非实时），旧历史分页 total3。仅有 admin 登录凭据，普通非管理员用户未测；单实例，跨实例失效未测。配置管理真实路径不接受 API Token（403），`/api/v1` 同路径未映射（管理员亦404），单列为访问路径限制。[逐项脱敏证据](E:/gplink_git/gplink-counter-isolated-20260924/evidence/auth-scope-live-20260924.md)。 |
| 7. 固定性能场景 | 未执行 | 14:18 只读核查：隔离 roster 52 台，d001–d050 恰50台，但2026-08上月原始数据0行、日快照0行、日 CAGG 视图0行；无法组成快照齐全/部分/全部缺失和原始/CAGG 回退的同一请求。`pg_stat_statements`/`auto_explain` 未启用，SQL 日志关闭，接口 Caffeine 缓存无单请求旁路。仅对取消场景的**1设备×1边界**快照反连接独立 EXPLAIN (ANALYZE, BUFFERS)：计划3.714ms、执行0.167ms、shared hit7、read0；不可外推固定请求。未记录接口 SQL 数、读取量、P50/P95、错误率。1000台×50属性×5分钟×5年完整规模 **未验证**。 |
| 旧 `history/update` 路径 | 失败（本轮外旧接口） | d003 09:20 显式 null 的 POST HTTP 500，日志13:33:23.152 为 PostgreSQL 42P01；源码拼旧 `device_properties_*` 表及 `id/thingId/propertyId`，实际 raw 为 `properties_*` 且仅 `(deviceId,property,timestamp)` 唯一。历史查询的 id 是虚拟 MD5：d001/d051 同一11:50、不同值12.5/25.5却返回相同 id `883888c1…`，不能作行主键。gpSaaS 调用还允许改时间且无 oldTimestamp；`history/delete` 同用旧表。仅改表名/无损补全旧契约都不安全，未创建旧表或冒险改写。 |

## v14 最新实测增补（以下结果覆盖上表较早的“进行中”记录）

| 项 | 最新状态 | HTTP、数据库、任务与日志对照 |
|---|---|---|
| 1. 成熟月三层物化 | 通过（现有测试库） | 隔离产品 `codex_counter_late4m_20260924_1525`：5 月 24 日 09:50=5、25 日 09:50=10，原始两行。5 月 24 日迟到样本 `history/write` HTTP 200 后，DIRTY `9ef52d99…` 于15:47:11 `SUCCESS 24/24`；HOURLY→DAILY→MONTHLY proof 各 source1，五月月实体 SUM15/COUNT2/LAST10，API 1h/1d/1M 均 HTTP 200 且值 5/10/15 对账。日/月**策略 job**尚无自然运行，不能把手工 CALL 成功说成调度通过。[4 个月完整记录](E:/gplink_git/gplink/docs/counter-snapshot-late4m-evidence-20260924.md) |
| 2. 迟到数据查询新鲜度 | 通过（正确性）/失败（性能约束） | v12 在 d005 9 月 19 日写 09:53=8，HTTP 200；写前原始/日物化均 SUM18、proof source10，同一别名聚合 HTTP200=18。写后原始 SUM26、日物化仍18、proof仍10、dirty source11；**同一请求** HTTP200=26，证实过期 CAGG 时回退 raw 且缓存未复用旧18。随后自然 DIRTY `SUCCESS 127/127`，小时/日物化均 SUM26、proof source12、dirty0。当前全局版本门禁对不相关旧月也可能长期回退 raw，性能不达标。[逐步证据](E:/gplink_git/gplink-counter-isolated-20260924/evidence/cagg-freshness-live-v12-20260924.md)、[门禁 SQL/审查](E:/gplink_git/gplink-counter-isolated-20260924/evidence/cagg-freshness-gate-review-20260924.md) |
| 2、4. 约 5 小时迟到后的小时刷新 | 通过（v14 修复后） | d050 10:30=7.25 的 v12 DIRTY `SUCCESS 6/6` 曾因阈值24跳过刷新，小时实体0行，是**历史失败**。v14 再写 10:31=8.25，`history/write` HTTP200、原始2行 SUM15.5；写后立即物化仍7.25而同键聚合 HTTP200=15.5；自然 DIRTY `df77b738… SUCCESS 6/6`、last_success 16:37:01.369 后，10:00 小时实体和视图 SUM15.5/COUNT2/LAST8.25，HOURLY proof source13、dirty0。当前日未闭合，不计日/月物化通过；日志16:38:18 搜索该任务与 CAGG/worker 错误0匹配，成功以任务表/DB为证。[v14 HTTP/DB 证据](E:/gplink_git/gplink-counter-isolated-20260924/evidence/v14-d050-short-dirty-refresh-20260924.md)、[独立 DB 复核](E:/gplink_git/gplink-counter-isolated-20260924/evidence/v14-writeguard-cagg-db-readback-20260924.md) |
| 3、4. 4 个月快照补录与写入可靠性 | 通过（有效属性与状态守卫） | 4 个月任务 `SUCCESS 24/24`，只补 5 月 24 日 10:00 至 25 日 09:00，25 日 10:00 前截断；日快照 25 日 00:00=5。新产品**未发布**时旧版 `history/write` 曾 HTTP200/无落库；v14 独立产品 `codex_counter_writeguard_20260924_1605` 同样输入现返回 HTTP409、原始表不存在；发布后有效 `counter_kwh` HTTP200，API/DB 仅目标行12.5。不存在属性 `missing_counter` 旧版曾 HTTP200/0行，v14 同输入 HTTP400、API/DB 0行；有效新值13.5 HTTP200、DB恰1行。禁用存储属性因测试模型不存在，未执行。[v14 写入证据](E:/gplink_git/gplink/docs/counter-snapshot-history-write-v14-evidence-20260924.md) |
| 5. 差值接口与表路径 | 通过（值）/未执行（严格表访问证明） | 既有 `/api/v1`、`/v1` 普通和历史分段 HTTP200、空值 null、规整/反向400 等见上表；4 个月隔离设备普通 interval 5→10，day/history 5→5、5→10、10→10，HTTP200。缓存关闭后满/缺快照各自访问表的 SQL 级轨迹尚无证据。 |
| 6. API Token 与同键缓存 | 通过（单实例）/环境阻塞（跨实例） | v12 产品 Token 对 d001/d051 聚合 HTTP200，设备仅 d051 Token 对 d051 HTTP200、对 d001及混合请求 HTTP403；重复授权请求后越权仍403，两枚 Token 已撤销并读回。d050 固定别名同请求写前 HTTP200=0，补 10:30=7.25 后 HTTP200=7.25，原始恰1行；v14 d050 再补后同键=15.5。普通非管理员登录用户、实际缓存命中计数及跨实例失效未测。[鉴权/缓存证据](E:/gplink_git/gplink-counter-isolated-20260924/evidence/agg-auth-v12-20260924.md) |
| 7. 固定性能与完整规模 | 未执行 | 只读核实精确50台 d001–d050 2026-08-01～09-01 的 raw、日快照、日 CAGG **均0行**；无请求级缓存关闭开关、`pg_stat_statements`/`auto_explain` 未启用、SQL日志关闭，故四组固定请求的 SQL 数、读量、P50/P95、错误率无可信数据。PG16 小样本门禁 SQL `EXPLAIN` execution 0.050ms/shared hit7，只证明语法与单行计划，不代表50台；1,000台×50属性×5分钟×5年**未验证**。[性能可测性证据](E:/gplink_git/gplink-counter-isolated-20260924/evidence/performance-feasibility-20260924.md) |

## 本地与发布验证

- 独立候选工作树完整后端 v14 打包 `BUILD SUCCESS`（16:25:33，79 秒），当前远端 JAR SHA256 与其相同。既有 Timescale 定向 24/24、设备 Created 同步 1/1、差值 DTO JSON 2/2、REBUILD 旧证明/失败传播/取消后快照失效 3/3、v11 CounterSnapshot*Test 17/17；v12 聚合缓存和 Token 路由 13/13；v13 历史写入元数据 9/9；v14 聚合/门禁/快照定向 30/30。这些是本地测试，下面的测试服 HTTP/DB 是独立证据；目标 TimescaleDB 2.29.1 仍未验收。
- 前端候选构建成功、Nginx 文件清单和入口哈希读回一致；Playwright 已完成配置页只读交互与截图，活动任务的条件态 UI 尚未验证。
- 日志：初次 PUT 失败阶段 SQLSTATE25006；13:06:44 不兼容 CAGG 警告/回退；13:18 后最近6000行未见 25006；13:25:06.980 `history/write` 显式null触发 Map.of NPE；13:33:23.152 `history/update` 因旧表名报 PostgreSQL 42P01。成功快照任务没有逐任务应用日志，完成时间以任务表 `last_success` 为证据。
- `docs/gplink-legacy-api-migration-tasks.md` 明确将旧 `history/update`、`history/delete` 列为前一轮迁移未做范围；本次发现说明其与现行 Timescale 行模式不兼容。修复需先明确 gpSaaS 传入记录 `id` 的兼容语义，再采用与真实原始写入器一致的连接/事务/脏标记链；不能只把 42P01 隐藏成旧表。

## 尚未达成的上线条件

1. 必须在 PostgreSQL 16 / TimescaleDB 2.29.1 上重做目标版本数据库集成验收。当前 2.26.1 结果仅作辅助验证；月物化已在隔离产品成熟的 2026-05 桶实测 1 行/SUM15，日/月策略 job 自身尚无自然调度运行证据。
2. 配置页任务进度/失败/取消条件态、多实例竞争、普通非管理员登录用户权限、跨实例缓存失效、超时取消后的连接释放及固定 50 台上月每日用能性能场景仍缺实测。API Token 产品/设备名单已实测；4 个月补报已实测。
3. gpSaaS 仍有“空表码按零计算”消费逻辑，尚未适配；本轮只交付 GpLink2 的 null 契约，不能视为端到端结算验收。
4. 测试服时钟快约4分56秒，需校时后再验“整点延后5分钟”的精确调度。
5. 旧 `history/update` 已复现 HTTP500；`history/delete` 同用旧表及 id。若这两条接口属于上线调用链，应单独完成行模式迁移及验收。
6. v9 对取消/失败重算使用任务历史充当快照失效证明；不得在没有替代失效记录的情况下清理这些任务。单边界反连接 EXPLAIN 已通过，但大规模整月查询的计划与 P95 未验证。
7. v14 CAGG 查询的新鲜度门禁采用产品属性全局 `source_version`，新样本可能令无关的旧历史聚合证明失效并长期走原始表。局部失效需要证明区间切割、旧证明迁移以及并发发布锁，尚未完成；这是**性能未达标风险**，不可把当前极小样本的门禁 SQL 0.050 ms 外推到固定 50 台或五年规模。
