package com.example.cloudcustomerservice.reconcile;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.cloudcustomerservice.handoff.HandoffModel.Actor;

import static com.example.cloudcustomerservice.reconcile.ReconcileModel.*;

/** 两个短事务：开始留痕，结束时将观察记录与状态修复整体提交。 */
@Repository
@org.springframework.context.annotation.Profile("local & knowledge")
@Transactional(
        propagation = Propagation.REQUIRES_NEW,
        timeout = 3,
        rollbackFor = Exception.class
)
public class OutboxReconciliationStore {

    /** 第一事务生成的快照只在服务端流转；原正文与操作人不得由浏览器提供。 */
    public record Work(
            UUID checkId,
            UUID eventId,
            UUID applicationId,
            Actor operator,
            long expectedVersion,
            String payload
    ) {
    }

    /** finding 是观察；repaired 才表示这次核查确实改变了本地送达记录。 */
    public record Result(
            UUID checkId,
            UUID eventId,
            String finding,
            String outboxStatus,
            String auditStatus,
            boolean repaired
    ) {
    }

    private record Row(
            UUID applicationId,
            String payload,
            String status,
            long version
    ) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OutboxReconciliationStore(
            JdbcTemplate jdbc,
            ObjectMapper mapper) {

        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 锁住原事件后保存 STARTED；网络发生前提交事务，不长时间持有数据库锁。 */
    public Work begin(Actor operator, UUID eventId) {

        Row row = lock(operator, eventId);

        if (!"REVIEW".equals(row.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "当前记录不处于待核查状态，请查询最新同步状态。"
            );
        }

        UUID checkId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO ai.cs_outbox_reconciliation (
                    check_id, event_id, tenant_id,
                    requested_by, expected_version,
                    state_before
                )
                VALUES (?, ?, ?, ?, ?, 'REVIEW')
                """,
                checkId,
                eventId,
                operator.tenantId(),
                operator.accountId(),
                row.version()
        );

        return new Work(
                checkId,
                eventId,
                row.applicationId(),
                operator,
                row.version(),
                row.payload()
        );
    }

    /** 重新加锁并比较版本；观察记录与可选修复同事务提交，任一步失败一起回滚。 */
    public Result record(
            Work work,
            Evidence evidence) throws JsonProcessingException {

        Row current = lock(
                work.operator(),
                work.eventId()
        );

        boolean fresh =
                "REVIEW".equals(current.status())
                && current.version() == work.expectedVersion();

        boolean repaired =
                fresh && evidence.finding() == Finding.PERSISTED;

        String nextStatus =
                repaired ? "DELIVERED" : current.status();

        // 每条新鲜观察都推进版本，包括“未观察到”；不能让更旧的肯定结果绕过这一事实。
        if (fresh) {
            int changed = jdbc.update("""
                    UPDATE ai.cs_outbox
                    SET status = ?,
                        remote_application_id =
                            CASE WHEN ? THEN ?
                                 ELSE remote_application_id END,
                        delivered_at =
                            CASE WHEN ? THEN clock_timestamp()
                                 ELSE delivered_at END,
                        last_error_code =
                            CASE WHEN ? THEN NULL
                                 ELSE last_error_code END,
                        reconcile_version = reconcile_version + 1
                    WHERE event_id = ?
                      AND tenant_id = ?
                      AND status = 'REVIEW'
                      AND reconcile_version = ?
                    """,
                    nextStatus,
                    repaired,
                    evidence.remoteId(),
                    repaired,
                    repaired,
                    work.eventId(),
                    work.operator().tenantId(),
                    work.expectedVersion()
            );

            requireOne(changed);
        }

        String auditStatus = fresh
                ? "RECORDED"
                : "STALE";

        int recorded = jdbc.update("""
                UPDATE ai.cs_outbox_reconciliation
                SET status = ?,
                    finding = ?,
                    observation_json = CAST(? AS jsonb),
                    state_after = ?,
                    repaired = ?,
                    completed_at = clock_timestamp()
                WHERE check_id = ?
                  AND event_id = ?
                  AND tenant_id = ?
                  AND requested_by = ?
                  AND expected_version = ?
                  AND status = 'STARTED'
                """,
                auditStatus,
                evidence.finding().name(),
                mapper.writeValueAsString(evidence),
                nextStatus,
                repaired,
                work.checkId(),
                work.eventId(),
                work.operator().tenantId(),
                work.operator().accountId(),
                work.expectedVersion()
        );

        requireOne(recorded);

        return new Result(
                work.checkId(),
                work.eventId(),
                evidence.finding().name(),
                nextStatus,
                auditStatus,
                repaired
        );
    }

    /** 始终绑定当前租户和固定目的地；跨租户事件与不存在事件统一为404。 */
    private Row lock(Actor operator, UUID eventId) {

        if (operator == null || operator.accountId() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED
            );
        }

        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        List<Row> rows = jdbc.query("""
                SELECT application_id, payload::text,
                       status, reconcile_version
                FROM ai.cs_outbox
                WHERE event_id = ?
                  AND tenant_id = ?
                  AND destination = 'AFTER_SALE_V1'
                FOR UPDATE
                """,
                (rs, rowNum) -> new Row(
                        rs.getObject(
                                "application_id",
                                UUID.class
                        ),
                        rs.getString("payload"),
                        rs.getString("status"),
                        rs.getLong("reconcile_version")
                ),
                eventId,
                operator.tenantId()
        );

        if (rows.size() != 1) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "未找到可核查的投递记录。"
            );
        }

        return rows.get(0);
    }

    private void requireOne(int changed) {
        if (changed != 1) {
            throw new IllegalStateException(
                    "Reconciliation state conflict"
            );
        }
    }
}
