package com.example.cloudcustomerservice.outbox;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 同库事件写入器：只从已批准申请的不可变快照选取字段，禁止自行开启独立事务。 */
@Service
@Profile("local & knowledge")
public class OutboxWriter {

    private final JdbcTemplate jdbc;

    public OutboxWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    /** 在创建事务内写一条事件；历史回放不调用此方法，失败不得吞掉。 */
    public void appendIfRequired(UUID applicationId) {

        List<String> profiles = jdbc.queryForList("""
                SELECT o.delivery_profile
                FROM ai.cs_after_sale_application a
                JOIN ai.cs_submit_operation o
                  ON o.operation_id = a.operation_id
                WHERE a.application_id = ?
                """,
                String.class,
                applicationId
        );

        if (profiles.size() != 1) {
            throw new IllegalStateException(
                    "Application or operation is missing"
            );
        }

        if ("LOCAL_ONLY".equals(profiles.get(0))) {
            return;
        }

        if (!"AFTER_SALE_V1".equals(profiles.get(0))) {
            throw new IllegalStateException(
                    "Unsupported delivery profile"
            );
        }

        UUID eventId = UUID.randomUUID();

        int inserted = jdbc.update("""
                INSERT INTO ai.cs_outbox (
                    event_id,
                    application_id,
                    operation_id,
                    tenant_id,
                    destination,
                    event_type,
                    payload
                )
                SELECT
                    ?,
                    a.application_id,
                    a.operation_id,
                    a.tenant_id,
                    'AFTER_SALE_V1',
                    'AFTER_SALE_APPLICATION_CREATED',
                    jsonb_build_object(
                        'schemaVersion', 1,
                        'eventId', CAST(? AS text),
                        'eventType',
                            'AFTER_SALE_APPLICATION_CREATED',
                        'applicationId', a.application_id,
                        'operationId', a.operation_id,
                        'tenantId', a.tenant_id,
                        'orderNo', a.order_no,
                        'draftVersion', a.draft_version,
                        'userStatement',
                            a.body_snapshot -> 'userStatement',
                        'occurredAt', a.created_at
                    )
                FROM ai.cs_after_sale_application a
                WHERE a.application_id = ?
                  AND jsonb_typeof(
                      a.body_snapshot -> 'userStatement'
                  ) = 'object'
                """,
                eventId,
                eventId.toString(),
                applicationId
        );

        if (inserted != 1) {
            throw new IllegalStateException(
                    "Outbox event was not created"
            );
        }
    }
}
