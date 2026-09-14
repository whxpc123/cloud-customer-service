-- 原件与向量在同一发布事务中写入；旧知识没有原件，不伪造上传时间或原文。
CREATE TABLE ai.knowledge_originals (
    tenant_id text NOT NULL,
    source_id text NOT NULL,
    file_name text NOT NULL,
    file_type text NOT NULL,
    file_hash text NOT NULL,
    file_bytes bytea NOT NULL,
    extracted_text text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, source_id)
);
-- 管理页按来源分组；不修改既有 V1 文件和向量索引。
CREATE INDEX knowledge_source_scope_idx ON ai.knowledge_vector_store
    ((metadata->>'tenantId'), (metadata->>'knowledgeBase'), (metadata->>'language'), (metadata->>'sourceId'));
