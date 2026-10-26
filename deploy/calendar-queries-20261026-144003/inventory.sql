SELECT json_build_object('database',current_database(),'server',inet_server_addr(),'version',version(),
 'extensions',(SELECT json_agg(json_build_object('name',extname,'version',extversion)) FROM pg_extension),
 'stat_statements',to_regclass('public.pg_stat_statements'),
 'snapshot_config',to_regclass('public.counter_snapshot_config'),
 'snapshot_day',to_regclass('public.counter_snapshot_day'));
SELECT coalesce(json_agg(c),'[]'::json) FROM (SELECT product_id,property_id,enabled,raw_table,version,source_version,published_version,
 start_time,coverage_from,coverage_to,pending_from,pending_to,status FROM public.counter_snapshot_config ORDER BY enabled DESC,product_id,property_id LIMIT 20) c;
SELECT coalesce(json_agg(s),'[]'::json) FROM (SELECT product_id,property_id,device_id,count(*) AS snapshots,min(boundary_time) AS first_boundary,
 max(boundary_time) AS last_boundary,count(*) FILTER (WHERE state='VALID') AS valid FROM public.counter_snapshot_day
 GROUP BY product_id,property_id,device_id ORDER BY count(*) DESC LIMIT 12) s;
