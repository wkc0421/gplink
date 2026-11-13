# 模拟历史数据补录

入口：设备管理 → 设备数据接口测试 → 模拟数据补录。

选择一个已有设备和一个数值型采集项，填写北京时间范围、5/15/30/60 分钟间隔，以及随机范围或递增初值/步长。点击“检查补录参数”后提交。修改参数会使预检失效；提交时服务端再次检查。结束时间不包含在样本中，跨度最多两个日历年，并受实际数据保留策略限制。

任务完成后可带入统计查询，或查看分页的原始属性历史。统计查询自动选择不超过现有桶数限制的粒度。任务列表每 3 秒刷新，离开补录页停止刷新。失败保留已经提交的数据，只能重试原任务；不存在覆盖或清空入口。

## 数据与兼容性

- 仅支持 TimescaleDB 行模式，要求小时、日、月聚合全部启用。缺失存储对象通过现有元数据注册流程初始化；不兼容对象拒绝执行。
- 原始历史每批最多 1000 条，只 INSERT，不写 Redis、不发布设备实时消息。任意已有数据（包括非采样网格上的记录）均构成冲突。
- `value` 保存十进制文本，`numberValue` 沿用现有双精度列。无法保留声明精度的数值会明确拒绝，包括超过安全表示范围的大整数。未声明精度的小数项默认两位。
- 小时、日、月聚合依次刷新到完整桶边界；直接核对物化表的计数、SUM、MIN、MAX，避免实时查询回退掩盖刷新缺失。AVG 可由 SUM/COUNT 独立核算。聚合由同产品设备共享。
- 可选累计量快照默认关闭。开启时按需为目标设备单独启用采集项统计，要求设备写权限；已有启用配置的统计范围保留。只为目标设备生成小时边界及其中的日边界，未来边界不生成，无历史读数保留无值状态。设备有效物模型中的自定义数值属性也可开启此选项，其他设备和产品默认不受影响。
- 未勾选时仍执行现有变更登记，维护已有统计配置的来源版本、设备名单及脏区间。不会绕过缓存失效机制。

新增两张普通表位于实际 TimescaleDB 数据源的当前 schema，由服务初始化：

| 表 | 用途 |
|---|---|
| `device_data_simulation_task` | 不可变参数快照、种子、操作者、状态、游标、刷新版本、租约及错误 |
| `device_data_simulation_sample` | 任务与采样序号/时间的归属凭据；与原始插入、变更登记及游标同事务提交 |

第二张表是对原方案的必要适配：现有默认属性历史表忽略 `id` 列，因此稳定记录标识保存于归属表，不改变原始表结构。重试同时核对归属、时间、文本值、数值及数量；不允许删除或篡改明细后继续导入。

数据库唯一约束与事务锁保证同一时刻只有一个补录任务运行。租约 15 分钟，批次和阶段会续租；单次刷新/快照生成最长 8 分钟。进程退出后，新进程接管过期租约。原始批次的瞬时连接/事务故障最多自动重试两次，其余失败保留进度，等待手工重试。聚合来源版本未变化时从保存的层级/月游标继续，变化时重新验证并刷新。

## 管理接口

基础路径 `/device-data/simulation`（前端请求由统一网关补 `/api` 前缀）。沿用管理端认证及 `device-instance` 的查看/写入权限；这些管理接口没有扩大服务 API Token 原有的公开路径范围。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/devices/{deviceId}/properties` | 有效数值属性、精度、范围、快照资格 |
| POST | `/_preview` | 只读预检 |
| POST | `/tasks` | 创建持久化异步任务 |
| GET | `/tasks?pageIndex=0&pageSize=20` | 当前操作者的任务，继续校验设备数据范围 |
| GET | `/tasks/{taskId}` | 任务参数及阶段进度 |
| POST | `/tasks/{taskId}/_retry` | 原参数重试失败任务 |

提交字段：`deviceId`、`propertyId`、`requestId`、`startTime`、`endTime`、`intervalMinutes`、`mode`、`snapshots`。`mode=RANDOM` 时传十进制字符串 `min/max`；`mode=INCREMENT` 时传十进制字符串 `initial/increment`。请求 ID 为 8–80 位字母、数字、下划线或连字符，同一操作者重复使用时参数必须一致。SQL 对象、产品及类型均由服务端解析。

## 本地验证

后端从 `back/jetlinks-community-2.10/jetlinks-community-2.10` 执行，使用 Java 17：

```powershell
mvn -pl jetlinks-manager/saas-manager -am '-Dtest=SimulationSpecTest,SimulationIntegrationTest,DeviceDataSimulationControllerTest,CounterSnapshot*Test,LegacyCounterIntervalRoutingTest,LegacyDeviceIntervalQueryTest,LegacyIntervalSummaryCompatibilityTest,ApiTokenPermissionCatalogTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dsimulation.test.url=r2dbc:postgresql://postgres@127.0.0.1:55438/simulation_it' test
```

集成测试只能指定**可丢弃的测试库**，需要预装 TimescaleDB 扩展及创建表/聚合/后台策略的权限。未提供 `simulation.test.url` 时数据库测试跳过。测试创建独立产品历史表，覆盖真实物化聚合、目标快照、批次失败重试、随机序列租约恢复、当前未结束桶、其他设备数据保留、跨闰年两年 210,528 条数据；结束后删除测试对象。

前端从 `front` 执行：

```powershell
pnpm exec vite --config tests/simulation-ui/vite.config.ts
# 在另一终端执行
pnpm exec playwright test simulation-ui.spec.ts simulation-utils.spec.ts device-data-query-utils.spec.ts
pnpm exec vite build --outDir dist-simulation
```

交互测试使用端口 5198 的独立内存接口夹具，加载实际页面组件，不连接业务设备。它与真实数据库集成测试分别证明页面行为和存储行为，不能替代部署后的认证 HTTP 联调。此次实现不自动部署，也不向现有业务设备灌入数据。
