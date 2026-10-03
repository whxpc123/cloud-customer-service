package com.example.cloudcustomerservice.outbox;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 确定性低并发投递器：短事务领取 → 无事务 HTTP → 短事务回写；不重新运行 Agent。 */
@Component
@Profile("local & knowledge & outbox-delivery")
public class OutboxRelay {

    private static final Logger log =
            LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore store;
    private final RemoteAfterSaleClient remote;

    public OutboxRelay(
            OutboxStore store,
            RemoteAfterSaleClient remote) {

        this.store = store;
        this.remote = remote;
    }

    @Scheduled(
            fixedDelayString =
                    "${app.outbox.poll-delay-ms:1000}"
    )
    /** 单次定时工作最多投递一条，数据库异常由后续轮询恢复，不抹除未知结果。 */
    public void tick() {

        try {
            store.reviewExhaustedLeases();

            Optional<OutboxStore.Claim> next =
                    store.claimOne();

            if (next.isEmpty()) {
                return;
            }

            deliverOne(next.get());
        }
        catch (RuntimeException ex) {
            log.warn(
                    "Outbox tick failed, errorType={}",
                    ex.getClass().getSimpleName()
            );
        }
    }

    /** 远端回执保存失败时保留 SENDING，租约到期后使用原事件重试。 */
    private void deliverOne(OutboxStore.Claim claim) {

        RemoteAfterSaleClient.Ack ack;

        try {
            // 没有数据库事务覆盖这次 HTTP 调用。
            ack = remote.deliver(claim);
        }
        catch (RemoteAfterSaleClient.DeliveryFailure ex) {

            boolean updated = store.failed(
                    claim,
                    ex.code(),
                    ex.retryable()
            );

            log.warn(
                    "Outbox delivery unconfirmed, "
                            + "eventId={}, code={}, stateUpdated={}",
                    claim.eventId(),
                    ex.code(),
                    updated
            );
            return;
        }
        catch (RuntimeException ex) {

            store.failed(
                    claim,
                    "UNCLASSIFIED_CLIENT_ERROR",
                    false
            );
            return;
        }

        /*
         * 已取得远端回执。
         * 如果这里写库失败，不把它重新解释为远端失败。
         * 保留未确认状态，后续仍使用原 eventId 核查或重投。
         */
        try {
            boolean updated = store.delivered(
                    claim,
                    ack.remoteApplicationId()
            );

            if (!updated) {
                log.warn(
                        "Outbox lease changed, eventId={}",
                        claim.eventId()
                );
            }
        }
        catch (RuntimeException ex) {
            log.warn(
                    "Remote ack received but local save "
                            + "not confirmed, eventId={}",
                    claim.eventId()
            );
        }
    }
}
