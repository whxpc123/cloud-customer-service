-- 每份证据在本库同一个只读快照中采集；不是两个库的分布式快照。
-- psql -v task_id=UUID -v event_id=UUID -f sender.sql；:'变量' 按 SQL 字面量引用。
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT jsonb_build_object(
 'database', current_database(), 'observedAt', clock_timestamp(),
 'migrations', (SELECT jsonb_agg(jsonb_build_object('version',version,'checksum',checksum,'success',success) ORDER BY installed_rank) FROM public.flyway_schema_history),
 'tasks', (SELECT coalesce(jsonb_agg(jsonb_build_object('task_id',task_id,'tenant_id',tenant_id,'user_id',user_id,'order_no',order_no,'status',status,'draft_version',draft_version,'run_id',run_id)), '[]') FROM ai.cs_draft_task WHERE task_id=:'task_id'::uuid),
 'revisions', (SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY draft_version),'[]') FROM ai.cs_draft_revision r WHERE task_id=:'task_id'::uuid),
 'confirmations', (SELECT coalesce(jsonb_agg(to_jsonb(c)),'[]') FROM ai.cs_draft_confirmation c WHERE task_id=:'task_id'::uuid),
 'operations', (SELECT coalesce(jsonb_agg(to_jsonb(o)),'[]') FROM ai.cs_submit_operation o WHERE task_id=:'task_id'::uuid),
 'applications', (SELECT coalesce(jsonb_agg(to_jsonb(a)),'[]') FROM ai.cs_after_sale_application a WHERE task_id=:'task_id'::uuid),
 'outbox', (SELECT coalesce(jsonb_agg(to_jsonb(e)),'[]') FROM ai.cs_outbox e WHERE application_id IN (SELECT application_id FROM ai.cs_after_sale_application WHERE task_id=:'task_id'::uuid)),
 'checks', (SELECT coalesce(jsonb_agg(to_jsonb(c)),'[]') FROM ai.cs_outbox_reconciliation c WHERE event_id=:'event_id'::uuid)
);
COMMIT;
