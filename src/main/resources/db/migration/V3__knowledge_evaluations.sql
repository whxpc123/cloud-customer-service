-- 基础评测保存用户定义的题集快照和每题真实结果；不生成虚构的模型评分。
CREATE TABLE ai.knowledge_evaluation_runs (
    id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    name text NOT NULL,
    status text NOT NULL,
    cases jsonb NOT NULL,
    results jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);
CREATE INDEX knowledge_evaluation_tenant_time_idx ON ai.knowledge_evaluation_runs(tenant_id, created_at DESC);
