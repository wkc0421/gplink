-- PostgreSQL 调参脚本 - 32GB RAM（混合部署，同机有其他后台服务）
-- 适用于：GPLink / JetLinks Community 2.10 + TimescaleDB
-- 用法：以 superuser 身份连接数据库后执行，最后运行 SELECT pg_reload_conf();
--       标注"需重启"的参数需重启 PG 服务才能生效。
-- Docker 部署等效写法（推荐，启动自动生效）：
--   command: postgres
--     -c shared_buffers=4GB
--     -c effective_cache_size=16GB
--     -c work_mem=64MB
--     -c maintenance_work_mem=512MB
--     -c max_wal_size=4GB
--     -c checkpoint_completion_target=0.9
--     -c wal_buffers=64MB
--
-- 内存分配参考（32GB 混合部署）：
--   PostgreSQL shared_buffers : 4GB（PG 为主要服务时可调至 6GB）
--   JetLinks JVM 堆           : 4~6GB
--   OS + 文件页缓存            : 8GB
--   其他后台服务               : 10GB
--   剩余余量                   : 4GB

-- ─── 内存参数（需重启 PG）────────────────────────────────────────
ALTER SYSTEM SET shared_buffers            = '4GB';    -- 混合部署保守值；PG 为主要服务时可改为 6GB
ALTER SYSTEM SET effective_cache_size      = '16GB';   -- 查询规划器参考值，不占实际内存（OS 页缓存估算）
ALTER SYSTEM SET work_mem                  = '64MB';   -- 每个排序/Hash 操作上限；消除 CAGG 聚合临时文件
                                                       -- 诊断：86GB 临时文件，根因即 work_mem 过小
ALTER SYSTEM SET maintenance_work_mem      = '512MB';  -- VACUUM、CREATE INDEX 时使用

-- ─── WAL / Checkpoint（无需重启，pg_reload_conf 生效）────────────
-- max_wal_size 按消息速率分级选择（取消对应行注释，注释掉其余行）：
-- ALTER SYSTEM SET max_wal_size = '2GB';   -- ≤ 1000 msg/min
-- ALTER SYSTEM SET max_wal_size = '3GB';   -- 2000~3000 msg/min
ALTER SYSTEM SET max_wal_size              = '4GB';    -- ≥ 4000 msg/min（保守默认）

ALTER SYSTEM SET checkpoint_completion_target = 0.9;  -- checkpoint IO 分散到 90% 的间隔，削平 IO 峰值
ALTER SYSTEM SET wal_buffers               = '64MB';   -- 减少并发 COMMIT 的 WALWrite 锁等待（需重启）

-- ─── Autovacuum（无需重启）────────────────────────────────────────
ALTER SYSTEM SET autovacuum_max_workers    = 3;        -- 混合部署用 3 个，纯 PG 可设 4
-- 低速率（≤1000 msg/min）保持默认 2ms；高速率（≥2000 msg/min）改为 0ms
ALTER SYSTEM SET autovacuum_vacuum_cost_delay = '2ms';

SELECT pg_reload_conf();
-- ⚠ shared_buffers / wal_buffers 需重启 PG 后生效
