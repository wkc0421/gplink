-- ============================================================
-- PostgreSQL 内存跳变诊断 — 单次执行版
-- 输出格式：section | metric | value（全部 UNION ALL）
-- 适用：TimescaleDB 2.x + PostgreSQL 12+
-- ============================================================

SELECT section, metric, value
FROM (

  -- §1  TimescaleDB 后台任务执行记录（最近 24h）
  --     跳变时间 ≈ last_start → 该 job 是根因
  --     job_stats 无 application_name，JOIN jobs 补充
  SELECT
    '§1 job_stats'                                           AS section,
    j.application_name || ' #' || s.job_id::text            AS metric,
    format('start=%s  dur=%s  fail=%s/%s',
           s.last_start::timestamptz(0),
           (s.last_finish - s.last_start)::interval(0),
           s.total_failures, s.total_runs)                   AS value
  FROM timescaledb_information.job_stats s
  JOIN timescaledb_information.jobs j USING (job_id)
  WHERE s.last_start > now() - INTERVAL '24 hours'

  UNION ALL

  -- §2  当前长时间运行的查询
  SELECT
    '§2 long_queries',
    'pid=' || pid::text || ' ' || state,
    format('dur=%s  wait=%s/%s  q=%s',
           (now() - query_start)::interval(0),
           coalesce(wait_event_type, ''),
           coalesce(wait_event, ''),
           left(query, 120))
  FROM pg_stat_activity
  WHERE state != 'idle'
    AND query_start < now() - INTERVAL '5 seconds'

  UNION ALL

  -- §3  临时文件累计（temp_bytes 大 → work_mem 不足）
  SELECT
    '§3 temp_files',
    datname,
    format('files=%s  size=%s', temp_files, pg_size_pretty(temp_bytes))
  FROM pg_stat_database
  WHERE datname = current_database()

  UNION ALL

  -- §4  死元组堆积（dead_pct>20% → autovacuum 滞后，集中清理时内存脉冲）
  SELECT
    '§4 dead_tuples',
    schemaname || '.' || relname,
    format('dead=%s  pct=%s%%  last_vacuum=%s',
           n_dead_tup,
           round(100.0 * n_dead_tup / nullif(n_live_tup + n_dead_tup, 0), 1),
           coalesce(last_autovacuum::text, 'never'))
  FROM pg_stat_user_tables
  WHERE n_dead_tup > 5000

  UNION ALL

  -- §5  内存相关参数
  SELECT
    '§5 pg_settings',
    name,
    setting || coalesce(' ' || unit, '') || '  -- ' || short_desc
  FROM pg_settings
  WHERE name IN (
    'shared_buffers', 'work_mem', 'maintenance_work_mem',
    'effective_cache_size', 'max_connections',
    'autovacuum_work_mem', 'wal_buffers'
  )

  UNION ALL

  -- §6  TimescaleDB 后台任务配置（评估 schedule 叠加风险）
  SELECT
    '§6 jobs_config',
    application_name || ' #' || job_id::text,
    format('schedule=%s  next=%s  config=%s',
           schedule_interval,
           next_start::timestamptz(0),
           config::text)
  FROM timescaledb_information.jobs

  UNION ALL

  -- §7  属性表 chunk 分布（最近 7 天）
  SELECT
    '§7 chunks',
    hypertable_name || '/' || chunk_name,
    format('[%s, %s)  compressed=%s',
           range_start::date,
           range_end::date,
           is_compressed)
  FROM timescaledb_information.chunks
  WHERE hypertable_schema = 'public'
    AND hypertable_name LIKE 'properties_%'
    AND range_end > now() - INTERVAL '7 days'

  UNION ALL

  -- §8  各 hypertable chunk 数量
  SELECT
    '§8 hypertables',
    hypertable_name,
    format('chunks=%s  schema=%s', num_chunks, hypertable_schema)
  FROM timescaledb_information.hypertables
  WHERE hypertable_schema = 'public'

  UNION ALL

  -- §9  shared_buffers 命中率（<99% → buffers 偏小）
  SELECT
    '§9 cache_hit',
    datname,
    format('hit_rate=%s%%  blks_hit=%s  blks_read=%s',
           round(100.0 * blks_hit / nullif(blks_hit + blks_read, 0), 2),
           blks_hit,
           blks_read)
  FROM pg_stat_database
  WHERE datname = current_database()

  UNION ALL

  -- §10  autovacuum 当前正在运行的会话
  SELECT
    '§10 autovacuum_now',
    'pid=' || pid::text,
    format('dur=%s  q=%s',
           (now() - query_start)::interval(0),
           left(query, 100))
  FROM pg_stat_activity
  WHERE application_name = 'autovacuum worker'
     OR query ILIKE 'autovacuum:%'

  UNION ALL

  -- §11  checkpoint 频率（checkpoints_req 多 → min_wal_size 太小）
  SELECT
    '§11 bgwriter',
    'checkpoint',
    format('timed=%s  req=%s  write_ms=%s  sync_ms=%s  data=%s',
           checkpoints_timed,
           checkpoints_req,
           checkpoint_write_time::bigint,
           checkpoint_sync_time::bigint,
           pg_size_pretty(buffers_checkpoint * 8192::bigint))
  FROM pg_stat_bgwriter

) t
ORDER BY section, metric;
