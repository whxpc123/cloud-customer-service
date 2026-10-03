package com.example.cloudcustomerservice.outbox;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 队列存储：每次领取、失败或确认单独短事务；稳定事件与临时租约分别处理。 */
@Repository
@Profile("local & knowledge")
@Transactional(
        propagation = Propagation.REQUIRES_NEW,
        timeout = 5
)
public class OutboxStore {

    private static final int MAX_ATTEMPTS = 8;

    /** 领取快照包含固定正文；attemptCount 是领取次数而非 HTTP 发送计数。 */
    public record Claim(
            UUID eventId,
            UUID applicationId,
            String payload,
            int attemptCount,
            UUID leaseToken
    ) {
    }

    private final JdbcTemplate jdbc;

    public OutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一次领取一条到期任务；跳过其他工作者持锁的行，过期租约可被重新领取。 */
    public Optional<Claim> claimOne() {

        UUID token = UUID.randomUUID();

        return jdbc.query("""
                WITH candidate AS (
                    SELECT event_id
                    FROM ai.cs_outbox
                    WHERE attempt_count < ?
                      AND (
                          (
                              status = 'PENDING'
                              AND next_attempt_at <= clock_timestamp()
                          )
                          OR
                          (
                              status = 'SENDING'
                              AND lease_until <= clock_timestamp()
                          )
                      )
                    ORDER BY created_at, event_id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE ai.cs_outbox o
                SET status = 'SENDING',
                    attempt_count = attempt_count + 1,
                    lease_token = ?,
                    lease_until =
                        clock_timestamp() + interval '30 seconds'
                FROM candidate c
                WHERE o.event_id = c.event_id
                RETURNING o.*
                """,
                (rs, rowNum) -> new Claim(
                        rs.getObject("event_id", UUID.class),
                        rs.getObject(
                                "application_id",
                                UUID.class
                        ),
                        rs.getString("payload"),
                        rs.getInt("attempt_count"),
                        rs.getObject("lease_token", UUID.class)
                ),
                MAX_ATTEMPTS,
                token
        ).stream().findFirst();
    }

    /** 只允许仍持有本次租约的工作者确认成功；旧工作者迟到回写返回 false。 */
    public boolean delivered(
            Claim claim,
            String remoteApplicationId) {

        if (remoteApplicationId == null
                || remoteApplicationId.isBlank()
                || remoteApplicationId.length() > 200) {
            throw new IllegalArgumentException(
                    "Invalid remote receipt"
            );
        }

        return jdbc.update("""
                UPDATE ai.cs_outbox
                SET status = 'DELIVERED',
                    remote_application_id = ?,
                    delivered_at = clock_timestamp(),
                    last_error_code = NULL,
                    lease_token = NULL,
                    lease_until = NULL
                WHERE event_id = ?
                  AND status = 'SENDING'
                  AND lease_token = ?
                """,
                remoteApplicationId,
                claim.eventId(),
                claim.leaseToken()
        ) == 1;
    }

    /** 暂时故障指数退避并加抖动，永久故障或八次用尽转 REVIEW，不能断言远端未创建。 */
    public boolean failed(
            Claim claim,
            String safeCode,
            boolean retryable) {

        if (safeCode == null
                || !safeCode.matches("[A-Z0-9_]{1,80}")) {
            throw new IllegalArgumentException(
                    "Invalid error code"
            );
        }

        boolean review = !retryable
                || claim.attemptCount() >= MAX_ATTEMPTS;

        long base = Math.min(
                300L,
                5L << Math.min(
                        10,
                        claim.attemptCount() - 1
                )
        );

        long delaySeconds =
                ThreadLocalRandom.current().nextLong(
                        Math.max(1L, base / 2),
                        base + 1
                );

        return jdbc.update("""
                UPDATE ai.cs_outbox
                SET status = ?,
                    next_attempt_at =
                        clock_timestamp()
                        + (? * interval '1 second'),
                    last_error_code = ?,
                    lease_token = NULL,
                    lease_until = NULL
                WHERE event_id = ?
                  AND status = 'SENDING'
                  AND lease_token = ?
                """,
                review ? "REVIEW" : "PENDING",
                delaySeconds,
                safeCode,
                claim.eventId(),
                claim.leaseToken()
        ) == 1;
    }

    /*
     * 最后一次领取后进程退出，也不能永远留在 SENDING。
     * 达到上限且租约过期后，转入人工核查。
     */
    /** 回收最后一次领取后崩溃的过期租约，保留原 eventId 和正文供人工核查。 */
    public void reviewExhaustedLeases() {

        jdbc.update("""
                WITH exhausted AS (
                    SELECT event_id
                    FROM ai.cs_outbox
                    WHERE status = 'SENDING'
                      AND attempt_count >= ?
                      AND lease_until <= clock_timestamp()
                    ORDER BY lease_until, event_id
                    LIMIT 100
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE ai.cs_outbox o
                SET status = 'REVIEW',
                    last_error_code = 'LEASE_EXPIRED_LIMIT',
                    lease_token = NULL,
                    lease_until = NULL
                FROM exhausted e
                WHERE o.event_id = e.event_id
                """,
                MAX_ATTEMPTS
        );
    }
}
