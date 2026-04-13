# 存储优化自动化测试方案

## 1. 目标

基于以下两个前提，优先设计“容易实现、容易验证、失败定位清晰”的自动化测试：

- 设计与实现文档：
  - [storage-optimization.md](/D:/project/gplink_ai/gplink/docs/storage-optimization.md)
  - [storage-optimization-impl.md](/D:/project/gplink_ai/gplink/docs/storage-optimization-impl.md)
- 已有模拟器能力：
  - [mqtt-simulator](/D:/project/gplink_ai/gplink/mqtt-simulator) 已支持任务配置、MQTT 高并发发送、HTTP API、Playwright E2E、本地 broker、递增 `E` 点位

测试设计遵循三个原则：

1. 先覆盖最有价值链路：`latest / aggregation / fallback`
2. 先做黑盒验证，少依赖内部实现细节
3. 优先复用现有 `mqtt-simulator`，只在确实必要时补最小测试能力

---

## 2. 当前模拟器能力评估

结合模拟器代码，当前可直接复用的能力如下：

- 可通过 HTTP API 创建/启动/停止任务
- 可稳定发送单设备单消息格式的属性上报
- 可通过 `deviceCount`、`messagesPerMinute` 构造并发写入
- `E` 属性对同一设备严格递增，适合验证 latest 与聚合结果
- 已有本地 MQTT broker 脚本，可用于 CI/本地自测

当前明显限制：

- 消息 `time` 字段固定为 `time.Now()`，不能直接构造“旧时间戳晚到”的乱序场景
- 现有测试更多验证“模拟器自身能否发送”，没有闭环验证 GPLink 的 Redis/TimescaleDB 查询结果
- 现有模拟器 UI 用 Playwright 驱动，适合冒烟，不适合做大量后端结果校验

结论：

- 第一批自动化测试应以“HTTP 配置任务 + MQTT 发消息 + 调用 GPLink 查询接口/数据库校验”为主
- “乱序覆盖拦截”应列为增强项，需要给模拟器补一个最小的“可控时间戳”能力，或单独补一个轻量发布脚本

---

## 3. 推荐测试分层

建议拆成三层，按投入从低到高推进。

### 3.1 L1：零改动高价值回归测试

目标：不改模拟器核心能力，直接验证当前已上线的主要收益点。

覆盖：

- Redis latest 写入与查询链路
- Redis miss 回源与回填
- 小时级 cagg 聚合查询路由是否可用
- 日/月级 cagg 查询是否能返回正确结果

### 3.2 L2：最小增强测试

目标：只补极小测试能力，验证最关键但现有模拟器不方便覆盖的场景。

覆盖：

- 乱序消息不覆盖最新值
- stale-blocked 指标增长
- Redis TTL 过期后的回源单飞/防击穿

### 3.3 L3：非阻塞监控型测试

目标：不强依赖精确业务值，主要验证运行状态与指标暴露。

覆盖：

- Redis 命中/未命中指标
- cagg refresh lag 指标存在且非异常
- Redis 不可用时 latest 查询降级

---

## 4. 优先落地的 6 个自动化测试

这 6 个用例是我最推荐先做的，原因是实现成本低、结果容易判断、和文档目标直接对应。

### 用例 1：latest 基本命中链路

目的：

- 验证属性消息写入 TimescaleDB 后，latest 查询能读到最新值
- 第二次查询应稳定命中 Redis

输入：

- 用模拟器创建 1 个任务
- `deviceCount=1`
- `messagesPerMinute=60`
- metadata 至少包含 `Ua`、`E`

步骤：

1. 启动任务并发送 5~10 条消息
2. 调用 GPLink latest 查询接口，读取设备 `000001` 的 `E`
3. 间隔 1~2 秒再次查询同一属性
4. 校验值存在且第二次值大于等于第一次
5. 如能读取 metrics，再校验 `redis.latest.hit` 增长

断言：

- latest 返回非空
- `E` 值单调不减
- 无查询异常

推荐级别：P0

---

### 用例 2：Redis miss -> Raw 回源 -> Redis 回填

目的：

- 验证 latest 的 miss 回源链路符合设计文档

前置：

- 已通过模拟器写入一批数据

步骤：

1. 先查一次 latest，确认设备有值
2. 删除 Redis 中该设备 latest key
3. 再次调用 latest 查询
4. 再读 Redis，确认 key 被回填
5. 如能读取 metrics，再校验 `redis.latest.miss` 增长

断言：

- 第二次 latest 查询仍成功返回
- 返回值与 TimescaleDB 中该设备该属性最新值一致
- Redis key 被重新写回

推荐级别：P0

说明：

- 这是最容易验证、也最能证明 Redis 只是缓存层而不是真源的用例

---

### 用例 3：小时聚合查询正确性

目的：

- 验证 `_hourly_agg` 路由生效
- 验证 avg/last/max/min/delta 结果正确

输入建议：

- 用模拟器发送单设备数据，持续 2~3 分钟即可
- 重点使用 `E` 作为累计量字段

步骤：

1. 通过模拟器产生单设备连续上报
2. 从原始表查询测试时间窗口内该设备的明细数据
3. 调用聚合查询接口，查询 1 小时粒度下的：
   - `LAST`
   - `AVG`
   - `MAX`
   - `MIN`
4. 对 `E` 额外校验 `delta = last - first`

断言：

- 聚合接口返回非空
- 返回值与基于原始表离线计算的结果一致

推荐级别：P0

说明：

- 该用例不强依赖“必须证明走了 cagg”，只要结果对且输入命中文档定义的可路由条件，就已经很有价值
- 如果系统支持 SQL 日志或 explain，再补一个“实际命中 `_hourly_agg` 视图”的增强断言

---

### 用例 4：日/月聚合路由正确性

目的：

- 验证第三阶段 T1 的日/月层级 cagg 能工作

难点：

- 实时生成跨天跨月数据较慢

建议做法：

- 测试初始化时直接向 TimescaleDB 插入一小批固定时间的原始数据
- 模拟器不负责造跨天历史数据，只负责说明运行链路

步骤：

1. 用 SQL 插入同一设备跨 2 天或跨 2 月的固定数据集
2. 调用 daily/monthly 聚合查询接口
3. 对照预期结果校验 avg/last/max/min/delta

断言：

- 日聚合在整天 interval 下返回正确结果
- 月聚合在 month/year interval 下返回正确结果

推荐级别：P1

说明：

- 这个场景用“SQL 构造固定数据 + 查询接口验证”比强行让模拟器跑几天更简单

---

### 用例 5：Redis 不可用时 latest 降级

目的：

- 验证文档要求的 Redis 故障降级能力

步骤：

1. 先用模拟器写入一批数据
2. 停掉 Redis，或临时让应用 Redis 连接不可用
3. 调用 latest 查询
4. 恢复 Redis
5. 再次调用 latest 查询

断言：

- Redis 不可用时 latest 查询仍能成功返回
- 恢复 Redis 后 latest 查询恢复正常

推荐级别：P1

说明：

- 这是很典型的“容易验证且业务价值高”的回归用例

---

### 用例 6：高频写入下 latest 稳定性

目的：

- 验证在模拟器高并发发送下，latest 查询不会明显异常

输入建议：

- `deviceCount=20~100`
- `messagesPerMinute=600~3000`

步骤：

1. 启动高并发任务
2. 并行轮询若干设备 latest
3. 连续采集 30~60 秒

断言：

- latest 查询接口无大面积失败
- 抽样设备 `E` 值单调不减
- 无明显超时或空值异常

推荐级别：P1

说明：

- 这是“低门槛稳定性回归”，不追求完整压测，只做自动化健康检查

---

## 5. 需要最小增强后再做的 3 个测试

### 增强用例 A：乱序消息不覆盖最新值

这是 Redis latest 设计中最关键的正确性约束之一，但当前模拟器不支持自定义消息时间。

最小改造建议二选一：

方案 A：给模拟器增加可选字段 `fixedTime` / `timeOffsetSeconds`

- 仅在测试模式使用
- 如果配置了，就用指定时间写入消息 `time`

方案 B：新增一个极轻量测试发布脚本

- 不改 UI
- 直接向 broker 发送两条相同设备相同属性消息：
  - 先发新时间戳
  - 再发旧时间戳

测试断言：

- latest 保持新值，不被旧值覆盖
- `redis.latest.stale.blocked` 增长

推荐级别：P0 增强项

---

### 增强用例 B：TTL 过期后回源单飞

目标：

- 验证同一设备 key 过期后，并发 latest 查询不会全部打爆 Raw Layer

前提：

- 测试环境把 Redis latest TTL 调小到几十秒

步骤：

1. 写入设备数据并等待 key 过期
2. 并发发起 20~50 个 latest 请求
3. 统计 Raw 查询次数或相关指标

断言：

- 所有请求成功
- Raw 回源次数明显小于请求数，理想情况下接近 1

推荐级别：P2

说明：

- 如果当前实现里还没有 singleflight，这个测试先不要做成强约束，可先作为待补能力

---

### 增强用例 C：cagg 刷新延迟监控

目标：

- 验证 `timescaledb.cagg.refresh.lag_seconds`、`timescaledb.cagg.refresh.failed` 已暴露

步骤：

1. 启动服务
2. 写入数据
3. 读取 metrics

断言：

- 指标存在
- lag 不是无限增长
- failed 未异常升高

推荐级别：P2

---

## 6. 自动化实现建议

## 6.1 测试技术选型

建议采用“接口驱动 + 数据校验”模式，而不是继续扩写 Playwright UI 用例。

推荐结构：

- 模拟器任务控制：直接调用 `mqtt-simulator` HTTP API
- MQTT broker：复用 [tests/local-broker.js](/D:/project/gplink_ai/gplink/mqtt-simulator/tests/local-broker.js) 或测试环境现有 broker
- 业务结果校验：
  - 优先调用 GPLink 对外查询接口
  - 必要时补充 Redis / TimescaleDB 直连校验

原因：

- UI 自动化更脆弱
- 接口驱动更容易做前置、清理、重试和断言
- 与存储优化目标更贴近

## 6.2 推荐目录

建议新建独立测试目录，例如：

```text
test_guide/storage-optimization/
  latest-hit.js
  latest-fallback.js
  aggregation-hourly.js
  aggregation-daily-monthly.js
  redis-degrade.js
  utils/
    simulator-client.js
    gplink-client.js
    redis-helper.js
    timescaledb-helper.js
```

## 6.3 每个脚本统一模式

每个测试脚本都遵循：

1. 准备环境
2. 清理旧任务/旧数据
3. 创建模拟器任务
4. 启动任务并等待样本数据写入
5. 调用业务查询接口
6. 读取 Redis / TimescaleDB / metrics 做断言
7. 停止任务并清理

这样失败时很好定位是：

- 模拟器没发出去
- broker 没收到
- GPLink 没写入
- Redis/TimescaleDB 查询链路有问题

---

## 7. 推荐优先级

第一批本周可落地：

1. latest 基本命中链路
2. Redis miss 回源回填
3. 小时聚合查询正确性
4. Redis 不可用时 latest 降级

第二批随后补齐：

1. 高频写入下 latest 稳定性
2. 日/月聚合路由正确性

第三批在补最小测试能力后推进：

1. 乱序消息不覆盖最新值
2. TTL 过期回源单飞
3. cagg 刷新监控

---

## 8. 最终建议

如果目标是“尽快形成有用的自动化回归”，最合适的路径不是先追求全覆盖，而是先做下面 4 个：

1. latest 命中
2. latest miss 回源
3. 小时聚合正确性
4. Redis 降级

这 4 个测试已经能覆盖本次存储优化最核心的业务价值，并且几乎都能基于现有 `mqtt-simulator` 和少量辅助脚本完成。

唯一不建议现在强做的是“乱序覆盖”场景，因为当前模拟器消息时间不可控。这个场景很重要，但应该通过“补一个最小测试能力”来做，而不是把现有测试体系绕复杂。
