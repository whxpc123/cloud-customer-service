-- 会话行是接待状态的唯一事实来源；等待队列是查询结果，不另建内存队列或独立工单状态。
CREATE TABLE ai.cs_conversation (
    tenant_id varchar(64) NOT NULL,
    id uuid NOT NULL,
    user_id bigint NOT NULL CHECK (user_id > 0),
    mode varchar(24) NOT NULL DEFAULT 'BOT',
    handoff_id uuid UNIQUE,
    assigned_agent_id bigint CHECK (assigned_agent_id > 0),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    requested_at timestamptz,
    accepted_at timestamptz,
    closed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 模型在事务外工作。租约防同会话并发生成；交接不等待租约，发布时再检查版本。
    generation_id uuid,
    generation_started_at timestamptz,
    memory_after bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, id),
    CHECK ((generation_id IS NULL) = (generation_started_at IS NULL)),
    CHECK (
      (mode='BOT' AND handoff_id IS NULL AND assigned_agent_id IS NULL AND requested_at IS NULL AND accepted_at IS NULL AND closed_at IS NULL)
      OR (mode='WAITING_HUMAN' AND handoff_id IS NOT NULL AND assigned_agent_id IS NULL AND requested_at IS NOT NULL AND accepted_at IS NULL AND closed_at IS NULL)
      OR (mode='HUMAN_ACTIVE' AND handoff_id IS NOT NULL AND assigned_agent_id IS NOT NULL AND requested_at IS NOT NULL AND accepted_at IS NOT NULL AND closed_at IS NULL)
      OR (mode='CLOSED' AND handoff_id IS NOT NULL AND assigned_agent_id IS NOT NULL AND requested_at IS NOT NULL AND accepted_at IS NOT NULL AND closed_at IS NOT NULL)
    )
);
CREATE INDEX cs_conversation_waiting_idx ON ai.cs_conversation(tenant_id,requested_at,id) WHERE mode='WAITING_HUMAN';
CREATE INDEX cs_conversation_owner_idx ON ai.cs_conversation(tenant_id,user_id,created_at DESC);

-- 完整正式消息与有限模型记忆分离。payload 保留真实业务结构，不保存被版本门拦截的候选答案。
CREATE TABLE ai.cs_message (
    id bigserial PRIMARY KEY,
    tenant_id varchar(64) NOT NULL,
    conversation_id uuid NOT NULL,
    role varchar(16) NOT NULL CHECK (role IN ('USER','BOT','SYSTEM')),
    author_id bigint CHECK (author_id > 0),
    client_message_id uuid,
    reply_to bigint REFERENCES ai.cs_message(id),
    content text NOT NULL,
    payload jsonb,
    version bigint NOT NULL CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tenant_id,conversation_id) REFERENCES ai.cs_conversation(tenant_id,id),
    UNIQUE (tenant_id,conversation_id,client_message_id),
    CHECK (client_message_id IS NULL OR role='USER')
);
CREATE INDEX cs_message_history_idx ON ai.cs_message(tenant_id,conversation_id,id);
