# things-property 内存泄露分析与治理方案

## 1. 问题背景

现场现象：

- MAT `Leak Suspects` 显示 `org.h2.mvstore.MVStore` 保留约 `164 MB`，约占堆内存 `33.30%`
- `org.springframework.boot.loader.launch.LaunchedClassLoader` 保留约 `92 MB`
- `org.jetlinks.community.things.data.AutoUpdateThingsDataManager` 保留约 `53 MB`
- Histogram 中 `LocalFileThingsDataManager$Property`、`PropertyHistory`、`String`、`ConcurrentHashMap$Node[]` 数量明显偏大

运行负载：

- 每分钟约 `2500` 条设备消息
- 每条消息约 `150` 个采集点
- 折算后约每分钟 `375000` 次属性更新

在这个吞吐下，如果本地缓存和索引结构没有明确上界，堆内存会随着运行时间持续上涨。

## 2. Heap Dump 关键结论

### 2.1 主保留链

MAT 报告中的几条主链可以合并理解：

1. `MVStore -> CacheLongKeyLIRS$Segment[]`
2. `AutoUpdateThingsDataManager -> ConcurrentHashMap$Node[]`
3. `CacheLongKeyLIRS$Segment[] -> 大量 String`

这几条链共同说明：

- 一部分内存来自 H2 `MVStore` 的页缓存
- 一部分内存来自 `AutoUpdateThingsDataManager` / `LocalFileThingsDataManager` 的内存索引和属性历史缓存
- 大量 `String`、`Property`、`PropertyHistory` 不是偶发对象，而是被长期引用

### 2.2 Histogram 对应关系

从截图可见，以下对象数量异常：

- `org.jetlinks.community.things.data.LocalFileThingsDataManager$Property` 约 `600` 万个
- `org.jetlinks.community.things.data.LocalFileThingsDataManager$PropertyHistory` 约 `77` 万个
- `java.lang.String` 约 `132` 万个
- `ConcurrentHashMap$Node[]` retained heap 很高

这和代码中的数据结构完全对应：

- `Property` 是单个属性历史节点
- `PropertyHistory` 是某个设备某个属性的历史链
- `l1Cache`、`tagCache`、`updaters/include` 都基于内存 Map/Set

## 3. 代码定位结果

### 3.1 Bean 创建点

文件：`back/jetlinks-community-2.10/jetlinks-community-2.10/jetlinks-components/things-component/src/main/java/org/jetlinks/community/things/configuration/ThingsConfiguration.java`

关键逻辑：

```java
@Bean(destroyMethod = "shutdown")
public AutoUpdateThingsDataManager thingsDataManager(EventBus eventBus) {
    String fileName = "./data/things-property/data";
    return new AutoUpdateThingsDataManager(fileName, eventBus);
}
```

说明：

- `AutoUpdateThingsDataManager` 是全局单例
- 只要服务不重启，这个对象内的缓存、订阅器、H2 Store 都会持续存在

### 3.2 `AutoUpdateThingsDataManager`

文件：`back/jetlinks-community-2.10/jetlinks-community-2.10/jetlinks-components/things-component/src/main/java/org/jetlinks/community/things/data/AutoUpdateThingsDataManager.java`

关键点：

- `updaters` 使用 Caffeine，但只有 `expireAfterAccess(10m)`，没有 `maximumSize`
- `Updater.include` 使用 `ConcurrentHashMap.newKeySet()`，没有 TTL，也没有容量上限
- 每次 `getProperties/getLastProperty/getFirstProperty` 都会触发 `tryLoad(property)`，把访问过的属性记入 `include`
- `Updater` 会持续订阅 EventBus，并对 `include` 里的属性做增量更新

影响：

- 访问过的设备越多，`updaters` 越大
- 某个设备访问过的属性越多，`include` 越大
- 一旦属性进入 `include`，在 `Updater` 生命周期内就会持续接收更新
- 在高吞吐场景下，这些更新会不断把数据推入下层 `l1Cache` 和 `MVStore`

### 3.3 `LocalFileThingsDataManager`

文件：`back/jetlinks-community-2.10/jetlinks-community-2.10/jetlinks-components/things-component/src/main/java/org/jetlinks/community/things/data/LocalFileThingsDataManager.java`

关键内存结构：

- `private final Map<String, Integer> tagCache = new ConcurrentHashMap<>();`
- `private final Map<StoreKey, PropertyHistory> l1Cache = new ConcurrentHashMap<>();`
- `private final MVStore mvStore;`
- `private final MVMap<Long, PropertyHistory> historyStore;`

关键问题：

#### 1. `l1Cache` 无上界

`l1Cache` 保存设备属性对应的 `PropertyHistory`，没有容量限制，也没有基于访问时间的统一淘汰。

虽然 `flushNow()` 中存在“空闲两轮后删除”的逻辑，但它依赖：

- 对象能被正确标记为 idle
- flush 周期能及时扫到
- 条目没有再次被更新

在高频写入下，大量条目会一直保持活跃或反复活跃，导致 `l1Cache` 长时间膨胀。

#### 2. `tagCache` 无上界

`tagCache` 保存 `thingType:thingId` 和 `property` 到整数 tag 的映射，没有淘汰策略。

只要出现过新的设备 ID 或新的属性名，这些 key 就会留在内存中。

#### 3. `MVStore` cache 按堆大小放大

```java
static int CACHE_SIZE = (int) Math.max(64, Runtime.getRuntime().maxMemory() / 1024 / 1024 / 64);
```

然后：

```java
c -> c.keysPerPage(1024)
      .cacheSize(CACHE_SIZE)
```

问题在于：

- `MVStore` cache 不是按业务可接受量配置，而是按 JVM 最大堆线性放大
- 堆越大，本地 H2 页缓存越大
- 这会放大 `CacheLongKeyLIRS$Segment[]` 的 retained heap

#### 4. 属性历史对象量大且持续存活

`PropertyHistory` 内部通过链表保留多个 `Property` 节点。

虽然单个属性历史长度受 `jetlinks.things.data.store.max-size` 控制，默认值为 `8`，但在以下条件下总体对象数仍会很大：

- 设备数多
- 每设备属性数多
- 访问和更新覆盖面大
- `l1Cache` 不及时释放

## 4. 是否属于内存泄露

结论：**属于设计性内存泄露 / 无界缓存泄露，不是单纯正常缓存。**

原因：

1. 关键对象由全局单例长期持有
2. 多个核心结构没有业务上界
3. 对象增长与时间和访问面强相关，而不是只与瞬时并发相关
4. 即使没有功能异常，堆会随着运行逐步抬升
5. MAT 已经能稳定定位到 `AutoUpdateThingsDataManager`、`MVStore`、`ConcurrentHashMap` 等长期保留对象

严格说，这不是“对象永远无法释放”的经典代码泄露，但在工程上应按内存泄露治理，因为：

- 它会导致老年代长期膨胀
- Full GC 后仍难以下降到健康水平
- 运行时间越久风险越高

## 5. 根因总结

根因可以归纳为四点：

1. `things-property` 被设计成“本地持久化 + 内存热点 + 访问即订阅更新”的组合，但没有为高吞吐场景设计容量边界
2. `updaters`、`include`、`l1Cache`、`tagCache` 都可能随访问面扩大而增长
3. `MVStore` 的 page cache 配置过于激进
4. 当前系统已经启用 TimescaleDB，本地 `things-property` 仍承担过多历史缓存职责，职责边界不清晰

## 6. 治理目标

结合当前业务诉求，治理目标定义为：

- 保留“最近一小段历史”的本地加速能力
- 接受冷数据首次查询变慢
- 长期历史查询以 TimescaleDB 为准
- 把 `things-property` 从“潜在长期存储”收缩为“短窗口本地缓存层”
- 让堆内存随时间进入平台期，而不是持续线性增长

## 7. 设计方案

### 7.1 收紧 `updaters`

改造 `AutoUpdateThingsDataManager`：

- 给 `updaters` 增加 `maximumSize`
- 保留 `expireAfterAccess`，但把默认值从 `10m` 收紧到 `5m`
- 淘汰时继续 `dispose()` 订阅

建议配置：

```properties
jetlinks.things.data.local.max-updaters=20000
jetlinks.things.data.local.updater-expire=5m
```

### 7.2 收紧 `Updater.include`

当前 `include` 是无界 `Set<String>`，应改为：

- 带 TTL 的属性访问缓存，或
- 带最大数量限制的缓存结构

推荐策略：

- 只保留最近 `5` 分钟内被查询过的属性
- 每个设备最多保留 `256` 个活跃属性

这样可以避免某个设备曾经被查过的所有属性都永久参与增量更新。

建议配置：

```properties
jetlinks.things.data.local.include-expire=5m
jetlinks.things.data.local.max-include-per-updater=256
```

### 7.3 收紧 `l1Cache`

将 `l1Cache` 从无界 `ConcurrentHashMap` 改为有上限缓存。

要求：

- 支持 `maximumSize`
- 支持 `expireAfterAccess` 或 `expireAfterWrite`
- 条目淘汰前，如果是 dirty 状态，必须先 flush 再移除

推荐默认值：

```properties
jetlinks.things.data.local.max-l1-cache=300000
jetlinks.things.data.local.l1-expire=10m
```

这样可以把 `PropertyHistory` 和 `Property` 的驻留数量压到一个可控范围。

### 7.4 收紧 `tagCache`

`tagCache` 应只作为热点 tag 的内存镜像，不应永久保存全部映射。

建议：

- 内存中只缓存热点 tag
- 冷 tag 从 `tagStore` 回源读取
- 加上 `maximumSize`

建议配置：

```properties
jetlinks.things.data.local.max-tag-cache=100000
```

### 7.5 调低单属性历史长度

当前默认：

```properties
jetlinks.things.data.store.max-size=8
```

在你当前吞吐下，建议先降为：

```properties
jetlinks.things.data.store.max-size=2
```

如需兼顾更短时间窗口，可酌情设为 `3`，不建议继续维持 `8`。

### 7.6 收紧 flush 策略

当前 flush 周期默认 `30s`，对高吞吐场景偏长。

建议改为：

```properties
jetlinks.things.data.store.flush-interval=5s
```

目的：

- 更快把 dirty 数据落入 `historyStore`
- 缩短脏对象在内存中的停留时间
- 降低一次 flush 的集中处理量

同时建议把 `flushNow()` 从“全量扫描 `l1Cache`”逐步改造成“优先扫描 dirty/活跃集合”，减少大 Map 全量遍历带来的停顿和对象延寿。

### 7.7 收紧 `MVStore` cache

将 `CACHE_SIZE` 改为可配置，不再按 JVM 堆线性放大。

建议配置：

```properties
jetlinks.things.data.local.mvstore-cache-mb=24
```

推荐初始范围：

- `16 MB` 到 `32 MB`

不建议继续使用“随堆大小自动增长”的策略。

### 7.8 明确本地缓存职责

建议明确约束：

- 本地 `things-property` 只承担最近窗口数据加速
- 历史跨度较大的查询直接走 TimescaleDB
- 批量历史查询默认绕过本地缓存

这一步是长期稳定的关键，否则即使做了缓存上限，后面仍会反复在命中率和内存之间拉扯。

## 8. 推荐实施顺序

### 第一阶段：快速止血

目标：尽快阻止堆继续线性上涨

实施项：

1. 给 `updaters` 增加 `maximumSize`
2. 给 `include` 增加 TTL 和数量上限
3. 给 `l1Cache` 增加容量上限
4. 把 `jetlinks.things.data.store.max-size` 调低到 `2`
5. 把 `jetlinks.things.data.store.flush-interval` 调低到 `5s`
6. 把 `MVStore` cache 改成固定小值

### 第二阶段：结构优化

目标：减少全量扫描和重复驻留

实施项：

1. 重构 `flushNow()`，避免周期性全量扫描大 `l1Cache`
2. `tagCache` 改成热点缓存
3. 查询链路上增加“超出本地窗口直接走 TimescaleDB”的分流

### 第三阶段：验证与调优

目标：找到适合现网的平衡点

调优项：

- `max-updaters`
- `max-include-per-updater`
- `max-l1-cache`
- `store.max-size`
- `mvstore-cache-mb`

## 9. 验证方案

### 9.1 功能验证

验证点：

- 最近值查询正常
- 最近短窗口历史查询正常
- 冷数据重新访问时可正确从磁盘或时序库回源
- `Updater` 淘汰后重新访问可自动恢复订阅

### 9.2 压测验证

至少模拟接近现网的负载：

- `2500` 条消息/分钟
- 每条 `150` 个属性
- 持续 `30` 到 `60` 分钟

观察项：

- 堆内存曲线是否进入平台期
- Old Gen 是否持续上涨
- Full GC 后堆是否明显回落
- 本地查询延迟是否可接受
- TimescaleDB 查询压力是否在可控范围内

### 9.3 Heap Dump 对比验证

修复后重新抓 heap dump，重点确认：

- `AutoUpdateThingsDataManager` retained heap 明显下降
- `ConcurrentHashMap$Node[]` retained heap 下降
- `LocalFileThingsDataManager$Property`、`PropertyHistory` 数量明显下降
- `MVStore CacheLongKeyLIRS$Segment[]` 不再成为绝对主嫌疑对象

## 10. 建议的初始配置

建议先用以下参数作为第一版：

```properties
jetlinks.things.data.store.max-size=2
jetlinks.things.data.store.flush-interval=5s
jetlinks.things.data.local.updater-expire=5m
jetlinks.things.data.local.max-updaters=20000
jetlinks.things.data.local.include-expire=5m
jetlinks.things.data.local.max-include-per-updater=256
jetlinks.things.data.local.max-l1-cache=300000
jetlinks.things.data.local.l1-expire=10m
jetlinks.things.data.local.max-tag-cache=100000
jetlinks.things.data.local.mvstore-cache-mb=24
```

如果第一轮仍然偏大，再继续下调：

- `max-l1-cache`
- `max-updaters`
- `mvstore-cache-mb`

## 11. 最终结论

结论如下：

- 当前代码存在明显的无界缓存问题，应按内存泄露治理
- `MVStore` 大内存占用不是孤立问题，而是被上层 `AutoUpdateThingsDataManager` / `LocalFileThingsDataManager` 的设计共同放大
- 在每分钟 `37.5` 万属性更新的场景下，现有实现缺少容量边界，长期运行后堆膨胀是高概率事件
- 推荐把 `things-property` 收缩为短窗口本地缓存层，并给 `updaters`、`include`、`l1Cache`、`tagCache`、`MVStore cache` 全部加硬上界

这套治理方案的核心不是“完全消灭缓存”，而是让缓存行为可预测、可配置、可回收。
