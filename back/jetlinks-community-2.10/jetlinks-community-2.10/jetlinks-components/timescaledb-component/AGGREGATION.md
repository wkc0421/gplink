# 行式属性聚合

## 约定

- 原始表的 timestamp 仍为 TIMESTAMP WITHOUT TIME ZONE，存储北京时间墙上时间。写入、查询绑定、返回 epoch 毫秒、Java/SQL 分桶及旧接口格式化显式使用 Asia/Shanghai。
- API 的 from/to 包含两端；内部拆成互不重叠的半开区间。limit 表示最新的时间桶数量，而非桶数乘属性数量。
- 固定间隔按 2000-01-03 起算，月/季/年按 2000-01-01 起算。小时/日/月连续聚合声明共同的 2000-01-01 原点，以满足 TimescaleDB 层级视图约束；1h/1d 的桶边界与上述查询原点相同。
- 完整底层桶读取连续聚合，首尾部分读取原始数据，以 UNION ALL 合并统计状态。AVG 使用 sum_value / numeric_count，不对平均数再次平均。
- FIRST/TOP 取最早样本，LAST 取最晚样本；时间相同时按设备 ID 的 C 排序确定先后。非数值首末值与 DISTINCT_COUNT 使用原始查询。
- 查询中的别名仅在结果映射时使用，不进入 SQL。未支持的筛选条件回退原始查询；设备约束和 AND/OR 分组保持不变。
- 查询时间超出聚合保留期时回退原始表，避免已清理的聚合块在物化水位之前形成空洞。

## 自然周与自然季度

`POST /api/v1/device-data/devices/agg/_query` 的 `aggregationRequest.query.interval` 支持：

| 参数 | 北京时间分桶规则 | CAGG 优先级 |
| --- | --- | --- |
| `1w` | 周一 00:00 至下周一 00:00（左闭右开，覆盖周一至周日） | 日层 → 小时层 |
| `1q` | 1—3、4—6、7—9、10—12 月；季度首日 00:00 至下一季度首日 00:00 | 月层 → 日层 → 小时层 |

复用现有 CAGG，不新增周／季度聚合表。选择最粗的兼容且就绪层；完整底层桶读取 CAGG，首尾不完整底层桶读取原始数据后合并统计状态。现有保留期、结构、筛选条件及累计量新鲜度检查继续生效；未满足检查时按原有规则回退原始查询。新鲜度检查失败不会绕过检查强制读取 CAGG。

`from/to` 包含两端，但不会自动扩展到完整周或季度。返回 `time` 为所属自然桶的起点，按 `format` 格式化，因此首桶的 `time` 可以早于 `from`。例如周三到周五的周查询，仅统计这三天请求范围内的样本，桶时间仍为本周一。`to` 恰好位于下周／季度起点时，该时刻归入新的桶；`limit` 仍保留最新的若干桶，空桶保持既有默认值。

自然周请求示例（设备与属性 ID 请替换为实际值）：

```json
{
  "devices": ["device-1"],
  "properties": ["E"],
  "aggregationRequest": {
    "query": {
      "from": "2026-10-14 12:00:00",
      "to": "2026-10-25 23:59:59",
      "interval": "1w",
      "format": "yyyy-MM-dd HH:mm:ss",
      "limit": 30
    },
    "columns": [{"property": "E", "alias": "value", "agg": "SUM"}]
  }
}
```

自然季度请求示例：

```json
{
  "devices": ["device-1"],
  "properties": ["E"],
  "aggregationRequest": {
    "query": {
      "from": "2026-02-15 12:00:00",
      "to": "2026-10-20 18:00:00",
      "interval": "1q",
      "format": "yyyy-MM-dd HH:mm:ss",
      "limit": 30
    },
    "columns": [{"property": "E", "alias": "value", "agg": "SUM"}]
  }
}
```

响应沿用设备 ID 到 `[{"time": "...", "value": ...}]` 的映射列表；季度示例按倒序返回 `2026-10-01`、`2026-07-01`、`2026-04-01`、`2026-01-01` 的桶。日期字符串按北京时间解释，也可继续传 epoch 毫秒。这里的 SUM 是样本值求和，不是区间差值。

## 配置

位于 timescaledb.things-data：

```yaml
retention-policy: 5y
aggregation:
  max-buckets: 10000
  max-columns: 100
  max-cells: 100000
  timeout: 30s
cagg:
  enabled: true
  refresh-interval: 1h
  end-offset: 1h
  retention: 5y
  daily-enabled: true
  monthly-enabled: true
```

三个层级的刷新 start_offset 均由原始数据 retention-policy 决定；无原始保留期时使用 NULL。旧 start-offset / daily-start-offset 字段保留绑定兼容，但不再缩短回看范围。小时、日、月的 end_offset 分别为配置值、1 天、1 月；调度间隔分别为配置值、1 天、1 天。

补报/修订为最终一致性：水位前的修改需等待各层刷新，层级任务顺序可能导致跨多轮传播。实时聚合只补水位之后的数据，不意味着历史修改已经刷新。查询超时不会被伪装成空结果。

聚合查询从实际 TimescaleDB 连接池借用独立的只读连接，通过 SET LOCAL statement_timeout 限制数据库执行时间；超时或客户端取消时显式发送 PostgreSQL cancelRequest，完成回滚后归还连接。设置只在当前事务有效，不影响后续复用该连接的请求。

原始表继续使用既有压缩策略，聚合表不自动启用压缩。组合索引为设备、属性、桶时间。

## 生命周期与运行条件

新建和已有原始表均通过幂等协调补齐聚合层、索引及策略。结构标记、必要字段、实时模式及刷新策略均校验后才路由到聚合；不兼容的旧对象只告警并回退，不删除重建。应用不提供也不自动执行破坏性迁移；下文只给出由运维人员盘点、重建、回填和回滚的显式步骤。

数据库需具备 TimescaleDB 扩展和相关 DDL/任务权限。查询与写入不依赖连接默认时区；但原生保留/刷新任务对无时区 TIMESTAMP 的“当前时间”仍受后台会话时区影响。部署时应将这些后台任务运行环境明确配置为 Asia/Shanghai；应用不会擅自修改数据库或角色的全局时区。策略的 timezone 参数用于调度，并不能代替后台会话设置。

监控使用 TimescaleDB 实际数据源，按 schema/view/job 区分：

- timescaledb.cagg.refresh.lag_seconds：上次成功运行距今时间。
- timescaledb.cagg.watermark.lag_seconds：物化水位距今时间，不保证历史失效区间已全部处理。
- timescaledb.cagg.refresh.failed：数据库累计任务失败次数，不随重复采样递增。

未知/从未运行的延迟为 -1；删除任务后清理对应指标。

## 旧连续聚合迁移与回滚

应用启动不会自动删除或重建不兼容的连续聚合。升级前先对每个属性原始表执行只读盘点，保存视图定义、版本标记和任务配置：

```sql
SELECT view_schema, view_name, materialized_only, view_definition
FROM timescaledb_information.continuous_aggregates
WHERE view_schema = :schema
  AND view_name IN (:hourly_view, :daily_view, :monthly_view)
ORDER BY view_name;

SELECT obj_description(to_regclass(format('%I.%I', view_schema, view_name)), 'pg_class') AS version,
       view_schema, view_name
FROM timescaledb_information.continuous_aggregates
WHERE view_schema = :schema
  AND view_name IN (:hourly_view, :daily_view, :monthly_view);

SELECT job_id, proc_name, schedule_interval, scheduled, config
FROM timescaledb_information.jobs
WHERE hypertable_schema = :schema
  AND hypertable_name IN (:hourly_view, :daily_view, :monthly_view)
ORDER BY hypertable_name, proc_name;
```

迁移必须由运维人员在维护窗口显式执行，不能由应用启动逻辑触发：

1. 先部署支持“不兼容即回退原表”的代码，并确认查询仍可从原始表返回正确结果。
2. 保存上述盘点结果以及 `pg_get_viewdef` 输出。按月、日、小时的逆依赖顺序删除旧视图；只处理已经确认属于目标属性表的三个视图。
3. 通过现有表初始化/修复流程重新创建小时、日、月视图、索引和策略，并读回版本标记与 12 个必要列。
4. 使用明确的业务保留期起止时间，依次刷新小时、日、月视图；不得用未审核的无限时间范围回填：

```sql
CALL refresh_continuous_aggregate(:hourly_regclass, :from_timestamp, :to_timestamp);
CALL refresh_continuous_aggregate(:daily_regclass,  :from_timestamp, :to_timestamp);
CALL refresh_continuous_aggregate(:monthly_regclass,:from_timestamp, :to_timestamp);
```

5. 对原表与连续聚合执行同一组 FIRST/LAST/AVG/COUNT 查询，并使用 `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` 确认时间范围裁剪、组合索引和无临时文件后，才结束维护窗口。

若创建、回填或对账失败，立即停止后续层级刷新。最安全的回滚是保持视图缺失或不兼容，让应用继续走原始表；只有在已保存且验证过旧 DDL 和策略时才恢复旧视图。禁止为“快速恢复”删除原始 hypertable 或修改其 timestamp 类型。

## 验证

在后端 Maven 根目录执行：

```powershell
mvn -o -pl jetlinks-components/timescaledb-component -am '-Dtest=AggregationWindowTest,AggregationQueryTest,CaggMonitorServiceTest,ContinuousAggregateIntegrationTest,TimescaleDBRetentionPolicyManagerTest,PointQueryOptimizationTest,PropertyBoundaryQueryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -o -pl jetlinks-manager/saas-manager -am '-Dtest=LegacyDeviceLatestValueServiceTest,LegacyDeviceDataLatestTest,LegacyAggregationTimezoneTest,LegacyDeviceIntervalQueryTest,LegacyDeviceHistoryNoPagingTest,LegacyDeviceDataCacheTest,PropertyBoundaryQueryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

集成测试默认使用一次性 timescale/timescaledb:2.29.1-pg16 容器；Docker 不可用则明确跳过。也可显式设置 -Dcagg.test.url 指向专门创建的一次性本机测试数据库，测试会创建表和视图，绝不能指向业务库。额外测试版本不替代 2.29.1 验证。

### 性能验收记录

本地单元测试只能证明 SQL 数量上界和结果合同，不能替代目标数据规模下的性能测量。每次在专用
TimescaleDB 环境验收时，使用同一数据快照、相同请求、相同 JVM 参数和连接池配置，分别记录改造前后：

| 场景 | SQL 数量 | HTTP 响应字节 | p50/p95/p99 | 连接池等待 | JVM 峰值堆 | GC 次数/暂停 | EXPLAIN 结论 |
|---|---:|---:|---|---|---:|---|---|
| latest 全命中 | 改造后应为 0 | 待实测 | 待实测 | 待实测 | 待实测 | 待实测 | 无 SQL |
| latest 部分缺失 | 改造后每个缺项设备最多 1 | 待实测 | 待实测 | 待实测 | 待实测 | 待实测 | 复合索引，无无界排序/临时文件 |
| interval summary | 改造后每设备 1 | 待实测 | 待实测 | 待实测 | 待实测 | 待实测 | CTE/LATERAL 索引点查 |
| interval history 1000 桶 | 改造后每设备 1 | 待实测 | 待实测 | 待实测 | 待实测 | 待实测 | `unnest` + LATERAL，无逐桶 SQL |
| 月/年历史 FIRST | 依完整/边缘桶拆分记录 | 待实测 | 待实测 | 待实测 | 待实测 | 待实测 | 中间桶 CAGG，首尾原表 |

对原始 chunk、压缩 chunk 和 CAGG 缺失回退分别保存
`EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`。验收以 SQL 复杂度下降、100000 cells 与缓存字节硬上界、
重复加载后堆占用回落以及无性能回退为准；未提供生产 SLA 时不得自行填写毫秒门槛。若本轮未连接
专用数据库或未运行 HTTP/JVM 压测，表中必须保留“待实测”，不能把单元测试耗时写入 p50/p95/p99。

### 本轮验证记录（2026-09-20）

- TimescaleDB 组件及设备、旧 SaaS 调用模块编译通过。
- 29 项单元测试、9 项数据库集成测试通过，无失败或跳过；真实数据库版本为 PostgreSQL 16.13 / TimescaleDB 2.26.1，使用独立临时实例。
- 数据库测试覆盖全新建表、已有原始表补建、幂等初始化与策略修复、异常结构回退、各粒度/部分桶结果对比、5 小时/5 天/4 个月补报、压缩块修订、真实监控 SQL，以及取消/超时后的锁等待解除、连接池归还和设置隔离。
- Docker 不可用，指定的 TimescaleDB 2.29.1 尚未验收；未部署运行服务，未做 HTTP 端到端回归及刷新执行过程中强制终止进程的恢复测试。这些未验证项不能由上述通过结果替代。

### 本轮验证记录（2026-09-22）

- latest/history/interval/cache 定向套件共发现并通过 43 项测试（SaaS 模块 41 项、依赖模块
  `PropertyBoundaryQueryTest` 2 项），0 失败、0 错误、0 跳过；覆盖 Redis
  全命中/缺项/故障、每缺项设备最多一条 SQL、设备级并发上限 10 与保序、非法筛选拒绝、精确边界、
  carry-forward、缓存隔离、30 秒虚拟超时、inflight 清理与重试。
- TimescaleDB 定向套件共发现 45 项：44 通过、0 失败、0 错误、1 跳过。跳过项为
  `ContinuousAggregateIntegrationTest`，原因是本机 Docker 不可用；其余单测覆盖 latest
  `DISTINCT ON` 分页门槛、单属性 `ORDER BY timestamp DESC LIMIT 1`、boundary 单 SQL、30 秒
  普通/分页/聚合查询的 statement/reactive timeout 与取消、运行时扩展 schema 下的
  `time_bucket`/`first`/`last` 引用与 60 秒缓存，以及 CAGG 缺失回退。
- `jetlinks-standalone -am package` 共 40 个模块构建成功；该命令使用 `-DskipTests`，仅证明完整编译、
  测试源码编译和打包成功，不能记为额外测试通过。
- 本轮没有可用的一次性 TimescaleDB 实例，因此未执行真实
  `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`、原始/压缩 chunk 与 CAGG 对账，也未采集 HTTP 响应字节、
  p50/p95/p99、连接池等待、JVM 峰值堆和 GC。上表继续保留“待实测”；未部署或修改远程服务、
  数据库视图及数据。

### 自然周／季度验证记录（2026-10-26）

- 后端相关 22 个模块编译成功，定向测试 69 项通过、0 失败、0 错误；`ContinuousAggregateIntegrationTest` 因 Docker 不可用整套跳过（Surefire 记为 1 个跳过项）。
- 覆盖北京时间周一／周日、跨年周、四季切换、闰日、季度跨年、部分周期、倒序、空桶和 limit；接口反序列化及设备／产品查询转发保持 `1w`、`1q` 和原始起止时间。
- SQL 路由单测确认周优先日层、季度优先月层，上层缺失时选择兼容下层，待刷新或缺少新鲜度证明时回退原表。新增集成用例会对比原表与 CAGG 的 SUM、COUNT、AVG、FIRST、LAST 等结果，并检查实际查询 SQL 使用的聚合层；本轮未执行该真实数据库对账，不作为已通过证据。
- 前端定向测试 16 项通过；Vite 构建成功，并核对产物包含周／季度选项。构建使用相对输出目录 `node_modules/.cache/gplink-calendar-dist-20261026`，避免现有 Monaco 插件拼接绝对输出路径失败的问题；未改动构建插件。
- 本轮没有部署、数据库迁移或远程数据变更。

复现后端测试（后端 Maven 根目录）：

```powershell
mvn -pl jetlinks-manager/saas-manager -am test '-Dtest=AggregationWindowTest,CalendarAggregationRoutingTest,AggregationQueryTest,CaggFreshnessGateTest,ContinuousAggregateIntegrationTest,ThingsBridgingAggregationLimitTest,LegacyAggregationTimezoneTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DfailIfNoTests=false' '-Dfile.encoding=UTF-8'
```

复现前端验证（front 目录；构建输出请选择独立目录）：

```powershell
pnpm.cmd exec playwright test tests/e2e/device-data-query-utils.spec.ts --workers=1
node --max-old-space-size=8192 node_modules/vite/bin/vite.js build --outDir node_modules/.cache/gplink-calendar-dist-20261026
```

### 后续测试环境部署验证（2026-10-26）

前后端已更新到 `192.168.5.19`。自然周聚合返回 6 个桶，自然季度聚合返回 2 个桶，两者 SUM=26，与独立原始表 SQL 对账一致；恢复正常启动参数后再次通过相同接口验证。实际数据库为 PostgreSQL 16.13 / TimescaleDB 2.26.1。快照优先证据针对 `interval/week/history`、`interval/quarter/history` 的边界解析路径；聚合接口继续使用既有 CAGG 优先与新鲜度回退规则。详见 [部署验收记录](../../../../../deploy/calendar-queries-20261026-144003/REPORT.md)。
