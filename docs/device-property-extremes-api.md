# 批量查询设备属性极值和发生时间

## 请求与返回

`POST /api/v1/device-data/devices/extremes/_query`

兼容路径：`POST /v1/device-data/devices/extremes/_query`。权限为 `device-opt-api:query`，同时校验 API Token 的设备/产品范围。沿用现有鉴权约定：API Token 只允许访问 `/api/v1/`；`/v1/` 兼容路径使用普通登录认证。

```json
{
  "devices": ["device-1", "device-2"],
  "properties": ["temperature", "pressure"],
  "startTime": 1792944000000,
  "endTime": 1793030400000
}
```

沿用统一响应包装，`result` 为数组；每个设备与属性的组合均占一项：

```json
{
  "status": 200,
  "result": [
    {
      "deviceId": "device-1",
      "property": "temperature",
      "status": "OK",
      "minValue": 12.3,
      "minTime": 1792944000123,
      "maxValue": 35.6,
      "maxTime": 1793020000456
    }
  ]
}
```

上例只展示一项；实际返回全部请求组合。无有效极值时四个数值/时间字段显式为 `null`，不返回零代替空值。

| 项状态 | 含义 |
| --- | --- |
| `OK` | 存在有限数值采样，返回极值与最早发生时间 |
| `NO_DATA` | 时间范围内没有有限数值采样 |
| `UNKNOWN_PROPERTY` | 设备当前物模型不存在该属性 |
| `NON_NUMERIC` | 属性不是数值类型，不将字符串等类型强转为数值 |
| `UNSUPPORTED_STORAGE` | 设备实际存储策略不支持此查询，首版仅实现 `timescaledb-row` |

先按当前设备物模型判断属性是否存在及是否为数值，再判断有效属性的存储能力。不改变现有历史接口对其他类型的处理。

## 时间与数值语义

- `startTime`、`endTime` 必填且前者小于后者，均为 Unix 毫秒时间戳；范围为闭区间 `[startTime,endTime]`，支持公元 1—9999 年。
- `minTime`、`maxTime` 是原始记录的采样时间，保留毫秒，不是入库时间、桶起点或请求边界。相同极值重复出现时取最早采样。
- 不对齐边界、不补值、不从区间外选样本、不自动裁剪未来结束时间。只查询实际保留的原始数据，不使用连续聚合或计量快照恢复已删除的数据。
- 数值比较使用现有 `numberValue`（double precision），不额外舍入。这个接口不提供超出现有浮点存储精度的整数/十进制精度保证。
- 排除 NULL、NaN、正负 Infinity；负数和真实零值正常参与计算。
- 每条批量 SQL 内极值和对应时间使用相同语句快照；跨表/跨批次不保证全库统一快照。

## 批量限制与失败处理

- 设备与属性列表不可空，元素不可为 null、空串或纯空白；按原始 ID 去重，不自动 trim 或改变大小写。
- 去重后最多 500 台设备、100 个属性；按设备输入顺序、属性输入顺序返回。
- 全部设备先完成存在性和权限检查，再读取历史。任何设备不存在返回 404，权限不足拒绝整个请求。
- 缺失属性、非数值属性和不支持的存储逐项标记，不阻断其他有效项。
- 实际数据库异常、元数据/存储解析失败、超时使整个请求失败，不降级为 `NO_DATA`，不输出部分成功结果。
- 按实际数据库、物理表和设备标识列合并有效组合；每批最多 1,000 个组合，查询并发最多 5。总超时为 30 秒，包含授权、元数据、排队及执行；取消复用现有 PostgreSQL 查询取消机制。
- 不缓存结果、不新增表或索引、不改变数据保留及预聚合策略。

## SQL 方案

每个批次一条参数化 SQL：`requested VALUES` 限定设备属性组合，原始表按时间范围过滤并同时计算 `MIN(numberValue)` / `MAX(numberValue)`，再用两个 `LEFT JOIN LATERAL` 按该极值查找最早记录。

时间条件直接作用在 `timestamp` 列，可利用 TimescaleDB chunk 裁剪及现有 `(deviceId,property,timestamp DESC)` 索引；倒序时间索引也可反向扫描。并不假定时间索引可以直接求出数值极值：极值聚合仍需读取范围内有效样本，最早时间定位也可能扫描并过滤多条记录。Java 只接收每组合一行结果。

请求参数全部绑定；物理表和设备列经现有存储策略、MetricBuilder 解析并对标识符转义。不会把请求中的设备或属性 ID 拼接为 SQL 标识符。

## 验证方式

在后端 Maven 根目录执行（JDK 17）：

```powershell
mvn.cmd -pl jetlinks-manager/saas-manager -am `
  '-Dtest=LegacyDeviceExtremesTest,DeviceExtremesBatchTest,PropertyExtremesQueryTest,PropertyExtremesIntegrationTest,ApiTokenPermissionCatalogTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dextremes.test.url=r2dbc:postgresql://postgres@127.0.0.1:55437/postgres' `
  '-Dextremes.test.benchmark=true' test
```

数据库必须是专用可销毁实例并安装 TimescaleDB 扩展。测试会建立并删除自己的 hypertable，包含压缩 chunk 正确性验证；不可指向业务数据库。没有 `extremes.test.url` 时数据库集成测试跳过，不能据此宣称真实 SQL 已验证。

性能测试写入 8 台设备 × 3 个属性、2023—2025 年每小时采样数据，分别测试小时、日、月、两年范围。与逐组合分别按数值排序查询最小/最大记录的 48 条 SQL 比较，校验值与最早时间一致，记录三次调用的客户端耗时中位数。双方复用相同连接池；基线按顺序发出查询，测试不包含 HTTP、授权或设备元数据耗时。执行计划和统计输出在 `timescaledb-component/target/extremes-benchmark/`。这些数据只用于本机对照，不代表线上延迟保证。

### 2026-10-26 本机验证记录

Windows、JDK 17、PostgreSQL 16.13、TimescaleDB 2.26.1；独立测试实例，421,080 条未压缩原始数据，24 个查询组合，每组返回一项。压缩数据块的正确性另有测试覆盖。

本次定向测试与相关历史查询回归共 76 项通过，0 失败、0 跳过；后端根项目 `mvn.cmd -DskipTests compile` 全模块通过。测试日志为工作区根目录的 `extremes-verified-tests.log`，全后端编译日志为 `extremes-backend-compile.log`。测试实例已停止，没有部署到业务环境。

| 范围 | 新方案 SQL 数 | 基线 SQL 数 | 新方案客户端中位耗时 | 基线客户端中位耗时 |
| --- | ---: | ---: | ---: | ---: |
| 1 小时 | 1 | 48 | 21.75 ms | 210.56 ms |
| 1 天 | 1 | 48 | 30.14 ms | 178.83 ms |
| 1 月 | 1 | 48 | 60.46 ms | 457.32 ms |
| 跨 2 年 | 1 | 48 | 679.52 ms | 6,207.10 ms |

`EXPLAIN (ANALYZE, BUFFERS)` 确认：短时间范围裁剪到相应 chunk；月范围只访问覆盖请求时间的 chunk；两年范围的最早极值定位使用设备/属性/时间复合索引反向扫描，并在命中后跳过后续 chunk。执行器也可能选择 bitmap scan 和小规模排序，这是优化器按代价做出的选择，不保证所有范围都采用完全相同的索引计划。
