# 存储优化实施总结

> 对应设计文档：`storage-optimization.md`
> 最后更新：2026-04-07

---

## 已实施内容

### 第一阶段（提交 ddc396b + 0ecb11f）

**目标**：减轻 TimescaleDB latest 查询热点压力，控制冷数据存储。

#### 1. TimescaleDB 压缩策略

新增 `CreateCompressionPolicy` Feature 类，属性表建表时自动附加压缩配置。

- 配置前缀：`timescaledb.things-data.compress`
- 默认 30 天后压缩，3 年后删除原始数据
- 压缩分段：`thing_id, property`，排序：`timestamp DESC`

涉及文件：
- `timescaledb-component/.../metadata/CreateCompressionPolicy.java`（新增）
- `timescaledb-component/.../metadata/TimescaleDBCreateTableSqlBuilder.java`（生成压缩 SQL）
- `timescaledb-component/.../thing/TimescaleDBThingsDataProperties.java`（compress 配置项）
- `timescaledb-component/.../thing/TimescaleDBRowModeDDLOperations.java`（属性表附加特性）

#### 2. Redis Latest 缓存层

设备属性上报后异步写入 Redis，latest 查询优先读 Redis，miss 后回源 TimescaleDB 并回填。

- Redis Key 结构：`device:{deviceId}:latest`，field：`property:{propertyId}`
- 存储格式：JSON `{"v": <typed-value>, "ts": <timestamp ms>}`（保留类型信息）
- Lua 原子脚本防乱序覆盖（比较 `obj.ts` 字段）
- TTL：24 小时（可配置），每次写入刷新
- 配置前缀：`jetlinks.device.storage.redis-latest`

Micrometer 指标：

| 指标 | 含义 |
|------|------|
| `redis.latest.hit` | Redis 命中次数 |
| `redis.latest.miss` | Redis miss 次数 |
| `redis.latest.write.fail` | 写入失败次数 |
| `redis.latest.stale.blocked` | 乱序覆盖拦截次数 |

涉及文件：
- `device-manager/.../service/data/RedisDeviceLatestService.java`（新增，Redis 操作核心）
- `device-manager/.../service/data/RedisDeviceLatestDataService.java`（新增，实现 DeviceLatestDataService）
- `device-manager/.../message/writer/TimeSeriesMessageWriterConnector.java`（写后触发 Redis 写入）
- `device-manager/.../configuration/DeviceManagerConfiguration.java`（注册 bean）
- `device-manager/.../service/data/DeviceDataStorageProperties.java`（新增 RedisLatest 配置类）

#### 3. 资源参数收紧

- R2DBC 连接池：128 → 32
- TimescaleDB things-data 保留策略：3 年
- 设备写入缓冲：size=500，parallelism=2

---

### 第二阶段（提交 b9bb9ad）

**目标**：将高频小时级聚合查询路由到预物化视图，避免反复全扫原始表。

#### 小时级 Continuous Aggregate

属性表建表时自动创建 `{table}_hourly_agg` 物化视图。

物化字段：

| 列 | 来源 |
|----|------|
| `bucket_start` | `time_bucket('1 hour', timestamp)` |
| `thing_id` | GROUP BY |
| `property` | GROUP BY |
| `first_value` | `first("numberValue", "timestamp")` |
| `last_value` | `last("numberValue", "timestamp")` |
| `avg_value` | `avg("numberValue")` |
| `sum_value` | `sum("numberValue")` |
| `min_value` | `min("numberValue")` |
| `max_value` | `max("numberValue")` |
| `sample_count` | `count(*)` |

配置前缀：`timescaledb.things-data.cagg`

```yaml
timescaledb:
  things-data:
    cagg:
      enabled: true
      refresh-interval: 1h
      start-offset: 3h
      end-offset: 1h
      retention: 3y
```

#### 查询路由规则

在 `doAggregation()` 中，满足以下**全部条件**时路由到 `_hourly_agg`：

1. `cagg.enabled = true`
2. 请求 interval ≥ 1 小时（`toMillis() >= 3_600_000`）
3. 聚合类型仅含 AVG / MAX / MIN / FIRST / LAST / TOP（不含 SUM / COUNT / DISTINCT_COUNT）
4. filter 仅含 `thing_id` / `thingId` 的 `eq` 或 `in` 条件，无嵌套子条件

**AVG 重聚合精度保证**：使用 `sum(sum_value) / nullif(sum(sample_count), 0)` 而非 `avg(avg_value)`，避免多桶加权误差。

#### cagg 刷新监控

`CaggMonitorService` 每 5 分钟查询 `timescaledb_information.jobs / job_stats`，暴露指标：

| 指标 | 含义 |
|------|------|
| `timescaledb.cagg.refresh.lag_seconds{view}` | 距上次成功刷新的秒数（Gauge） |
| `timescaledb.cagg.refresh.failed{view}` | 刷新失败次数（Counter） |

涉及文件：
- `timescaledb-component/.../metadata/CreateContinuousAggregate.java`（新增，DDL Feature）
- `timescaledb-component/.../thing/CaggMonitorService.java`（新增，监控服务）
- `timescaledb-component/.../metadata/TimescaleDBCreateTableSqlBuilder.java`（生成 cagg DDL）
- `timescaledb-component/.../thing/TimescaleDBRowModeDDLOperations.java`（属性表附加 cagg feature）
- `timescaledb-component/.../thing/TimescaleDBThingsDataProperties.java`（Cagg 配置类）
- `timescaledb-component/.../thing/TimescaleDBRowModeQueryOperations.java`（路由逻辑）
- `timescaledb-component/.../thing/TimescaleDBRowModeStrategy.java`（传入 properties）
- `timescaledb-component/.../configuration/TimescaleDBThingsDataConfiguration.java`（注册 CaggMonitorService）

---

### 第三阶段 T1（提交 1feda11）

**目标**：建立日/月级层级 cagg，减少日月查询需扫描的行数。

#### 层级 cagg 结构

```
原始表 (things_data)
  └─ _hourly_agg   （1 小时桶，直接在原始表上聚合）
       └─ _daily_agg   （1 天桶，在 _hourly_agg 上聚合）
            └─ _monthly_agg  （1 月桶，在 _daily_agg 上聚合）
```

日/月级 cagg 物化字段与小时级相同，但无 `avg_value`（查询时由 `sum_value / sample_count` 派生）。

配置新增：

```yaml
timescaledb:
  things-data:
    cagg:
      daily-enabled: true    # 建立 _daily_agg
      monthly-enabled: true  # 建立 _monthly_agg（需要 daily-enabled=true）
```

刷新策略默认值：

| 层级 | start_offset | end_offset | schedule_interval |
|------|-------------|------------|-------------------|
| 小时 | 3h（可配）  | 1h（可配） | 1h（可配） |
| 日   | 3 days      | 1 day      | 1 day |
| 月   | 3 months    | 1 month    | 1 day |

#### 分层路由规则

`selectCaggView()` 按以下优先级选择视图：

| 条件 | 路由目标 |
|------|---------|
| `monthlyEnabled && dailyEnabled` 且 interval 单位为 MONTHS/YEARS | `_monthly_agg` |
| `dailyEnabled` 且 `interval % DAY_MS == 0`（整天对齐） | `_daily_agg` |
| 其他（interval ≥ 1h） | `_hourly_agg` |

**关键约束（Codex P1 修复）**：
- 月级路由要求 `dailyEnabled && monthlyEnabled` 同时为 true，与 DDL 创建条件一致，防止查询缺失视图
- 日级路由要求间隔为整天整除（25h、36h 等非对齐间隔回退到小时视图）

---

## 关键架构约定

### 三层查询链路

```
latest 查询:   Redis → miss → TimescaleDB raw latest → 回填 Redis
聚合查询:      _monthly/_daily/_hourly_agg → 不满足条件 → 原始表
历史查询:      直接走 TimescaleDB 原始表
```

### cagg 路由不支持的场景（回退原始表）

- 聚合类型包含 SUM / COUNT / DISTINCT_COUNT
- interval < 1 小时
- filter 含非 `thing_id` 列的条件（如 `numberValue > 100`）
- filter 含嵌套子条件
- 日/月 cagg 未启用，或间隔非对齐

### 数据生命周期

| 数据层 | 保留时长 | 压缩策略 |
|--------|---------|---------|
| 原始数据（热） | 0-30 天 | 不压缩 |
| 原始数据（冷） | 30 天-3 年 | 列压缩 |
| 小时/日/月 cagg | 3 年 | 由 TimescaleDB 管理 |
| 原始数据（超期） | 3 年后删除 | — |

---

## 待实施（第三阶段剩余）

| 任务 | 说明 | 优先级 |
|------|------|--------|
| T2 Delta 查询 | cagg 已物化 first_value / last_value，delta = last - first，查询时计算，无需额外代码 | 已具备能力 |
| T3 外部 latest API | 新增 REST 接口供外部服务查询设备属性最新值（走 Redis 层） | P1 |
| T4 冷数据归档 | 评估 3 年后原始数据删除 vs 归档策略 | P2（评估） |
