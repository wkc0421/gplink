# 周／季度查询：测试环境部署与验收

2026-10-26，目标 `192.168.5.19`。前后端均已更新，`GpLink2` 最终状态为 `Running`，前端地址为 <http://192.168.5.19:18088>。临时诊断配置已恢复，四类核心接口在正常启动参数下再次通过验证。

## 发布包与回滚备份

| 项目 | 发布结果 |
| --- | --- |
| 后端 | [application.jar](application.jar)，274729289 字节；远端 `D:\GpLink2\application.jar` 与本地 SHA-256 一致 |
| 后端 SHA-256 | `8F6FABCD8829CBD2F91BDF6339BDBFE9121E4445B207975D793A968F78802B1C` |
| 后端备份 | `D:\GpLink2\backup\application-20261026-145138.jar` |
| 前端 | [frontend.zip](frontend.zip)，26421345 字节；831 文件、49857047 字节，暂存树与本地清单逐文件哈希一致 |
| 前端 ZIP SHA-256 | `7309062624596C3BD1D16A62C5AAAFBAC8CC097651CDEA1761F0CF83F3752352` |
| 前端目录 | `D:\tool\nginx-1.26.3\html\gpLink2` |
| 前端备份 | `D:\tool\nginx-1.26.3\html\gpLink2.backup-calendar-20261026-144003` |
| 在线首页 SHA-256 | `8DF9C522C59F39A10B4379A4611F3DCCB66CAA0FAC3745BCA2B084A586FF2594` |

前端在线 JS 也通过独立 HTTP 下载与本地哈希比对，包含周／季度区间选项及季度聚合选项。详情见 [在线资源验证](frontend-live-bundle.json)、[前端清单](frontend-manifest.json)、[发布清单](release-manifest.json)、[校验文件](SHA256SUMS.txt)。构建基于当前工作区（包括已有未提交修改）；[Git 状态](workspace-status.txt) 与 [HEAD](workspace-head.txt) 已记录。

## 实际接口结果

复用既有测试产品 `codex_counter_20260924_1059`、设备 `codex_counter_20260924_d005`、采集项 `counter_kwh`。该采集项的历史统计配置已启用，查询前配置 `source_version=15`、`pending_from/pending_to=null`。验证使用现有数据，没有新增产品、设备或业务历史写入。

核心范围为北京时间 `2026-09-14 00:00:00` 至 `2026-10-19 00:00:00`。

| 场景 | HTTP | 核对结果 |
| --- | --- | --- |
| 聚合 `interval=1w` | 200 | 6 个自然周桶，倒序；SUM 总和 26，空桶为 0 |
| 聚合 `interval=1q` | 200 | 2 个自然季度桶，桶起点 2026-07-01、2026-10-01；SUM 总和 26 |
| 区间 `week/history` | 200 | 5 段，内部周一零点切分；表码连续衔接，起止读数均为 8 |
| 区间 `quarter/history` | 200 | 2 段，2026-10-01 零点切分；首尾保持请求值，读数均为 8 |
| 重复季度区间请求 | 200 | 结果相同；命中历史缓存，未再解析快照边界 |
| 快照齐全的周区间（09-28 至 10-19） | 200 | 3 段，4 个边界全部命中日快照 |
| 非零点首尾周区间 | 200 | 6 段，保留 09-20 12:30 至 10-21 18:45 的首尾 |
| 非零点首尾季度区间 | 200 | 2 段，保留同一请求首尾 |

独立只读 SQL 在原始表查到 09-19 的 4 条样本 `5、6、7、8`，SUM 为 26。SQL 使用 PostgreSQL `date_trunc('week'/'quarter')` 独立生成期望桶，对照 API 的时间与结果；没有用 API 自身生成预期值。见 [期望 SQL](expected.sql)、[数据库期望结果](expected-results.jsonl)、[8 项验证汇总](api-validation-summary.json) 和各项 `*-http.txt` / `*-response.json`。

## 快照优先读取的运行证据

通过应用已有指标 `gplink.counter.boundary.resolved` 记录查询来源。测试会话仅在 `127.0.0.1:18849` 开放诊断入口，临时启用指标注册，将现有 StepMeterRegistry 的采样窗口设为 5 秒，并在隔离的完成窗口读取计数。这里是窗口计数，不是累计计数差。

| 请求 | day_snapshot | hour_snapshot | cagg_daily | 结论 |
| --- | ---: | ---: | ---: | --- |
| 周区间，6 个边界 | 5 | 0 | 1 | 5 个符合条件的边界优先读日快照；其余 1 个按规则回退 |
| 季度区间，3 个边界 | 3 | 0 | 0 | 全部边界读日快照，包含已知无值的起点 |
| 相同季度区间再次请求 | 0 | 0 | 0 | 缓存命中，未重新读取边界 |
| 快照齐全的周区间，4 个边界 | 4 | 0 | 0 | 日快照齐全时全部优先命中日快照 |

周区间 `2026-09-21 00:00` 的日快照版本较旧，存在更新版本的未成功重算覆盖，因此按现有规则不可用；独立 SQL 的 `eligible=false` 与实际仅一次 `cagg_daily` 回退吻合。其他符合条件的边界直接命中日快照，证明优先路径与重算保护均生效。CAGG 边界回退仍会按既有实现点读原表的精确文本表码，因此这些来源计数描述的是边界解析路径，不是全部 SQL 条数。

## 最终运行状态

- 临时启动参数、指标开关及采样周期均已撤销，恢复原参数 `-jar D:\GpLink2\application.jar`；诊断端口 18849 已确认关闭。
- `GpLink2` 为 `Running`，Nginx 进程正常；后端 JAR、前端首页和在线查询 JS 哈希均已读回确认。
- 远端暂存归档 `D:\tool\nginx-1.26.3\html\.gpLink2-calendar-20261026-144003.zip` 已保留：自动审批拒绝了清理命令，仅提供 `blocked by policy` 原因。前后端回滚备份也已保留。
- 正常启动参数恢复后，周／季度聚合、周／季度区间 4 个接口再次返回 HTTP 200，桶数／段数及数值一致。见 [最终接口复查](final-api-verification.json)、[最终服务状态](final-runtime-state.json)、[最终 JAR 比较](backend-final-check.txt)。
- 查询相关错误模式检查为 0 匹配，见 [日志检查](query-log-check.txt)。服务日志中仍可见部署前已有的 MQTT 1883 连接拒绝及 TCP `direct buffer` 告警，本次未修改这些设备接入问题。
- 实际环境为 PostgreSQL **16.13** / TimescaleDB **2.26.1**；这是当前测试服运行验证，不是 TimescaleDB 2.29.1 验收或规模性能测试。

流程使用本机技能：[更新测试环境](C:/Users/wkc/.codex/skills/update-gplink2-test-env/SKILL.md)、[受保护 API 测试](C:/Users/wkc/.codex/skills/test-gplink2-test-api/SKILL.md)、[测试环境日志](C:/Users/wkc/.codex/skills/view-gplink2-test-env-logs/SKILL.md)。
