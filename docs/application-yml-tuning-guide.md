# application.yml 配置调优指南

> 适用版本：GPLink / JetLinks Community 2.10  
> 核心原则：**写入吞吐量决定分区，RAM 决定 chunk 大小，并发数决定锁竞争**

---

## 一、chunk-time-interval（分区时间间隔）

```yaml
timescaledb:
  things-data:
    chunk-time-interval: 1h  # 按写入量决定
```

**原则：单个 chunk 大小 ≈ 可用 RAM 的 25%**

```
目标 chunk 行数 = RAM × 25% ÷ 100B（每行约 100 字节）
chunk 间隔    = 目标行数 ÷ 行/秒
```

| 设备数 | 上报频率 | 采集点 | 行/秒 | 建议间隔（32GB RAM） |
|--------|---------|--------|------|-------------------|
| 500 | 1次/min | 150 | 1,250 | 1d |
| 2,000 | 1次/min | 150 | 5,000 | 4h |
| 5,000 | 1次/min | 150 | 12,500 | 1h |
| 5,000 | 1次/10s | 150 | 75,000 | 10min |

**错误案例**：默认 `7d` + 5000 台设备 = 单 chunk 超过 100GB，所有写入打到同一个文件，触发 `DataFileWrite` 锁等待，CPU 周期性跳变。

---

## 二、write-buffer（写入缓冲）

```yaml
timescaledb:
  write-buffer:
    parallelism: 4     # 并发 worker 数
    size: 1000         # 每批写入行数
    timeout: 1s        # 最长等待时间，超时强制刷新
    mvstore-cache-mb: 8  # 写入队列的 H2 缓存上限
```

**原则：并发数不是越大越好**

- `parallelism` 过高（如 16）→ 多个 worker 同时写同一 chunk → 锁竞争 → 写入反而更慢
- 单机 PostgreSQL 建议 **4~8**，写入量大时先调大 `size`，再考虑增加 `parallelism`
- `mvstore-cache-mb` 默认无上限，会吃掉 ~50% 堆内存，**必须设置**，8MB 已够用

| 行/秒 | parallelism | size |
|------|------------|------|
| < 5,000 | 4 | 1,000 |
| 5,000~15,000 | 4~6 | 1,000~2,000 |
| > 15,000 | 6~8 | 2,000~3,000 |

---

## 三、cagg（连续聚合视图）

```yaml
timescaledb:
  things-data:
    cagg:
      enabled: true
      refresh-interval: 1h   # 小时 cagg 刷新频率
      start-offset: 3h        # 刷新窗口起点（距现在多远）
      end-offset: 1h          # 刷新窗口终点（跳过最近1小时不完整数据）
      retention: 3y
      daily-enabled: true
      daily-start-offset: 3d  # 日级 cagg 窗口起点，最小值 > end_offset + schedule_interval
      monthly-enabled: true
```

**原则：窗口必须满足 `start_offset - end_offset > schedule_interval`**

| cagg 级别 | start_offset | end_offset | schedule | 窗口 | 是否满足 |
|----------|-------------|-----------|---------|------|---------|
| 小时 | 3h | 1h | 1h | 2h > 1h | ✓ |
| 日 | 3d | 1d | 1d | 2d > 1d | ✓ |
| 日（错误示例）| 2d | 1d | 1d | 1d = 1d | ✗ 报错 |

**cagg 适用场景**：有历史统计查询需求（如"过去30天每天的平均温度"）。  
如果没有此类查询，关掉 `cagg.enabled: false` 可节省 CPU 和存储。

---

## 四、compress（列压缩）

```yaml
timescaledb:
  things-data:
    compress: true
    compress-after: 30d   # 超过30天的chunk自动压缩
    compress-order-by: "timestamp DESC"
```

**原则：压缩只对冷数据（不再写入的 chunk）有效**

- `compress-after` 必须 > `chunk-time-interval`，否则还在写入的 chunk 被压缩会报错
- 压缩比通常 5~10x，历史数据多时收益很大
- 压缩后的 chunk 仍可查询，但写入会解压再压缩，所以只压缩冷数据

---

## 五、R2DBC 连接池

```yaml
spring:
  r2dbc:
    pool:
      max-size: 32        # 连接数上限
      max-idle-time: 2m   # 空闲连接超时回收
      max-life-time: 10m  # 连接最长存活时间
      max-acquire-time: 30s  # 等待连接超时
```

**原则：连接数 ≈ CPU 核心数 × 2~4**

- 连接数过多（如 128）→ PostgreSQL 每个连接消耗约 5~10MB 内存 + 上下文切换开销
- 连接数过少 → 高并发时等待获取连接
- 8 核机器建议 `max-size: 16~32`

---

## 六、JVM 参数（启动脚本 / docker-compose JAVA_OPTS）

```bash
java \
  -Xms256m -Xmx2g \                                         # 内存上限按吞吐量选（见 jvm-tuning-by-throughput.md）
  -XX:SoftRefLRUPolicyMSPerMB=0 \                           # 让 GC 积极回收 Spring 反射缓存（节省~100MB）
  -Djetlinks.things.data.local.max-l1-cache=300000 \        # L1 缓存条目数 = 设备数 × 采集点 × 0.7
  -Djetlinks.things.data.local.l1-expire=120s \             # 缓存条目 TTL，高吞吐缩短加速淘汰
  -Djetlinks.things.data.local.mvstore-cache-mb=8 \         # things-data 本地 H2 缓存上限
  -Djetlinks.things.data.store.flush-interval=1s \          # 脏数据落盘间隔
  -jar jetlinks-standalone.jar
```

> **注意**：`-Xms` 和 `-Xmx` 必须同时设置。只写 `-Xmx4g` 不写 `-Xms`，JVM 启动即占用 3~4GB 物理内存。

详细参数按吞吐量选取，见 [jvm-tuning-by-throughput.md](./jvm-tuning-by-throughput.md)。

---

## 七、单产品设备数上限

没有硬性上限，根据写入量决定：

- **关键公式**：`行/秒 = 设备数 × 上报频率(次/秒) × 采集点数`
- **舒适上限**（单机 32GB RAM）：`行/秒 < 15,000`（约 5000 台 × 1次/min × 150点）
- 超出后优先缩短 `chunk-time-interval`，而不是拆分产品
- 同类设备应放同一产品，**不要为了分散写压力人为拆分产品**

---

## 快速决策表

| 现象 | 根因 | 调整项 |
|------|------|-------|
| PG CPU 周期性跳变 | chunk 太大，写入锁竞争 | 缩小 `chunk-time-interval` |
| JVM 堆内存持续增长 | MVStore 缓存无上限 | 设置 `mvstore-cache-mb: 8` |
| 写入延迟高但 CPU 不高 | write-buffer 积压 | 增大 `parallelism` 或 `size` |
| cagg 刷新报 window too small | start/end_offset 配置错误 | 确保 `start - end > schedule` |
| 历史查询很慢 | 缺少 cagg 或未命中索引 | 开启 `cagg.enabled: true` |
| PG 连接数过高 | 连接池过大 | 降低 `r2dbc.pool.max-size` |
