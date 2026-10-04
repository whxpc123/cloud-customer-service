package com.example.aftersalereceiver.inbox;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.example.aftersalereceiver.inbox.InboxModels.*;

/** PostgreSQL 短事务接收器：首次处理整体提交，重复处理只读取固定回执。 */
@Service
public class InboxApplicationService {

    private final JdbcTemplate jdbc;
    private final InboxProtocol protocol;
    private final ObjectMapper mapper;

    public InboxApplicationService(
            JdbcTemplate jdbc,
            InboxProtocol protocol,
            ObjectMapper mapper) {

        this.jdbc = jdbc;
        this.protocol = protocol;
        this.mapper = mapper;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5,
            rollbackFor = Exception.class
    )
    public Ack receive(
            TrustedSource source,
            UUID headerEventId,
            String json) throws JsonProcessingException {

        Objects.requireNonNull(source);

        CreateApplicationEvent event =
                protocol.parse(json);

        if (!event.eventId().equals(headerEventId)) {
            throw problem(
                    HttpStatus.BAD_REQUEST,
                    "IDEMPOTENCY_KEY_MISMATCH"
            );
        }

        if (!source.tenantId().equals(event.tenantId())) {
            throw problem(
                    HttpStatus.FORBIDDEN,
                    "TENANT_NOT_ALLOWED"
            );
        }

        /*
         * 1. 通过唯一约束争取处理资格。
         * 不先 SELECT 再 INSERT。
         */
        // 数据库端超时也覆盖唯一索引的等待，不无限占用连接池。
        jdbc.execute("SET LOCAL lock_timeout = '3s'");
        jdbc.execute("SET LOCAL statement_timeout = '4s'");
        int inserted = jdbc.update("""
                INSERT INTO rx_inbox (
                    producer_id,
                    tenant_id,
                    event_id,
                    payload
                )
                VALUES (?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (
                    producer_id,
                    tenant_id,
                    event_id
                )
                DO NOTHING
                """,
                source.producerId(),
                source.tenantId(),
                event.eventId(),
                json
        );

        if (inserted == 0) {
            /*
             * 独立的下一条 SELECT：
             * 校验原正文并返回已保存回执。
             */
            return replay(source, event, json);
        }

        /*
         * 2. 第一次处理，创建接收方自己的申请。
         */
        UUID remoteId = UUID.randomUUID();

        List<UUID> created = jdbc.query("""
                INSERT INTO rx_after_sale_application (
                    remote_application_id,
                    producer_id,
                    tenant_id,
                    source_event_id,
                    source_application_id,
                    source_operation_id,
                    order_no,
                    draft_version,
                    user_statement
                )
                VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?,
                    CAST(? AS jsonb) -> 'userStatement'
                )
                ON CONFLICT DO NOTHING
                RETURNING remote_application_id
                """,
                (rs, rowNum) ->
                        rs.getObject(1, UUID.class),
                remoteId,
                source.producerId(),
                source.tenantId(),
                event.eventId(),
                event.applicationId(),
                event.operationId(),
                event.orderNo(),
                event.draftVersion(),
                json
        );

        if (created.size() != 1) {
            /*
             * 换 eventId 却复用来源申请或操作：
             * 不默默创建第二份，也不覆盖第一份。
             *
             * 抛异常后，本轮 Inbox 插入一起回滚。
             */
            throw problem(
                    HttpStatus.CONFLICT,
                    "SOURCE_APPLICATION_OR_OPERATION_CONFLICT"
            );
        }

        Ack ack = new Ack(
                event.eventId(),
                event.applicationId(),
                remoteId.toString(),
                "PERSISTED"
        );

        /*
         * 3. 保存这条事件的固定成功回执。
         */
        int completed = jdbc.update("""
                UPDATE rx_inbox
                SET status = 'PROCESSED',
                    receipt = CAST(? AS jsonb),
                    processed_at = clock_timestamp()
                WHERE producer_id = ?
                  AND tenant_id = ?
                  AND event_id = ?
                  AND status = 'PROCESSING'
                """,
                mapper.writeValueAsString(ack),
                source.producerId(),
                source.tenantId(),
                event.eventId()
        );

        if (completed != 1) {
            throw new IllegalStateException(
                    "INBOX_COMPLETION_CONFLICT"
            );
        }

        /*
         * 方法通过 Spring 事务代理返回到 Controller 前，
         * 整笔数据库事务必须成功提交。
         */
        return ack;
    }

    /** READ COMMITTED 下使用下一条 SQL 的新快照，读取竞争事务刚提交的回执。 */
    private Ack replay(
            TrustedSource source,
            CreateApplicationEvent event,
            String json) throws JsonProcessingException {

        List<Saved> rows = jdbc.query("""
                SELECT
                    status,
                    receipt::text AS receipt_json,
                    payload = CAST(? AS jsonb) AS same_payload
                FROM rx_inbox
                WHERE producer_id = ?
                  AND tenant_id = ?
                  AND event_id = ?
                """,
                (rs, rowNum) -> new Saved(
                        rs.getString("status"),
                        rs.getString("receipt_json"),
                        rs.getBoolean("same_payload")
                ),
                json,
                source.producerId(),
                source.tenantId(),
                event.eventId()
        );

        if (rows.size() != 1) {
            throw problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "INBOX_RESULT_UNCONFIRMED"
            );
        }

        Saved saved = rows.get(0);

        if (!saved.samePayload()) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "EVENT_PAYLOAD_CONFLICT"
            );
        }

        if (!"PROCESSED".equals(saved.status())
                || saved.receiptJson() == null) {

            throw problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "INBOX_NOT_COMPLETED"
            );
        }

        Ack ack = mapper.readValue(
                saved.receiptJson(),
                Ack.class
        );

        if (ack == null
                || !event.eventId().equals(ack.eventId())
                || !event.applicationId()
                        .equals(ack.applicationId())
                || !"PERSISTED".equals(ack.status())
                || ack.remoteApplicationId() == null
                || ack.remoteApplicationId().isBlank()) {

            throw new IllegalStateException(
                    "INBOX_RECEIPT_INVALID"
            );
        }

        return ack;
    }

    private ResponseStatusException problem(
            HttpStatus status,
            String code) {

        return new ResponseStatusException(
                status,
                code
        );
    }

    private record Saved(
            String status,
            String receiptJson,
            boolean samePayload
    ) {
    }
}
