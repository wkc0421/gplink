# 累计表码边界快照：配置与运行说明

## 使用范围

本功能用于产品物模型中的数值型累计采集项。在“历史统计配置”中直接选择并启用需要统计的属性，无需在物模型中额外标记。页面列出数值型属性，由用户选择实际属于累计读数的采集项；电压、瞬时功率等属性不适用于累计差值统计。旧物模型中的 `expands.cumulativeCounter` 字段不再参与资格判断，已有统计配置可继续使用。原始属性表仍保存每次上报；小时、日快照只保存边界表码和实际采样时间，不保存或修正用量。未配置或停用的采集项继续走原有原始查询路径。首次启用仅从北京时间当天 00:00 开始自动处理，更早历史必须显式提交回填任务。

## 初始化与保留

启用 `timescaledb.things-data.counter-snapshot.enabled` 仅初始化共享快照基础设施，不自动启用任何产品采集项。配置、任务和原始变更标记位于实际 TimescaleDB 数据源；小时、日快照为共享超级表。默认小时分块 30 天、日分块 365 天，保留 5 年。原始属性和现有 CAGG 的保留配置也为 5 年。保留策略按完整分块清理，实际删除时间可能晚于 5 年；已被删除的原始数据无法通过回填恢复。不自动删除或重建已有不兼容对象，出现结构告警时应先人工核验。

`timescaledb.things-data.counter-snapshot` 可配置处理周期、边界延迟、批量设备数、每批边界数、并发数、补报 CAGG 阈值和 SQL 超时；默认分别为 5 分钟、5 分钟、50、24、2、24、30 秒。保持 TimescaleDB 原始数据压缩设置，快照默认不压缩。

## 管理操作

在“产品详情 → 历史统计配置”中直接启用所需的累计数值属性。物模型页面不提供统计启停开关，Excel 导入/导出也无需携带统计标记。属性被删除或改为非数值型时，已有配置会暂停；恢复为数值型后，可在历史统计页面重新启用。

也可使用 `/device-product/{productId}/history-statistics`（别名 `/device/product/{productId}/history-statistics`）：

- `GET /`：查看配置、覆盖区间、待处理区间和最近结果。
- `PUT /{propertyId}`，请求体 `{ "enabled": true|false }`：启停配置。
- `POST /{propertyId}/_rebuild`，请求体 `{ "from": 1720000000000, "to": 1720086400000 }`：提交历史范围回填或重算，时间为 Unix 毫秒；返回任务 ID。对一天的小时快照须处理当天 00:00 至次日 00:00 的 25 个边界。
- `GET /tasks/{taskId}`：查看进度与错误；`POST /tasks/{taskId}/_cancel`：取消后续处理，已提交结果保留。

停用不删除既有快照；重新启用仅能补齐仍在保留期内的区间。外部直接修改 TimescaleDB 原始表不会经过平台写入标记，须手动提交重算。原始数据覆盖更新及补报通过平台写入时会记录变更，后台异步修正受影响边界。在线查询不会触发刷新或补录。

旧小时／日聚合视图缺少 `last_timestamp`、`numeric_count` 等字段或未通过结构与策略检查时，快照任务直接回退原始属性表，不修改或重建旧视图。原始表尚未创建时，通过目录查询识别并记录 `NO_VALUE`；目录检查后发生的真实 SQL 错误会回滚整批并保留原始错误，不在已失败的事务内继续回退。

自动任务与脏数据任务在存在可重试失败任务时不再重复新建。已有 `FAILED` 与 `PENDING/RUNNING` 并存时，先处理活跃任务，避免旧失败任务转为 `RUNNING` 时违反唯一约束；并发领取引起的活跃任务唯一约束冲突在事务回滚后最多重试两次。修复版本启动后按正常调度继续处理现有队列，无需删除任务或唯一索引；达到五次尝试的失败记录仍保留。

权限口径：普通登录用户沿用现有全局资源权限，不增加产品或设备 `orgId` 行级过滤；API Token 仍须遵守其产品/设备授权范围。缓存命中也不能绕过授权检查。

## 时间及空值契约

外部时间是 Unix 毫秒，业务边界与 `TIMESTAMP WITHOUT TIME ZONE` 值都按 `Asia/Shanghai` 解释。边界 T 采用该设备、该属性采样时间 `≤ T` 的最后一条非空原始表码；没有有效历史读数时为 `null`，不得把零或未来读数当作过去值。差值只做起止表码相减，不对清零、换表、回绕或负差值作结算修正。

`day/history` 按小时向外规整、`month/history` 按日向外规整；已对齐端点不再扩展。`year/history` 只保留首尾一分钟规整，不向外扩展整月。`week/history`、`quarter/history` 保留请求首尾，内部按自然周／季度切分。普通 `/interval` 可在 60 秒内吸附最近整点，但不能吸附到当前时刻之后，规整后若出现空区间则保留原范围。分钟级历史不规整。响应的 `startTime/endTime` 为实际使用范围；历史查询结束时间截到当前时刻，全未来范围返回每台设备的空列表。

配置内的历史端点按日／小时快照 → 可证明新鲜且语义等价的 CAGG → 原始边界分级读取，仅针对缺失边界回退；`day/history` 沿用仅小时快照路径，未配置和停用保持原始路径。普通 `/interval` 保留既有“起点无历史时使用请求内首条值”的兼容行为。历史分段沿用连续表码规则：首值缺失时由首个可用边界读数建立基线，中间边界缺值时继承上一有效读数，外侧终点缺有效读数时保留现有后查兼容逻辑（不查询当前时刻之后的样本）。全部来源均无读数时返回空值；数据库连接或超时故障应作为错误返回，不能伪装成空值。

为兼容旧 gpSaaS 按字段是否存在判断能否计算的行为，`/interval` 和 `/interval/{type}/history` 的 `startValue`、`endValue` 为空时在 JSON 中省略该字段。内部边界值仍为 `null`，不使用零或其他时刻读数伪造有效表码。此兼容方式可避免旧调用方在字段存在但值为空时将其按零相减；调用方仍须正确处理字段缺失。未执行端到端结算验证。

## 自然周与自然季度区间历史

接口：`POST /api/v1/device-data/devices/interval/{type}/history`。`startTime/endTime` 为 Query 参数（Unix 毫秒），请求体包含设备列表和单个属性 ID。

| type | 每段范围 | 内部边界 |
| --- | --- | --- |
| `week` | 自然周（周一到周日） | 北京时间每周一 00:00 |
| `quarter` | 自然季度（1—3、4—6、7—9、10—12 月） | 北京时间 1、4、7、10 月 1 日 00:00 |

起止时间落在周期中间时，不向外扩展；内部只插入严格位于首尾之间的自然边界。精确落在下一周期起点的终点不额外生成零长度段。跨年、闰年按日历处理，不用固定天数替代季度。响应为按请求设备顺序排列的分段列表，每段包含 `deviceId/property/startTime/endTime/startValue/endValue`，空值沿用既有成对省略规则。

已启用“历史统计配置”的产品属性通过现有批量边界解析器读取：零点边界优先使用 `counter_snapshot_day`，缺失边界再使用 `counter_snapshot_hour`；仍缺失时依次查询经过新鲜度检查的日／小时 CAGG、原始属性表。CAGG 定位候选采样时间后从原表点读精确文本表码。已存储的 `NO_VALUE` 是已知无值，不被当作缺失记录反复回查。非零点首尾沿用解析器的小时快照与回退逻辑，不改变用户请求时间。

不新增周／季度存储表。脏数据、待重算和刷新证明校验保持生效；在线查询不触发快照写入、刷新或回填。未配置、停用属性继续走原始边界查询。历史周／季度结果使用与月／年历史相同的长缓存策略，近期范围仍使用短缓存，缓存按类型、实际范围和配置版本隔离；命中缓存前仍执行设备授权。

以下两种请求使用相同请求体（请替换实际设备与属性 ID）：

```json
{"devices": ["device-1"], "properties": ["E"]}
```

自然周示例：2024-01-03 12:30 至 2024-01-10 18:45，北京时间。

```http
POST /api/v1/device-data/devices/interval/week/history?startTime=1704256200000&endTime=1704883500000
Content-Type: application/json

{"devices": ["device-1"], "properties": ["E"]}
```

返回两段：`01-03 12:30 → 01-08 00:00`、`01-08 00:00 → 01-10 18:45`。

自然季度示例：2024-02-29 12:30 至 2024-07-03 18:45，北京时间。

```http
POST /api/v1/device-data/devices/interval/quarter/history?startTime=1709181000000&endTime=1720003500000
Content-Type: application/json

{"devices": ["device-1"], "properties": ["E"]}
```

返回三段：`02-29 12:30 → 04-01 00:00`、`04-01 00:00 → 07-01 00:00`、`07-01 00:00 → 07-03 18:45`。每段返回累计表码供调用方计算差值。既有 `day/month/year` 分段含义保持不变，仍限制最多 500 台设备、每台 1000 段及 100000 个结果单元；底层边界批量读取还受既有 100000 个设备×边界单元限制。

## 验证记录

### 自然周／季度区间历史（2026-10-26）

- 后端相关 22 个模块编译成功，定向测试 131 项通过、0 失败、0 错误；`ContinuousAggregateIntegrationTest` 因本机 Docker 不可用整套跳过（Surefire 记为 1 个跳过项）。本轮未执行真实 TimescaleDB 快照／CAGG／原表对账或性能测量。
- 新增测试覆盖自然周／季度分段、保留部分首尾、跨年、闰日、JVM 时区、当前时刻截断、未来空范围、1000 段和结果规模限制、冷热缓存选择。
- 配置路径测试覆盖批量设备顺序、精确表码、空边界衔接、配置版本失效及缓存命中前授权；存储 SQL 测试覆盖日快照全命中、已知 `NO_VALUE`、仅对缺失边界读取小时快照、经过脏数据／重算／刷新证明检查后逐级回退，以及数据库故障传播。未配置／停用采集项继续使用原始路径。
- 回归覆盖既有分钟、小时、日、月区间语义、区间汇总、空值成对省略、API Token 路由与权限。修正既有重算测试的过时 SQL 模拟，直接验证单层强制刷新及新证明避免重复刷新；后台刷新调度未改动，原有分层刷新测试继续通过。
- 前端定向测试 20 项通过，Vite 构建成功，产物确认包含周、季度选项；输出至独立目录 `front/node_modules/.cache/gplink-interval-calendar-dist-20261026`。
- 未部署、未执行迁移或历史回填，未新增周／季度快照表。

复现命令（后端 Maven 根目录）：

```powershell
mvn -pl jetlinks-manager/saas-manager -am test '-Dtest=LegacyDeviceIntervalQueryTest,LegacyCounterIntervalRoutingTest,LegacyIntervalSummaryCompatibilityTest,LegacyDeviceDataCacheTest,DeviceIntervalDataEntityJsonTest,ApiTokenIntervalPathWebFilterTest,ApiTokenPermissionCatalogTest,CounterSnapshot*Test,PropertyBoundaryQueryTest,ContinuousAggregateIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DfailIfNoTests=false' '-Dfile.encoding=UTF-8'
```

复现命令（front 目录，构建输出使用独立的相对目录）：

```powershell
pnpm.cmd exec playwright test tests/e2e/device-data-query-utils.spec.ts --workers=1
node --max-old-space-size=8192 node_modules/vite/bin/vite.js build --outDir node_modules/.cache/gplink-interval-calendar-dist-20261026
```

目标数据库为 PostgreSQL 16 / TimescaleDB 2.29.1；其他版本的试跑仅作辅助排错，不计为目标版本验收。性能固定场景为 1,000 台设备 × 50 属性 × 5 分钟采样、查询 50 台设备上月每日用量；需关闭接口结果缓存并分别测量快照齐全、部分缺失、全部缺失路径的 SQL 数、执行计划、读取量、P50/P95。完整 5 年数据规模需单独隔离压测，缩小样本不得冒充该规模验证。实际测试结果应以执行日志和环境版本为准，不预先承诺延时或提升倍数。

### 测试服部署与运行验证（2026-10-26）

后续已将前后端部署到 `192.168.5.19`。8 项实际 API 场景通过，恢复正常启动参数后 4 类核心接口再次返回 HTTP 200。实际来源计数确认周区间 5 次日快照命中、1 次受重算保护影响的日 CAGG 回退；季度区间 3 个边界全部命中日快照。快照齐全的周范围命中 4 次日快照，相同季度请求命中缓存、没有新增边界读取。实际数据库为 PostgreSQL 16.13 / TimescaleDB 2.26.1；临时诊断端口和配置已恢复。完整请求、响应、只读 SQL、运行计数、哈希及回滚路径见 [部署验收记录](../deploy/calendar-queries-20261026-144003/REPORT.md)。
