# 累计表码快照：本轮验证与性能状态

## 已通过的静态与单元验证

- 后端：`mvn.cmd -pl jetlinks-manager/saas-manager -am '-Dtest=CounterSnapshotTimeTest,CounterSnapshotRefreshProofTest,RawRetentionStartupReconcilerTest,AggregationWindowTest,AggregationQueryTest,DeviceProductHistoryStatisticsControllerTest,ApiTokenPermissionCatalogTest,ApiTokenHistoryStatisticsPathWebFilterTest,ApiTokenScopeServiceTest,ApiTokenIntervalPathWebFilterTest,LegacyDeviceDataCacheTest,LegacyDeviceIntervalQueryTest,LegacyCounterIntervalRoutingTest' '-Dsurefire.failIfNoSpecifiedTests=false' -q test`，13 个测试类共 65 项，0 失败、0 错误、0 跳过。
- 独立启动模块：`mvn.cmd -pl jetlinks-standalone -am -DskipTests -q compile`，退出码 0。
- 前端：`pnpm.cmd build` 成功；`pnpm.cmd exec playwright test tests/e2e/history-statistics-utils.spec.ts`，4 项通过。
- 工作区差异检查：`git diff --check` 通过（仅 Windows CRLF 提示）。

以上证明编译、纯逻辑和局部接口测试通过，不等同于数据库运行或端到端授权验证。前端全量 `pnpm.cmd exec vue-tsc --noEmit --pretty false` 退出码 1，仓库及依赖中存在大量全局类型错误；本轮没有将其记为通过，也没有为该功能改动无关文件。

## 数据库集成阻塞

目标版本为 PostgreSQL 16 / TimescaleDB 2.29.1。本机 PostgreSQL 16 服务可用，但已安装的 TimescaleDB 扩展默认版本是 2.26.1，且 Docker/Podman 不可用。`ContinuousAggregateIntegrationTest` 执行结果为 1 项跳过，因此目标版本集成未通过验收。没有连接业务数据库试建或删除对象。

在可用的隔离目标库上，还需实测：首次初始化、原始表已存在时补建、已有 3 年保留任务改为 5 年、重复启动、结构不兼容回退、`UPDATE ... RETURNING` 与原始 UPSERT/变更标记共事务、CAGG 刷新证明和 5 小时/5 天/4 个月补报、任务取消/崩溃恢复/多实例竞争、普通用户全局资源权限与 API Token 产品/设备范围。源码中的事务及租约设计不能替代这些运行验证。

## 性能报告状态

固定场景为 1,000 台设备 × 每台 50 属性 × 每 5 分钟采样 × 5 年，理论原始记录约 262.8 亿条。目标库及隔离规模数据集未具备，故本轮没有可信的 SQL 数、执行计划、读取量、P50/P95、快照命中率或提升倍数，不填写估计值冒充实测。

目标环境就绪后应关闭接口结果缓存，在“快照齐全、部分缺失、全部缺失”三种路径，分别对 50 台设备上月每日用能采集 `EXPLAIN (ANALYZE, BUFFERS)`、SQL 数、P50/P95、CAGG 回退和原始校正比例；再增加冷/热缓存、并发查询、补录并行和补报积压。快照齐全的约 1,500 个每日区间，应证明查询不访问原始属性表或 CAGG。完整 5 年规模需单独隔离压测，不能用缩小数据集替代。

## 上线前尚需确认

- CAGG 刷新已对同一物理视图、相同窗口的多个配置采集项复用一次刷新，并用刷新前后源版本限制证明范围；不同待处理窗口的全局合并及跨实例重复刷新尚未完成性能验收。
- 权限口径已确认：普通登录用户沿用现有全局资源权限，不额外按产品或设备 `orgId` 做行级限制；API Token 仍按其产品/设备名单校验。端到端授权行为尚待目标环境实测。
- GpLink2 的 interval 响应省略空表码字段，以兼容旧 gpSaaS 的字段存在性判断；内部空值与未来边界语义不变。此次未执行端到端结算验证。
- 运行期数据库集成、上述真实性能数据及前端全局类型检查仍未通过，故不建议直接生产启用配置。

本轮未部署、未提交，也未自动删除或重建既有数据库对象。
