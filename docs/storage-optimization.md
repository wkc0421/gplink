# Redis + TimescaleDB 存储与查询优化方案

## 1. 背景

当前后端采用 GPLink + TimescaleDB 作为设备时序数据主存储，已经具备以下基础能力：

- 使用 TimescaleDB 存储设备属性、事件、日志等时序数据
- 设备属性查询、历史查询、聚合查询主要仍走 TimescaleDB 原始表
- 设备消息写入链路已经具备缓冲写入能力
- `timescaledb.things-data` 已支持 hypertable 和 retention policy

当前痛点主要集中在以下几个方面：

1. `latest` 查询高频，后续还需要开放给外部服务使用
2. 高峰期 `latest` 查询直接打到 TimescaleDB，会带来不必要的热点读压力
3. 高频聚合查询主要集中在累计量、平均值、区间最后值、最大值、最小值
4. 原始历史查询仍有价值，但整体访问频率低于 `latest` 和聚合查询
5. 需要显式控制 JVM、Redis 和 TimescaleDB 侧的内存/资源占用
6. 冷数据需要生命周期策略，而不是无限在线保存

## 2. 目标

本方案目标如下：

- 使用 Redis 承担设备属性 `latest` 查询
- Redis 仅存属性最新值，不存事件，不存历史，不参与聚合
- TimescaleDB 继续作为全量时序数据真源
- 为高频聚合查询建立独立聚合层
- 将查询链路明确拆分为 `latest / aggregation / history`
- 控制应用内存、连接池、写入缓冲和 Redis 占用
- 为冷数据设计明确生命周期

## 3. 设计原则

1. Redis 是 `latest state store`，不是历史库，也不是真源
2. TimescaleDB 是原始时序数据真源
3. 聚合层服务高频统计，原始层服务低频明细与回源
4. 先做高价值、低风险能力，再逐步扩展
5. 优先控制资源占用，而不是追求过度复杂的全场景路由

## 4. 总体架构

### 4.1 分层结构

建议采用三层结构：

1. Redis Latest Layer
   - 仅保存设备属性最新值
   - 仅服务 `latest` 查询
   - 支持 miss 后回源 TimescaleDB 并回填 Redis

2. TimescaleDB Raw Layer
   - 存储全量原始时序数据
   - 服务低频历史查询
   - 服务 Redis miss 时的 `latest` 回源
   - 作为聚合层的计算真源

3. TimescaleDB Aggregate Layer
   - 存储高频统计结果
   - 优先支持累计量、平均值、时间段最后值、最大值、最小值查询
   - 视实现难度采用 continuous aggregate 或中间聚合表

### 4.2 查询服务拆分

不建议设计一个大而全的通用查询路由器，建议拆分为三个明确服务：

1. `LatestQueryService`
   - 单设备单属性最新值
   - 单设备全部属性最新值
   - 多设备指定属性最新值

2. `AggregationQueryService`
   - 两个时间点的差值
   - 时间桶首末差值
   - 时间桶平均值
   - 时间桶最后值
   - 时间桶最大值/最小值

3. `HistoryQueryService`
   - 原始历史数据查询
   - 低频时序明细回溯

## 5. Redis Latest 方案

## 5.1 范围边界

Redis 只承担以下职责：

- 设备属性 `latest` 查询
- 写入链路上的轻量辅助缓存

Redis 不承担以下职责：

- 历史查询
- 聚合计算
- 事件查询
- 日志查询
- 原始数据真源

## 5.2 存储对象

首期仅存设备属性的最新值，不存事件。

推荐只存这些信息：

- 属性值
- 属性时间戳

不建议存以下内容：

- 复杂对象/数组大字段
- 长文本字段
- 与 latest 查询无关的冗余元数据

## 5.3 Key 结构建议

推荐采用每设备一个 Hash：

```text
device:{deviceId}:latest
```

推荐 field 结构（value 与 ts 合并，减少 field 数量）：

```text
property:{propertyId} = "{value}|{timestamp}"
```

例如：

```text
device:dev-001:latest
  property:totalPower = "12345.6|1712345678900"
  property:voltage    = "220.1|1712345678910"
```

将 value 和 ts 合并为一个分隔符字符串的优点：

- field 数量减半，大规模设备场景内存节省明显
- Lua 脚本更简单（一次 HGET + 一次 HSET）
- 更新粒度小
- 不同属性互不覆盖
- 单设备读取全部属性简单
- 内存开销可控

## 5.4 写入规则

设备消息到达后：

1. 解析属性上报
2. 逐个属性写入 Redis latest
3. 必须比较时间戳，仅允许新时间戳覆盖旧值
4. Redis 写失败不影响 TimescaleDB 持久化

时间戳覆盖规则必须作为强约束：

- 若新消息时间戳大于 Redis 中已有时间戳，则覆盖
- 若新消息时间戳小于等于已有时间戳，则忽略

这是避免乱序消息覆盖最新值的关键。

### 5.4.1 原子性要求（硬性前提）

时间戳比较与值写入**必须通过 Lua 脚本保证原子性**。如果用两次 Redis 命令（先读 ts 再写），并发场景下会出现竞态条件，导致旧值覆盖新值。

参考 Lua 脚本（配合 5.3 节合并 field 结构）：

```lua
-- KEYS[1] = device:{deviceId}:latest
-- ARGV[1] = property:{propertyId}  (field name)
-- ARGV[2] = value
-- ARGV[3] = timestamp
-- ARGV[4] = TTL (seconds)
local cur = redis.call('HGET', KEYS[1], ARGV[1])
if cur then
    local sep = string.find(cur, '|')
    if sep then
        local cur_ts = tonumber(string.sub(cur, sep + 1))
        if cur_ts and tonumber(ARGV[3]) <= cur_ts then
            return 0
        end
    end
end
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2] .. '|' .. ARGV[3])
redis.call('EXPIRE', KEYS[1], ARGV[4])
return 1
```

此脚本为第一阶段上线的硬性前提，不可用非原子方式替代。

## 5.5 读路径规则

`latest` 查询统一采用：

1. 先查 Redis
2. Redis miss 后回源 TimescaleDB 原始层
3. 回源成功后回填 Redis

Redis miss 回源仅支持：

- 单设备单属性最新值
- 单设备全部属性最新值
- 多设备指定属性最新值

不支持将 Redis miss 扩展为历史查询或复杂聚合查询。

### 5.5.1 回填并发控制（防缓存击穿）

当某设备的 Redis key 过期后，大量并发请求同时 miss 会全部打到 TimescaleDB，造成瞬时热点压力。

要求：

- 对回源操作加 **singleflight / 互斥锁**，同一 `deviceId` 同时只允许一个请求回源
- 其他并发请求等待该回源结果，而不是各自独立查询 TimescaleDB
- 可选方案：在首个请求回源期间，用短 TTL（如 3 秒）的占位 key 阻止重复回源

## 5.6 Redis 故障降级策略

当 Redis 不可用时，系统必须具备降级能力：

- `latest` 查询降级为直接查询 TimescaleDB Raw Layer
- 降级期间触发告警，通知运维
- Redis 恢复后自动恢复正常路径，无需手动干预
- 降级期间的写入仅写 TimescaleDB，Redis 恢复后由后续写入自然刷新缓存

## 5.7 TTL 策略

建议保留 TTL，但不依赖 TTL 保证正确性。

推荐：

- TTL：24 小时，可配置
- 每次写入刷新 TTL
- 数据是否有效以属性时间戳为准

## 5.8 Redis 内存控制建议

1. 只缓存需要对外提供 `latest` 的属性
2. 大对象属性默认不进入 Redis
3. 不在 Redis 中保存历史片段
4. 不在 Redis 中保存事件
5. 定期统计 key 数、field 数、平均对象大小

容量评估建议使用：

```text
Redis 内存 ≈ 设备数 × 平均缓存属性数 × 单属性平均占用 × 冗余系数
```

## 6. TimescaleDB 原始层方案

## 6.1 职责

TimescaleDB 原始层负责：

- 全量原始数据持久化
- Redis latest miss 回源
- 低频原始历史查询
- 聚合层数据计算真源

## 6.2 原始层保留与压缩策略

建议保留当前 hypertable 设计，并做以下增强：

- `chunk interval` 保持 7 天
- 近 30 天数据作为热数据
- 超过 30 天的数据压缩
- 原始数据最长在线保留 3 年

### 6.2.1 压缩配置

`compress_segmentby` 的选择直接影响压缩后的查询性能，按 `device_id, property` 分段是最优选择：

```sql
-- 启用压缩
ALTER TABLE things_data SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id, property',
    timescaledb.compress_orderby = 'timestamp DESC'
);

-- 30 天后自动压缩
SELECT add_compression_policy('things_data', INTERVAL '30 days');

-- 3 年后自动删除
SELECT add_retention_policy('things_data', INTERVAL '3 years');
```

注意：压缩策略投入极小（仅两条 SQL），建议在第一阶段与 Redis latest 同步上线，不需要等到第二阶段。

## 6.3 原始层查询定位

原始层只服务以下场景：

- 历史原始数据查询
- 任意两个时间点的差值查询
- Redis latest miss 时回源

不建议再让高频统计直接反复扫原始表。

## 7. 聚合层方案

## 7.1 聚合层目标

聚合层用于承载高频查询，重点覆盖：

1. 两个时间点的差值
2. 每小时/日/月累计量
3. 每小时/日/月平均值
4. 每小时/日/月最后值
5. 每小时/日/月最大值和最小值

## 7.2 高频查询分类

### 7.2.1 任意两个时间点差值

例如：

```text
value(t2) - value(t1)
```

这类查询通常依赖任意时间点的前后最近值，不一定能完全依赖固定时间桶聚合。

建议：

- 首期仍以 Raw Layer 查询为主
- 后续视使用频率决定是否增加专用优化

### 7.2.2 时间桶首末差值

例如：

- 每小时用电量
- 每日用电量
- 每月用电量

建议公式：

```text
delta = last_value - first_value
```

这是本方案中最值得优先优化的高频场景。

实现方式：cagg 物化 `first_value` 和 `last_value`，delta 在查询时做减法，不需要额外存储。示例：

```sql
SELECT
    bucket_start,
    device_id,
    property,
    last_value - first_value AS delta
FROM hourly_device_agg
WHERE device_id = 'dev-001' AND property = 'totalPower'
  AND bucket_start BETWEEN '2026-04-01' AND '2026-04-06';
```

cagg 不支持在定义中引用同一行的两个聚合结果做运算（语法约束），但查询时的减法在已物化数据上执行，开销可忽略。

### 7.2.3 时间桶平均值

例如：

- 每小时平均电压
- 每日平均功率

建议直接进入聚合层。

### 7.2.4 时间桶最后值

例如：

- 每小时结束时总表读数
- 每日最后时刻电表值

建议直接进入聚合层。

### 7.2.5 时间桶极值

例如：

- 日内最大值/最小值
- 月内最大值/最小值

建议直接进入聚合层。

## 7.3 聚合层核心模型

聚合层通过 continuous aggregate（cagg）实现，不需要自建中间聚合表。cagg 自动物化以下字段：

| 字段 | 来源 |
|------|------|
| `bucket_start` | `time_bucket('1 hour', timestamp)` |
| `device_id` | `GROUP BY` |
| `property` | `GROUP BY` |
| `first_value` | `first(value, timestamp)` |
| `last_value` | `last(value, timestamp)` |
| `avg_value` | `avg(value)` |
| `min_value` | `min(value)` |
| `max_value` | `max(value)` |
| `sample_count` | `count(*)` |

小时级 cagg 可以覆盖：

- 小时累计量：查询时 `last_value - first_value`（delta 不存储，查询时计算）
- 小时平均值：`avg_value`
- 小时最后值：`last_value`
- 小时最大值/最小值：`max_value / min_value`

同时还可以作为日级、月级查询的基础层（通过 hierarchical cagg 叠加）。

具体建表语句见 7.5 节。

## 7.4 日/月查询建议

日、月查询不建议直接频繁扫原始表，建议复用小时聚合层。

建议方式：

- 日累计量：基于当天小时聚合结果计算
- 月累计量：基于当月小时或天级结果计算
- 日平均值：基于小时聚合层进一步聚合
- 日最后值：取最后一个小时桶的 `last_value`
- 月最后值：取最后一个日/小时桶的 `last_value`

## 7.5 关于 continuous aggregate 的定位

continuous aggregate 适用于：

- 固定时间粒度
- 高频重复访问
- 允许轻微刷新延迟

**建议优先使用 continuous aggregate 而非中间聚合表**。TimescaleDB 2.x 的 continuous aggregate 已经成熟，`first()` / `last()` 是原生支持的聚合函数，不存在语义复杂度问题。使用 continuous aggregate 比自建中间表维护成本低得多（无需自建刷新调度）。

在本方案中，continuous aggregate 承载：

- 小时级平均值（`avg`）
- 小时级最后值（`last`）
- 小时级最大值/最小值（`max` / `min`）
- 小时级首值（`first`）
- 小时级累计量：cagg 物化 `first` 和 `last`，delta 在查询时计算 `last_value - first_value`

推荐配置：

```sql
CREATE MATERIALIZED VIEW hourly_device_agg
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 hour', timestamp) AS bucket_start,
    device_id,
    property,
    first(value, timestamp) AS first_value,
    last(value, timestamp) AS last_value,
    avg(value) AS avg_value,
    min(value) AS min_value,
    max(value) AS max_value,
    count(*) AS sample_count
FROM things_data
GROUP BY bucket_start, device_id, property
WITH NO DATA;

-- 允许查询时自动合并未物化的实时数据
ALTER MATERIALIZED VIEW hourly_device_agg SET (
    timescaledb.materialized_only = false
);

-- 自动刷新策略
SELECT add_continuous_aggregate_policy('hourly_device_agg',
    start_offset => INTERVAL '3 hours',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour'
);
```

日/月级聚合可以在小时 cagg 之上再建 cagg（hierarchical continuous aggregate，TimescaleDB 2.9+ 原生支持），避免重复扫描原始表。

delta（`last_value - first_value`）等派生计算不需要存储，在查询 cagg 时做减法即可，开销可忽略。因此本方案不需要中间聚合表。

## 8. 查询路由方案

## 8.1 Latest 查询路由

```text
Redis latest -> miss -> TimescaleDB raw latest -> 回填 Redis
```

## 8.2 Aggregation 查询路由

建议规则如下：

1. 时间桶累计量、平均值、最后值、最大值、最小值
   - 优先走 Aggregate Layer

2. 任意两个时间点差值
   - 首期优先走 Raw Layer
   - 后续根据热点情况再决定是否优化

## 8.3 History 查询路由

```text
直接走 TimescaleDB Raw Layer
```

## 9. 生命周期策略

冷数据不应只做压缩，还应设计明确生命周期。

## 9.1 原始数据生命周期

建议：

- 0 ~ 30 天：热数据，原始明细，不压缩
- 30 天 ~ 3 年：冷数据，压缩保存
- 超过 3 年：删除原始明细或归档到离线存储

是否归档取决于合规与审计要求：

- 无强合规要求：可删除
- 有审计要求：建议归档

## 9.2 聚合数据生命周期

建议聚合数据比原始数据保留更久：

- 小时聚合：保留 1 ~ 3 年
- 日聚合：保留 3 年以上
- 月聚合：可长期保留

retention policy 可以直接挂在 continuous aggregate 上，由 TimescaleDB 自动管理：

```sql
SELECT add_retention_policy('hourly_device_agg', INTERVAL '3 years');
```

原则：

- 原始明细先删
- 聚合结果后删

## 10. 内存与资源优化策略

## 10.1 应用 JVM

建议重点控制以下资源：

- 减少应用内 latest 缓存依赖
- 下调 TimescaleDB 写入缓冲大小
- 下调并行写线程数
- 下调 R2DBC 连接池大小
- 显式配置批量写入大小

推荐首期参数方向：

- TimescaleDB write buffer：300 ~ 500
- write parallelism：2
- R2DBC pool：16 ~ 32
- batch size：500 ~ 1000（TimescaleDB 对批量 INSERT 优化良好，100-200 偏保守）

具体值应结合压测确定。

## 10.2 Redis

建议控制：

- 仅存属性 latest
- 只存 value 与 ts
- 默认过滤大字段
- TTL 自动淘汰不活跃设备

## 10.3 TimescaleDB

建议控制：

- 高频统计尽量走聚合层
- 冷数据压缩
- 避免高频 `latest` 查询直接打原始表

## 11. 监控方案

首期先做监控，不强制立即接告警。

建议至少增加以下指标：

### 11.1 Redis

- Redis latest 命中率
- Redis miss 回源次数
- Redis latest 写失败数
- Redis 乱序覆盖拦截数
- Redis key 数量
- Redis 内存占用

### 11.2 TimescaleDB

- 写入 buffer 当前积压
- 写入失败次数
- 写入重试次数
- 聚合查询 RT
- 原始历史查询 RT
- Continuous aggregate 刷新延迟（聚合层数据新鲜度的关键指标）
- Continuous aggregate 刷新失败次数

### 11.3 Query Service

- latest 查询 RT
- aggregation 查询 RT
- history 查询 RT

## 12. 分阶段实施建议

## 12.1 第一阶段（已实施）

按以下顺序落地，优先选择风险最低、收益最高的改动：

### 步骤 1：TimescaleDB 压缩策略（零代码风险）

新增 `CreateCompressionPolicy` 特性类，在属性表建表时自动附加压缩配置。  
通过 `timescaledb.things-data.compress=true` 开启，默认 30 天后压缩，3 年后删除原始数据。

**涉及文件：**
- `timescaledb-component/.../metadata/CreateCompressionPolicy.java`（新增）
- `timescaledb-component/.../metadata/TimescaleDBCreateTableSqlBuilder.java`（生成压缩 SQL）
- `timescaledb-component/.../thing/TimescaleDBThingsDataProperties.java`（compress 配置项）
- `timescaledb-component/.../thing/TimescaleDBRowModeDDLOperations.java`（属性表附加特性）
- `application.yml`（启用 `timescaledb.things-data.compress=true`）

### 步骤 2：收紧资源参数（配置改动，无业务风险）

- R2DBC 连接池：128 → 32
- TimescaleDB things-data retention-policy：3y
- 设备写入缓冲默认参数：size=500，parallelism=2

**涉及文件：**
- `application.yml`

### 步骤 3a：新增 RedisDeviceLatestService

封装 Redis HGET/HSET，包含：
- Lua 原子写入脚本（防乱序覆盖）
- TTL 管理（默认 24 小时）
- Micrometer 指标（hit/miss/write-fail/stale-blocked）

**涉及文件：**
- `device-manager/.../service/data/RedisDeviceLatestService.java`（新增）

### 步骤 3b：修改写路径，属性上报后异步写 Redis

在 `TimeSeriesMessageWriterConnector.writeToThingsDataWriter()` 中，TimescaleDB 写完后 fire-and-forget 写入 Redis。Redis 失败不影响主流程。

**涉及文件：**
- `device-manager/.../message/writer/TimeSeriesMessageWriterConnector.java`

### 步骤 3c：修改 latest 查询路径（Redis first → 回源 → 回填）

新增 `RedisDeviceLatestDataService` 实现 `DeviceLatestDataService`：
- `queryDeviceData`：先查 Redis HGETALL，miss 时回源 TimescaleDB `queryEachProperty` 并异步回填 Redis
- 其他方法（需关系型过滤）：返回空，行为与原 `NonDeviceLatestDataService` 一致

在 `DeviceManagerConfiguration` 中装配，条件：`ReactiveStringRedisTemplate` 存在 + `redis-latest.enabled=true`（默认开启）。

**涉及文件：**
- `device-manager/.../service/data/RedisDeviceLatestDataService.java`（新增）
- `device-manager/.../configuration/DeviceManagerConfiguration.java`

### 步骤 4：基础监控指标

已内置于 `RedisDeviceLatestService`，通过 Micrometer + Spring Boot Actuator 暴露：

| 指标名 | 说明 |
|--------|------|
| `redis.latest.hit` | Redis latest 命中次数 |
| `redis.latest.miss` | Redis latest miss 次数 |
| `redis.latest.write.fail` | Redis latest 写入失败次数 |
| `redis.latest.stale.blocked` | 乱序覆盖拦截次数 |

## 12.2 第二阶段

- 建立小时级基础聚合层（优先使用 continuous aggregate）
- 支持高频累计量、平均值、最后值、最大值、最小值查询
- 补充 continuous aggregate 刷新延迟监控

## 12.3 第三阶段

- 优化日/月查询链路
- 评估是否需要对”两时间点差值”做进一步专用优化
- 结合外部服务场景开放 latest 接口
- 评估冷数据归档策略

## 13. 风险与边界

1. Redis 与 TimescaleDB 在短时间内允许最终一致，不承诺强一致
2. Redis 不是原始数据真源，不能承担历史查询
3. 任意两时间点差值首期不强行纳入聚合层
4. `first/last` 聚合若在技术实现上复杂度过高，可采用中间聚合表替代纯 continuous aggregate
5. 超过 3 年的原始数据需要在方案评审阶段明确“删除或归档”策略

## 14. 结论

本方案建议采用：

- Redis 只做设备属性 `latest`
- TimescaleDB 原始层继续承担全量真源
- TimescaleDB 聚合层承载高频统计
- 查询按 `latest / aggregation / history` 三类拆分
- 生命周期明确区分热、冷、归档数据
- 在设计阶段就将 JVM、Redis、连接池、写入缓冲的内存控制纳入正式约束

该方案较原始的“四层全能路由”更贴合当前代码和业务场景，能够在控制复杂度和内存成本的前提下，优先优化最有价值的查询路径。
