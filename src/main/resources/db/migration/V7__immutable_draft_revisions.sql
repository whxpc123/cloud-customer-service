-- 草稿版本仅标识不可变正文；任务 version 继续承担状态并发控制，两者不能混用。
ALTER TABLE ai.cs_draft_task ADD COLUMN draft_version bigint NOT NULL DEFAULT 0 CHECK (draft_version >= 0);
CREATE TABLE ai.cs_draft_revision (
    task_id uuid NOT NULL REFERENCES ai.cs_draft_task(task_id),
    draft_version bigint NOT NULL CHECK (draft_version > 0),
    basis_run_id uuid NOT NULL,
    body_json jsonb NOT NULL CHECK (jsonb_typeof(body_json) = 'object'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, draft_version)
);
CREATE TABLE ai.cs_draft_confirmation (
    confirmation_id uuid PRIMARY KEY,
    task_id uuid NOT NULL,
    draft_version bigint NOT NULL,
    confirmed_by bigint NOT NULL CHECK (confirmed_by > 0),
    confirmed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    scope varchar(32) NOT NULL DEFAULT 'DRAFT_CONTENT_ONLY' CHECK (scope = 'DRAFT_CONTENT_ONLY'),
    UNIQUE (task_id, draft_version),
    FOREIGN KEY (task_id, draft_version) REFERENCES ai.cs_draft_revision(task_id, draft_version)
);
-- 正文与确认回执都只追加，不原地改写。删除权限及保留策略留给单独授权的运维流程。
CREATE FUNCTION ai.cs_reject_draft_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Draft revisions and confirmation receipts cannot be updated in place';
END;
$$;
CREATE TRIGGER cs_draft_revision_no_update BEFORE UPDATE ON ai.cs_draft_revision
    FOR EACH ROW EXECUTE FUNCTION ai.cs_reject_draft_rewrite();
CREATE TRIGGER cs_draft_confirmation_no_update BEFORE UPDATE ON ai.cs_draft_confirmation
    FOR EACH ROW EXECUTE FUNCTION ai.cs_reject_draft_rewrite();
