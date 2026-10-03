-- 第27章：历史授权默认为 LOCAL_ONLY；不回填或补发任何历史申请。
ALTER TABLE ai.cs_submit_operation
ADD COLUMN delivery_profile varchar(32) NOT NULL
DEFAULT 'LOCAL_ONLY'
CHECK (
    delivery_profile IN (
        'LOCAL_ONLY',
        'AFTER_SALE_V1'
    )
);

CREATE OR REPLACE FUNCTION ai.cs_reject_submit_target_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.operation_id,NEW.task_id,NEW.draft_version,NEW.action,NEW.reception_version,NEW.delivery_profile)
        IS DISTINCT FROM ROW(OLD.operation_id,OLD.task_id,OLD.draft_version,OLD.action,OLD.reception_version,OLD.delivery_profile) THEN
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

ALTER TABLE ai.cs_after_sale_application ADD UNIQUE(application_id,operation_id,tenant_id);

CREATE TABLE ai.cs_outbox
(
    event_id               uuid PRIMARY KEY,

    application_id         uuid NOT NULL
                           REFERENCES ai.cs_after_sale_application(
                               application_id
                           ),

    operation_id           uuid NOT NULL,
    tenant_id              varchar(64) NOT NULL,

    destination            varchar(32) NOT NULL
                           CHECK (
                               destination = 'AFTER_SALE_V1'
                           ),

    event_type             varchar(64) NOT NULL
                           CHECK (
                               event_type =
                               'AFTER_SALE_APPLICATION_CREATED'
                           ),

    payload                jsonb NOT NULL
                           CHECK (
                               jsonb_typeof(payload) = 'object'
                           ),

    status                 varchar(16) NOT NULL
                           DEFAULT 'PENDING'
                           CHECK (
                               status IN (
                                   'PENDING',
                                   'SENDING',
                                   'DELIVERED',
                                   'REVIEW'
                               )
                           ),

    attempt_count          integer NOT NULL DEFAULT 0
                           CHECK (attempt_count >= 0),

    next_attempt_at        timestamptz NOT NULL
                           DEFAULT clock_timestamp(),

    lease_token            uuid,
    lease_until            timestamptz,

    remote_application_id  varchar(200),
    delivered_at           timestamptz,

    last_error_code        varchar(80),

    created_at             timestamptz NOT NULL
                           DEFAULT clock_timestamp(),

    FOREIGN KEY(application_id,operation_id,tenant_id)
        REFERENCES ai.cs_after_sale_application(application_id,operation_id,tenant_id),

    UNIQUE (
        application_id,
        destination,
        event_type
    ),

    CHECK (
        (
            status = 'SENDING'
            AND lease_token IS NOT NULL
            AND lease_until IS NOT NULL
        )
        OR
        (
            status <> 'SENDING'
            AND lease_token IS NULL
            AND lease_until IS NULL
        )
    ),

    CHECK (
        status <> 'DELIVERED'
        OR (
            remote_application_id IS NOT NULL
            AND delivered_at IS NOT NULL
        )
    )
);

CREATE INDEX cs_outbox_pending_idx
ON ai.cs_outbox(next_attempt_at, created_at, event_id)
WHERE status = 'PENDING';

CREATE INDEX cs_outbox_sending_idx
ON ai.cs_outbox(lease_until, event_id)
WHERE status = 'SENDING';

CREATE FUNCTION ai.cs_outbox_payload_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF ROW(
        NEW.event_id,
        NEW.application_id,
        NEW.operation_id,
        NEW.tenant_id,
        NEW.destination,
        NEW.event_type,
        NEW.payload
    ) IS DISTINCT FROM ROW(
        OLD.event_id,
        OLD.application_id,
        OLD.operation_id,
        OLD.tenant_id,
        OLD.destination,
        OLD.event_type,
        OLD.payload
    ) THEN
        RAISE EXCEPTION
            'Outbox event content is immutable';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER cs_outbox_no_payload_rewrite
BEFORE UPDATE ON ai.cs_outbox
FOR EACH ROW
EXECUTE FUNCTION ai.cs_outbox_payload_immutable();
