-- 第26章：操作、授权消费、申请创建和任务关闭位于同一 PostgreSQL 事务。
CREATE TABLE ai.cs_submit_operation (
    operation_id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES ai.cs_draft_task(task_id),
    draft_version bigint NOT NULL,
    action varchar(64) NOT NULL DEFAULT 'CREATE_AFTER_SALE_APPLICATION'
        CHECK (action = 'CREATE_AFTER_SALE_APPLICATION'),
    status varchar(24) NOT NULL DEFAULT 'PENDING_APPROVAL'
        CHECK (status IN ('PENDING_APPROVAL','APPROVED','REJECTED','SUCCEEDED')),
    -- 捕获准备操作时的接待版本；交接后即使回到 BOT，也不能复用旧的首次执行授权。
    reception_version bigint NOT NULL CHECK (reception_version >= 0),
    decided_by bigint,
    decided_at timestamptz,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(task_id,draft_version),
    UNIQUE(operation_id,task_id,draft_version),
    FOREIGN KEY(task_id,draft_version) REFERENCES ai.cs_draft_confirmation(task_id,draft_version),
    CHECK ((status='PENDING_APPROVAL' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status IN ('APPROVED','REJECTED','SUCCEEDED') AND decided_by IS NOT NULL AND decided_by>0 AND decided_at IS NOT NULL))
);
CREATE TABLE ai.cs_after_sale_application (
    application_id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    task_id uuid NOT NULL UNIQUE,
    draft_version bigint NOT NULL,
    tenant_id varchar(64) NOT NULL,
    user_id bigint NOT NULL CHECK(user_id>0),
    order_no varchar(32) NOT NULL,
    body_snapshot jsonb NOT NULL CHECK(jsonb_typeof(body_snapshot)='object'),
    status varchar(32) NOT NULL DEFAULT 'PENDING_REVIEW' CHECK(status='PENDING_REVIEW'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(operation_id,task_id,draft_version)
        REFERENCES ai.cs_submit_operation(operation_id,task_id,draft_version)
);
-- 目标和接待范围不可原地换绑；状态只允许待决策→批准/拒绝→成功。
CREATE FUNCTION ai.cs_reject_submit_target_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.operation_id,NEW.task_id,NEW.draft_version,NEW.action,NEW.reception_version)
        IS DISTINCT FROM ROW(OLD.operation_id,OLD.task_id,OLD.draft_version,OLD.action,OLD.reception_version) THEN
        RAISE EXCEPTION 'Submission operation target is immutable';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
        (OLD.status='PENDING_APPROVAL' AND NEW.status IN ('APPROVED','REJECTED')) OR
        (OLD.status='APPROVED' AND NEW.status='SUCCEEDED')) THEN
        RAISE EXCEPTION 'Invalid submission operation transition';
    END IF;
    IF OLD.status <> 'PENDING_APPROVAL' AND ROW(NEW.decided_by,NEW.decided_at) IS DISTINCT FROM ROW(OLD.decided_by,OLD.decided_at) THEN
        RAISE EXCEPTION 'Submission decision is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER cs_submit_target_immutable BEFORE UPDATE ON ai.cs_submit_operation
    FOR EACH ROW EXECUTE FUNCTION ai.cs_reject_submit_target_change();
-- 本章保存的是不可改写的受理快照；未来审核状态变化应设计独立的事件/处理记录。
CREATE TRIGGER cs_application_no_update BEFORE UPDATE ON ai.cs_after_sale_application
    FOR EACH ROW EXECUTE FUNCTION ai.cs_reject_draft_rewrite();
