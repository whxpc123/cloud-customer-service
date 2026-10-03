-- 业务任务是授权、范围和完成状态的权威来源；不保存 Agent、锁、连接或登录凭证。
CREATE TABLE ai.cs_draft_task (
    task_id uuid PRIMARY KEY,
    tenant_id varchar(64) NOT NULL,
    user_id bigint NOT NULL CHECK (user_id > 0),
    conversation_id uuid NOT NULL,
    thread_id varchar(255) NOT NULL UNIQUE,
    order_no varchar(32) NOT NULL,
    reason varchar(32) NOT NULL CHECK (reason IN ('QUALITY_ISSUE','CHANGE_OF_MIND','UNKNOWN')),
    agent_profile varchar(64) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'READY'
        CHECK (status IN ('READY','RUNNING','CANDIDATE_UNVALIDATED','NEEDS_ATTENTION','RECOVERY_REQUIRED','CLOSED')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    turn_no integer NOT NULL DEFAULT 0 CHECK (turn_no BETWEEN 0 AND 8),
    run_id uuid,
    last_checkpoint_id uuid,
    last_result_json jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tenant_id,conversation_id) REFERENCES ai.cs_conversation(tenant_id,id),
    CHECK ((last_checkpoint_id IS NULL) = (last_result_json IS NULL))
);
CREATE INDEX cs_draft_task_owner_idx ON ai.cs_draft_task(tenant_id,user_id,created_at DESC);

-- 兼容实际解析的 Graph Core 1.1.2.2 PostgresSaver SQL，未限定名字会解析到 public。
-- thread_name 才是 RunnableConfig.threadId；thread_id 是保存器自己生成的内部 UUID。
CREATE TABLE public.graphthread (
    thread_id uuid PRIMARY KEY,
    thread_name varchar(255),
    is_released boolean NOT NULL DEFAULT FALSE
);
CREATE TABLE public.graphcheckpoint (
    checkpoint_id uuid PRIMARY KEY,
    parent_checkpoint_id uuid,
    thread_id uuid NOT NULL REFERENCES public.graphthread(thread_id) ON DELETE CASCADE,
    node_id varchar(255),
    next_node_id varchar(255),
    -- binaryPayload 是框架序列化后的 Base64 数据，不是明文聊天 JSON，也不是加密。
    state_data jsonb NOT NULL,
    state_content_type varchar(100) NOT NULL,
    saved_at timestamptz DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_lg4jcheckpoint_thread_id ON public.graphcheckpoint(thread_id);
CREATE INDEX idx_lg4jcheckpoint_thread_id_saved_at_desc ON public.graphcheckpoint(thread_id,saved_at DESC);
CREATE UNIQUE INDEX idx_unique_lg4jthread_thread_name_unreleased
    ON public.graphthread(thread_name) WHERE is_released = FALSE;
