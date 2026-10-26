WITH r AS (SELECT "timestamp", "numberValue" FROM public.properties_codex_counter_20260924_1059
 WHERE "deviceId"='codex_counter_20260924_d005' AND property='counter_kwh'
 AND "timestamp">='2026-09-14 00:00:00' AND "timestamp"<='2026-10-19 00:00:00'),
w AS (SELECT g AS bucket FROM generate_series(timestamp '2026-09-14', timestamp '2026-10-19', interval '1 week') g),
q AS (SELECT g AS bucket FROM generate_series(timestamp '2026-07-01',timestamp '2026-10-01',interval '3 months') g)
SELECT json_build_object('weekly',(SELECT json_agg(x ORDER BY x.time DESC) FROM
 (SELECT to_char(w.bucket,'YYYY-MM-DD HH24:MI:SS') AS time,sum(r."numberValue") AS value FROM w LEFT JOIN r ON date_trunc('week',r."timestamp")=w.bucket GROUP BY w.bucket) x),
 'quarterly',(SELECT json_agg(x ORDER BY x.time DESC) FROM
 (SELECT to_char(q.bucket,'YYYY-MM-DD HH24:MI:SS') AS time,sum(r."numberValue") AS value FROM q LEFT JOIN r ON date_trunc('quarter',r."timestamp")=q.bucket GROUP BY q.bucket) x));
WITH requested(kind,t) AS (VALUES
 ('week',timestamp '2026-09-14'),('week',timestamp '2026-09-21'),('week',timestamp '2026-09-28'),
 ('week',timestamp '2026-10-05'),('week',timestamp '2026-10-12'),('week',timestamp '2026-10-19'),
 ('quarter',timestamp '2026-09-14'),('quarter',timestamp '2026-10-01'),('quarter',timestamp '2026-10-19'))
SELECT json_agg(x ORDER BY x.kind,x.boundary_time) FROM (SELECT r.kind,r.t AS boundary_time,s.value_text,s.state,
 (s.boundary_time IS NOT NULL AND NOT EXISTS (SELECT 1 FROM public.counter_snapshot_config c WHERE c.product_id=s.product_id
 AND c.property_id=s.property_id AND c.pending_from<=s.boundary_time AND c.pending_to>=s.boundary_time)
 AND NOT EXISTS (SELECT 1 FROM public.counter_snapshot_task t WHERE t.product_id=s.product_id AND t.property_id=s.property_id
 AND t.kind='REBUILD' AND t.status<>'SUCCESS' AND t.source_version>s.source_version AND t.from_time<=s.boundary_time AND t.to_time>=s.boundary_time)
 AND NOT EXISTS (SELECT 1 FROM public.counter_snapshot_change d WHERE d.product_id=s.product_id AND d.property_id=s.property_id
 AND d.device_id=s.device_id AND d.dirty_from<=s.boundary_time AND (d.dirty_to IS NULL OR d.dirty_to>=s.boundary_time))) AS eligible
 FROM requested r LEFT JOIN public.counter_snapshot_day s ON s.product_id='codex_counter_20260924_1059'
 AND s.property_id='counter_kwh' AND s.device_id='codex_counter_20260924_d005' AND s.boundary_time=r.t) x;
