-- 第29章：只补记可验证的历史送达事实，保留原投递次数、错误与身份，不重建业务。
ALTER TABLE ai.cs_outbox
ADD COLUMN reconcile_version bigint NOT NULL DEFAULT 0 CHECK(reconcile_version >= 0);
ALTER TABLE ai.cs_outbox ADD UNIQUE(event_id,tenant_id);

CREATE TABLE ai.cs_outbox_reconciliation (
    check_id uuid PRIMARY KEY,
    event_id uuid NOT NULL,
    tenant_id varchar(64) NOT NULL,
    requested_by bigint NOT NULL CHECK(requested_by > 0),
    expected_version bigint NOT NULL CHECK(expected_version >= 0),
    status varchar(16) NOT NULL DEFAULT 'STARTED' CHECK(status IN ('STARTED','RECORDED','STALE')),
    finding varchar(40) CHECK(finding IN ('PERSISTED','NOT_OBSERVED','PAYLOAD_CONFLICT','RECEIVER_INCONSISTENT','QUERY_UNAVAILABLE','INVALID_RESPONSE')),
    observation_json jsonb,
    state_before varchar(16) NOT NULL CHECK(state_before='REVIEW'),
    state_after varchar(16) CHECK(state_after IN ('PENDING','SENDING','DELIVERED','REVIEW')),
    repaired boolean NOT NULL DEFAULT FALSE,
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    FOREIGN KEY(event_id,tenant_id) REFERENCES ai.cs_outbox(event_id,tenant_id),
    -- 完成记录必须携带观察和前后状态；STARTED 只代表尚未确认完成。
    CHECK((status='STARTED' AND finding IS NULL AND observation_json IS NULL AND state_after IS NULL AND completed_at IS NULL AND NOT repaired)
       OR (status IN ('RECORDED','STALE') AND finding IS NOT NULL AND observation_json IS NOT NULL
           AND jsonb_typeof(observation_json)='object' AND state_after IS NOT NULL AND completed_at IS NOT NULL)),
    CHECK(NOT repaired OR (status='RECORDED' AND finding='PERSISTED' AND state_after='DELIVERED'))
);
CREATE INDEX cs_reconciliation_event_idx ON ai.cs_outbox_reconciliation(tenant_id,event_id,started_at DESC,check_id);
CREATE INDEX cs_reconciliation_started_idx ON ai.cs_outbox_reconciliation(tenant_id,started_at) WHERE status='STARTED';
CREATE INDEX cs_outbox_review_idx ON ai.cs_outbox(tenant_id,created_at,event_id) WHERE status='REVIEW';

-- 完成审计不能被重复回调覆盖；一次操作只完成自己的 STARTED 记录。
CREATE FUNCTION ai.cs_reconciliation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status<>'STARTED' OR ROW(NEW.check_id,NEW.event_id,NEW.tenant_id,NEW.requested_by,NEW.expected_version,NEW.state_before,NEW.started_at)
       IS DISTINCT FROM ROW(OLD.check_id,OLD.event_id,OLD.tenant_id,OLD.requested_by,OLD.expected_version,OLD.state_before,OLD.started_at) THEN
        RAISE EXCEPTION 'Reconciliation audit identity or completed observation is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER cs_reconciliation_no_rewrite BEFORE UPDATE ON ai.cs_outbox_reconciliation
FOR EACH ROW EXECUTE FUNCTION ai.cs_reconciliation_immutable();
