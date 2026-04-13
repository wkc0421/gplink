# JVM 启动参数调优指南（按上报吞吐量）

> 适用版本：GPLink / JetLinks Community 2.10  
> 场景假设：每台设备每次上报 **150 个采集点**，设备持续周期性上报。

## 参数选取依据

| 参数 | 说明 | 计算方式 |
|------|------|----------|
| `-Xms` | JVM 初始堆（**必须设置**） | 固定 256m；JVM 按需增长，避免启动即占满 `-Xmx` |
| `-Xmx` | JVM 最大堆上限 | L1 cache 条目数 × 160 B + 应用基础内存 300MB，向上取整 |
| `max-l1-cache` | LocalFileThingsDataManager L1 缓存条目数 | 设备数 × 150 × 0.7（70% 命中率即可，其余走 H2 MVStore） |
| `l1-expire` | L1 缓存 TTL | 高吞吐时缩短以加速淘汰，低吞吐可适当放宽 |
| `mvstore-cache-mb` | things-data H2 MVStore 读缓存上限 | 固定 8MB，高吞吐场景可适当调大 |
| `flush-interval` | L1 脏数据落盘间隔 | ≥ 5000 msg/min 建议 1s，否则 2s |
| `parallelism` | TimescaleDB 写入 worker 数 | 写入速率（条/秒）÷ 1000，每个 worker 约消化 1000 条/秒 |
| `write-buffer.size` | TimescaleDB 批次大小 | 吞吐越高批次越大，减少 DB round-trip |
| `-XX:SoftRefLRUPolicyMSPerMB=0` | GC 积极回收 soft-reference | 固定加上，消除 Spring 反射缓存堆积（可节省 ~100MB） |

> **注意**：`-Xms` 和 `-Xmx` 必须同时设置。若只写 `-Xmx4g` 不写 `-Xms`，
> JVM 默认初始堆为 `-Xmx` 的 1/4（即 1g），加上 Metaspace、直接内存、线程栈，
> 进程启动时即占用 3~4GB 物理内存。

---

## 500 msg/min × 150 pts = 1,250 条/秒

```bash
java -Xms256m -Xmx512m \
     -XX:SoftRefLRUPolicyMSPerMB=0 \
     -Djetlinks.things.data.local.max-l1-cache=100000 \
     -Djetlinks.things.data.local.l1-expire=120s \
     -Djetlinks.things.data.local.mvstore-cache-mb=8 \
     -Djetlinks.things.data.store.flush-interval=2s \
     -Dtimescaledb.write-buffer.parallelism=4 \
     -Dtimescaledb.write-buffer.size=1000 \
     -jar jetlinks-standalone.jar
```

---

## 1,000 msg/min × 150 pts = 2,500 条/秒

```bash
java -Xms256m -Xmx768m \
     -XX:SoftRefLRUPolicyMSPerMB=0 \
     -Djetlinks.things.data.local.max-l1-cache=200000 \
     -Djetlinks.things.data.local.l1-expire=120s \
     -Djetlinks.things.data.local.mvstore-cache-mb=8 \
     -Djetlinks.things.data.store.flush-interval=1s \
     -Dtimescaledb.write-buffer.parallelism=6 \
     -Dtimescaledb.write-buffer.size=1500 \
     -jar jetlinks-standalone.jar
```

---

## 2,000 msg/min × 150 pts = 5,000 条/秒

```bash
java -Xms256m -Xmx1g \
     -XX:SoftRefLRUPolicyMSPerMB=0 \
     -Djetlinks.things.data.local.max-l1-cache=300000 \
     -Djetlinks.things.data.local.l1-expire=120s \
     -Djetlinks.things.data.local.mvstore-cache-mb=8 \
     -Djetlinks.things.data.store.flush-interval=1s \
     -Dtimescaledb.write-buffer.parallelism=8 \
     -Dtimescaledb.write-buffer.size=2000 \
     -jar jetlinks-standalone.jar
```

---

## 5,000 msg/min × 150 pts = 12,500 条/秒

```bash
java -Xms256m -Xmx2g \
     -XX:SoftRefLRUPolicyMSPerMB=0 \
     -Djetlinks.things.data.local.max-l1-cache=500000 \
     -Djetlinks.things.data.local.l1-expire=120s \
     -Djetlinks.things.data.local.mvstore-cache-mb=8 \
     -Djetlinks.things.data.store.flush-interval=1s \
     -Dtimescaledb.write-buffer.parallelism=16 \
     -Dtimescaledb.write-buffer.size=2000 \
     -jar jetlinks-standalone.jar
```

---

## 10,000 msg/min × 150 pts = 25,000 条/秒

```bash
java -Xms256m -Xmx4g \
     -XX:SoftRefLRUPolicyMSPerMB=0 \
     -Djetlinks.things.data.local.max-l1-cache=800000 \
     -Djetlinks.things.data.local.l1-expire=60s \
     -Djetlinks.things.data.local.mvstore-cache-mb=16 \
     -Djetlinks.things.data.store.flush-interval=1s \
     -Dtimescaledb.write-buffer.parallelism=24 \
     -Dtimescaledb.write-buffer.size=3000 \
     -jar jetlinks-standalone.jar
```

---

## 快速对照表

| msg/min | pts/msg | 条/秒 | `-Xms` | `-Xmx` | `max-l1-cache` | `parallelism` | `buffer.size` |
|--------:|--------:|------:|--------|--------|---------------:|--------------:|--------------:|
| 500 | 150 | 1,250 | 256m | 512m | 100,000 | 4 | 1,000 |
| 1,000 | 150 | 2,500 | 256m | 768m | 200,000 | 6 | 1,500 |
| 2,000 | 150 | 5,000 | 256m | 1g | 300,000 | 8 | 2,000 |
| 5,000 | 150 | 12,500 | 256m | 2g | 500,000 | 16 | 2,000 |
| 10,000 | 150 | 25,000 | 256m | 4g | 800,000 | 24 | 3,000 |

---

## Docker Compose 示例

```yaml
services:
  jetlinks:
    image: jetlinks-standalone:latest
    environment:
      JAVA_OPTS: >-
        -Xms256m
        -Xmx2g
        -XX:SoftRefLRUPolicyMSPerMB=0
        -Djetlinks.things.data.local.max-l1-cache=500000
        -Djetlinks.things.data.local.l1-expire=120s
        -Djetlinks.things.data.local.mvstore-cache-mb=8
        -Djetlinks.things.data.store.flush-interval=1s
        -Dtimescaledb.write-buffer.parallelism=16
        -Dtimescaledb.write-buffer.size=2000
    command: ["sh", "-c", "java $JAVA_OPTS -jar jetlinks-standalone.jar"]
```

---

## 验证方法

```bash
# 观察老年代增长趋势，应趋于平稳
jstat -gcutil <pid> 5000

# 查看 L1 缓存实时大小（Micrometer 指标）
curl http://localhost:8848/actuator/metrics/things.data.l1cache.size

# 查看 TimescaleDB 写入缓冲积压量
curl http://localhost:8848/actuator/metrics/jetlinks.buffer.queue.size
```

堆内存健康标志：老年代（O 列）在 GC 后能回落并保持平稳，不持续线性增长。
