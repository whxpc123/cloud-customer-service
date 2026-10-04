-- 第二十八章：独立接收库；处理记录、业务申请与固定回执在同一短事务中保存。
CREATE TABLE rx_inbox
(
    producer_id     varchar(128) NOT NULL,
    tenant_id       varchar(64) NOT NULL,
    event_id        uuid NOT NULL,

    payload         jsonb NOT NULL
                    CHECK (
                        jsonb_typeof(payload) = 'object'
                    ),

    status          varchar(16) NOT NULL
                    DEFAULT 'PROCESSING',

    receipt         jsonb,

    received_at     timestamptz NOT NULL
                    DEFAULT clock_timestamp(),

    processed_at    timestamptz,

    PRIMARY KEY (
        producer_id,
        tenant_id,
        event_id
    ),

    CHECK (
        (
            status = 'PROCESSING'
            AND receipt IS NULL
            AND processed_at IS NULL
        )
        OR
        (
            status = 'PROCESSED'
            AND receipt IS NOT NULL
            AND jsonb_typeof(receipt) = 'object'
            AND processed_at IS NOT NULL
        )
    )
);


CREATE TABLE rx_after_sale_application
(
    remote_application_id   uuid PRIMARY KEY,

    producer_id             varchar(128) NOT NULL,
    tenant_id               varchar(64) NOT NULL,

    source_event_id         uuid NOT NULL,
    source_application_id   uuid NOT NULL,
    source_operation_id     uuid NOT NULL,

    order_no                varchar(32) NOT NULL,
    draft_version           bigint NOT NULL
                            CHECK (draft_version > 0),

    user_statement          jsonb NOT NULL
                            CHECK (
                                jsonb_typeof(user_statement)
                                = 'object'
                            ),

    status                  varchar(32) NOT NULL
                            DEFAULT 'PENDING_REVIEW',

    created_at              timestamptz NOT NULL
                            DEFAULT clock_timestamp(),

    UNIQUE (
        producer_id,
        tenant_id,
        source_application_id
    ),

    UNIQUE (
        producer_id,
        tenant_id,
        source_operation_id
    ),

    FOREIGN KEY (
        producer_id,
        tenant_id,
        source_event_id
    )
    REFERENCES rx_inbox (
        producer_id,
        tenant_id,
        event_id
    )
);
