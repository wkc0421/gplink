-- ============================================================
-- PostgreSQL CPU 跳变诊断脚本
-- 运行方式：在 pgAdmin 或 psql 中执行，观察各部分输出
-- ============================================================

-- ────────────────────────────────────────────────────────────
-- 1. 当前活跃查询（CPU 高时跑，看在执行什么）
-- ────────────────────────────────────────────────────────────
SELECT
    pid,
    state,
    wait_event_type,
    wait_event,
    now() - query_start        AS duration,
    left(query, 120)           AS query
FROM pg_stat_activity
WHERE state != 'idle'
  AND pid != pg_backend_pid()
ORDER BY query_start ASC;

-- ────────────────────────────────────────────────────────────
-- 2. 锁等待（是否有查询被阻塞）
-- ────────────────────────────────────────────────────────────
SELECT
    blocked.pid                AS blocked_pid,
    blocked.query              AS blocked_query,
    blocking.pid               AS blocking_pid,
    blocking.query             AS blocking_query,
    now() - blocked.query_start AS wait_duration
FROM pg_stat_activity AS blocked
JOIN pg_stat_activity AS blocking
    ON blocking.pid = ANY(pg_blocking_pids(blocked.pid))
WHERE cardinality(pg_blocking_pids(blocked.pid)) > 0;

-- ────────────────────────────────────────────────────────────
-- 3. 写入最频繁的表（找到 CPU 压力来源）
-- ────────────────────────────────────────────────────────────
SELECT
    relname,
    n_tup_ins                  AS inserts,
    n_tup_upd                  AS updates,
    n_tup_del                  AS deletes,
    n_live_tup,
    n_dead_tup,
    last_autovacuum,
    last_autoanalyze
FROM pg_stat_user_tables
ORDER BY n_tup_ins DESC
LIMIT 20;

-- ────────────────────────────────────────────────────────────
-- 4. autovacuum 是否正在运行（周期性 CPU 尖峰常见原因）
-- ────────────────────────────────────────────────────────────
SELECT
    pid,
    now() - query_start        AS duration,
    left(query, 100)           AS query
FROM pg_stat_activity
WHERE query LIKE 'autovacuum:%'
   OR query LIKE '%VACUUM%';

-- ────────────────────────────────────────────────────────────
-- 5. 死元组最多的表（需要 VACUUM 的候选）
-- ────────────────────────────────────────────────────────────
SELECT
    relname,
    n_dead_tup,
    n_live_tup,
    round(n_dead_tup::numeric / NULLIF(n_live_tup + n_dead_tup, 0) * 100, 1) AS dead_ratio_pct,
    last_autovacuum,
    last_autoanalyze
FROM pg_stat_user_tables
WHERE n_dead_tup > 1000
ORDER BY n_dead_tup DESC
LIMIT 20;

-- ────────────────────────────────────────────────────────────
-- 6. TimescaleDB chunk 信息（分区是否过多或过旧）
-- ────────────────────────────────────────────────────────────
SELECT
    hypertable_name,
    chunk_name,
    range_start,
    range_end,
    is_compressed
FROM timescaledb_information.chunks
ORDER BY hypertable_name, range_start DESC
LIMIT 40;

-- ────────────────────────────────────────────────────────────
-- 7. 当前各表 IO 统计（堆/索引读取次数）
-- ────────────────────────────────────────────────────────────
SELECT
    relname,
    heap_blks_read,
    heap_blks_hit,
    idx_blks_read,
    idx_blks_hit,
    round(heap_blks_hit::numeric / NULLIF(heap_blks_read + heap_blks_hit, 0) * 100, 1) AS heap_hit_pct
FROM pg_statio_user_tables
ORDER BY heap_blks_read DESC
LIMIT 20;

-- ────────────────────────────────────────────────────────────
-- 8. WAL 写入速率（INSERT 密集时 WAL 是主要 CPU 消耗）
-- ────────────────────────────────────────────────────────────
SELECT
    pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')) AS total_wal_written,
    pg_walfile_name(pg_current_wal_lsn())                        AS current_wal_file;

-- ────────────────────────────────────────────────────────────
-- 9. 索引使用率（未命中索引 = 全表扫描 = CPU 高）
-- ────────────────────────────────────────────────────────────
SELECT
    relname,
    seq_scan,
    seq_tup_read,
    idx_scan,
    idx_tup_fetch,
    CASE WHEN seq_scan + idx_scan = 0 THEN NULL
         ELSE round(idx_scan::numeric / (seq_scan + idx_scan) * 100, 1)
    END AS idx_usage_pct
FROM pg_stat_user_tables
WHERE seq_scan > 100
ORDER BY seq_scan DESC
LIMIT 20;
