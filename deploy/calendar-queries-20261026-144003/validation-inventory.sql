SELECT coalesce(json_agg(s),'[]'::json) FROM (SELECT boundary_time,value_text,sample_time,state,source_version
 FROM public.counter_snapshot_day WHERE product_id='codex_counter_20260924_1059' AND property_id='counter_kwh'
 AND device_id='codex_counter_20260924_d005' AND boundary_time>='2026-09-14' AND boundary_time<='2026-10-26'
 ORDER BY boundary_time) s;
SELECT coalesce(json_agg(s),'[]'::json) FROM (SELECT "timestamp",value,"numberValue" FROM public.properties_codex_counter_20260924_1059
 WHERE "deviceId"='codex_counter_20260924_d005' AND property='counter_kwh'
 AND "timestamp">='2026-09-01' AND "timestamp"<='2026-10-26' ORDER BY "timestamp" LIMIT 40) s;
SELECT json_build_object('dirty', (SELECT count(*) FROM public.counter_snapshot_change
 WHERE product_id='codex_counter_20260924_1059' AND property_id='counter_kwh' AND device_id='codex_counter_20260924_d005'),
 'unfinished_rebuild',(SELECT count(*) FROM public.counter_snapshot_task WHERE product_id='codex_counter_20260924_1059'
 AND property_id='counter_kwh' AND kind='REBUILD' AND status<>'SUCCESS'));
