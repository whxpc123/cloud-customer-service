-- 单独接收库的只读快照；两端采集时间都保留，不声称跨库原子观察。
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT jsonb_build_object(
 'database',current_database(),'observedAt',clock_timestamp(),
 'migrations',(SELECT jsonb_agg(jsonb_build_object('version',version,'checksum',checksum,'success',success) ORDER BY installed_rank) FROM flyway_schema_history),
 'inbox',(SELECT coalesce(jsonb_agg(to_jsonb(i)),'[]') FROM rx_inbox i WHERE event_id=:'event_id'::uuid),
 'applications',(SELECT coalesce(jsonb_agg(to_jsonb(a)),'[]') FROM rx_after_sale_application a WHERE source_event_id=:'event_id'::uuid OR source_operation_id=:'operation_id'::uuid)
);
COMMIT;
