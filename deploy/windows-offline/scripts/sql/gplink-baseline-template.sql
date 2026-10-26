\set ON_ERROR_STOP on

-- GPLink2 offline deployment baseline initialization.
-- This template is combined with a read-only export of the explicitly retained
-- network rows. No protocol rows or protocol JARs are imported by this bundle.
-- The finalized SQL lives in packages and is hashed
-- by the package manifest.

BEGIN;
SET LOCAL lock_timeout = '30s';
SET LOCAL statement_timeout = '20min';
SET LOCAL search_path TO :"database_schema", public;

-- Defense in depth: the installer checks this marker before invoking psql,
-- and the SQL itself also refuses to run after any baseline was recorded.
DO $gplink$
DECLARE
    already_applied boolean := false;
BEGIN
    IF to_regclass(format('%I.%I', current_schema(), 'gplink_deployment_baseline')) IS NOT NULL THEN
        EXECUTE format(
            'SELECT EXISTS (SELECT 1 FROM %I.gplink_deployment_baseline)',
            current_schema()
        ) INTO already_applied;
    END IF;
    IF already_applied THEN
        RAISE EXCEPTION 'A GPLink2 deployment baseline has already been applied; refusing to clear data again.';
    END IF;
END
$gplink$;

DO $gplink$
DECLARE
    required_table text;
BEGIN
    FOREACH required_table IN ARRAY ARRAY[
        'dev_product',
        'dev_device_instance',
        'device_gateway',
        'dev_protocol',
        'network_config',
        's_file'
    ] LOOP
        IF to_regclass(format('%I.%I', current_schema(), required_table)) IS NULL THEN
            RAISE EXCEPTION 'Required GPLink2 table is missing: %.%', current_schema(), required_table;
        END IF;
    END LOOP;
END
$gplink$;

LOCK TABLE dev_product, dev_device_instance, device_gateway, dev_protocol, network_config, s_file
    IN SHARE ROW EXCLUSIVE MODE;

-- Remove all per-product time-series tables. These prefixes are reserved by
-- DeviceTimeSeriesMetric and the legacy row-mode property implementation.
DO $gplink$
DECLARE
    dynamic_table record;
BEGIN
    FOR dynamic_table IN
        SELECT schemaname, tablename
        FROM pg_tables
        WHERE schemaname = current_schema()
          AND tablename ~ '^(properties_|event_|device_log_|device_properties_)'
    LOOP
        EXECUTE format('DROP TABLE IF EXISTS %I.%I CASCADE', dynamic_table.schemaname, dynamic_table.tablename);
    END LOOP;
END
$gplink$;

-- These tables contain device/product/gateway derived configuration, runtime
-- metrics, alarms, scheduled work, or rule execution state. They are emptied
-- before deleting the owning product and device rows.
DO $gplink$
DECLARE
    related_table text;
BEGIN
    FOREACH related_table IN ARRAY ARRAY[
        'alarm_handle_history',
        'alarm_history',
        'alarm_record',
        's_alarm_rule_bind',
        'alarm_config',
        'numeric_alarm_rule',
        'rule_engine_execute_event',
        'rule_engine_execute_log',
        'rule_task_snapshot',
        'rule_scene',
        'rule_instance',
        'dev_scheduled_task_log',
        'dev_scheduled_task',
        'dev_device_tags',
        'dev_metadata_mapping',
        'dev_transparent_codec',
        'device_change_property_config',
        'device_gateway_monitor',
        'device_metrics',
        'device_session_metric',
        'thing_property_metric',
        'redis_latest'
    ] LOOP
        IF to_regclass(format('%I.%I', current_schema(), related_table)) IS NOT NULL THEN
            EXECUTE format('DELETE FROM %I.%I', current_schema(), related_table);
        END IF;
    END LOOP;
END
$gplink$;

DELETE FROM dev_device_instance;
DELETE FROM dev_product;
DELETE FROM device_gateway;

-- Clear all protocol registrations. This bundle does not import protocol JARs.
DELETE FROM dev_protocol;

-- Remove protocol file-manager records created by older GPLink2 baselines.
DELETE FROM s_file
WHERE id IN ('gplink-baseline-mbrtu', 'gplink-baseline-khmqtt');

DELETE FROM network_config
WHERE id NOT IN ('2068932624811515904', '2068926208973430784');

-- The only retained network components are the two approved MQTT clients.
INSERT INTO network_config (id, name, description, type, state, creator_id, create_time, creator_name, modifier_id, modify_time, modifier_name, configuration, share_cluster, cluster) VALUES ('2068926208973430784', 'KHMQTT客户端', '', 'MQTT_CLIENT', 'enabled', '21232f297a57a5a743894a0e4a801fc3', '1782105421659', 'Administrator', '21232f297a57a5a743894a0e4a801fc3', '1783673838697', 'Administrator', '{"secure":false,"maxMessageSize":81920,"topicPrefix":"","password":"kh_mqtt","username":"kh_mqtt","clientId":"kh_mqtt_01","remotePort":2883,"remoteHost":"127.0.0.1"}', 'True', NULL) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, type = EXCLUDED.type, state = EXCLUDED.state, creator_id = EXCLUDED.creator_id, create_time = EXCLUDED.create_time, creator_name = EXCLUDED.creator_name, modifier_id = EXCLUDED.modifier_id, modify_time = EXCLUDED.modify_time, modifier_name = EXCLUDED.modifier_name, configuration = EXCLUDED.configuration, share_cluster = EXCLUDED.share_cluster, cluster = EXCLUDED.cluster;
INSERT INTO network_config (id, name, description, type, state, creator_id, create_time, creator_name, modifier_id, modify_time, modifier_name, configuration, share_cluster, cluster) VALUES ('2068932624811515904', 'GP_MQTT客户端', '', 'MQTT_CLIENT', 'enabled', '21232f297a57a5a743894a0e4a801fc3', '1782106951320', 'Administrator', '21232f297a57a5a743894a0e4a801fc3', '1787796208537', 'Administrator', '{"secure":false,"maxMessageSize":81920,"topicPrefix":"","password":"gplink_mqtt","username":"gplink_mqtt","clientId":"gplink_mqtt","remotePort":2883,"remoteHost":"127.0.0.1"}', 'True', NULL) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, type = EXCLUDED.type, state = EXCLUDED.state, creator_id = EXCLUDED.creator_id, create_time = EXCLUDED.create_time, creator_name = EXCLUDED.creator_name, modifier_id = EXCLUDED.modifier_id, modify_time = EXCLUDED.modify_time, modifier_name = EXCLUDED.modifier_name, configuration = EXCLUDED.configuration, share_cluster = EXCLUDED.share_cluster, cluster = EXCLUDED.cluster;

DO $gplink$
DECLARE
    actual_ids text[];
BEGIN
    IF EXISTS (SELECT 1 FROM dev_device_instance) THEN
        RAISE EXCEPTION 'Baseline verification failed: devices remain.';
    END IF;
    IF EXISTS (SELECT 1 FROM dev_product) THEN
        RAISE EXCEPTION 'Baseline verification failed: products remain.';
    END IF;
    IF EXISTS (SELECT 1 FROM device_gateway) THEN
        RAISE EXCEPTION 'Baseline verification failed: access gateways remain.';
    END IF;

    SELECT array_agg(id ORDER BY id) INTO actual_ids FROM dev_protocol;
    IF actual_ids IS NOT NULL THEN
        RAISE EXCEPTION 'Baseline verification failed: protocol IDs are still present: %.', actual_ids;
    END IF;

    SELECT array_agg(id ORDER BY id) INTO actual_ids FROM network_config;
    IF actual_ids IS DISTINCT FROM ARRAY['2068926208973430784', '2068932624811515904']::text[] THEN
        RAISE EXCEPTION 'Baseline verification failed: network component IDs are %.', actual_ids;
    END IF;
END
$gplink$;

CREATE TABLE IF NOT EXISTS gplink_deployment_baseline (
    bundle_id varchar(128) PRIMARY KEY,
    sql_sha256 char(64) NOT NULL,
    applied_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO gplink_deployment_baseline(bundle_id, sql_sha256, applied_at)
VALUES (:'bundle_id', :'baseline_sha256', now())
ON CONFLICT (bundle_id) DO UPDATE
SET sql_sha256 = EXCLUDED.sql_sha256,
    applied_at = EXCLUDED.applied_at;

COMMIT;
