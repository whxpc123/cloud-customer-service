package com.example.cloudcustomerservice.reconcile;

import java.util.Objects;
import java.util.UUID;

/** 将远端响应转换为有限的核查证据；任何缺号、错号或矛盾字段都不能补记成功。 */
public final class ReconcileModel {

    private ReconcileModel() {
    }

    /** 观察分类与业务状态分离；只有 PERSISTED 可能触发本地修复。 */
    public enum Finding {
        PERSISTED,
        NOT_OBSERVED,
        PAYLOAD_CONFLICT,
        RECEIVER_INCONSISTENT,
        QUERY_UNAVAILABLE,
        INVALID_RESPONSE
    }

    /** 远端保存的创建回执；不是当前审核或退款状态。 */
    public record Ack(
            UUID eventId,
            UUID applicationId,
            String remoteApplicationId,
            String status
    ) {
    }

    /** 只读查询信封：除 PERSISTED 外不应带有成功回执。 */
    public record Reply(
            UUID eventId,
            String state,
            Ack receipt
    ) {
    }

    /** 保存到审计的最小证据，不保存远端错误正文、令牌或敏感请求。 */
    public record Evidence(
            Finding finding,
            String remoteId,
            String code
    ) {
        public Evidence {
            Objects.requireNonNull(finding);

            if (code == null
                    || !code.matches("[A-Z0-9_]{1,80}")) {
                throw new IllegalArgumentException(
                        "Invalid safe error code"
                );
            }

            if (finding == Finding.PERSISTED) {
                if (remoteId == null
                        || remoteId.isBlank()
                        || remoteId.length() > 200) {

                    throw new IllegalArgumentException(
                            "Missing remote receipt"
                    );
                }
            }
            else if (remoteId != null) {
                throw new IllegalArgumentException(
                        "Unexpected remote receipt"
                );
            }
        }
    }

    /** 独立校验信封和回执内的 eventId，以及申请编号、状态和远端编号。 */
    public static Evidence inspect(
            UUID eventId,
            UUID applicationId,
            Reply reply) {

        Objects.requireNonNull(eventId);
        Objects.requireNonNull(applicationId);

        if (reply == null
                || !eventId.equals(reply.eventId())
                || reply.state() == null) {
            return invalid();
        }

        if ("PERSISTED".equals(reply.state())) {

            Ack ack = reply.receipt();

            if (ack == null
                    || !eventId.equals(ack.eventId())
                    || !applicationId.equals(ack.applicationId())
                    || !"PERSISTED".equals(ack.status())
                    || ack.remoteApplicationId() == null
                    || ack.remoteApplicationId().isBlank()
                    || ack.remoteApplicationId().length() > 200) {

                return invalid();
            }

            return new Evidence(
                    Finding.PERSISTED,
                    ack.remoteApplicationId(),
                    "VERIFIED_RECEIPT"
            );
        }

        if (reply.receipt() != null) {
            return invalid();
        }

        return switch (reply.state()) {

            case "NOT_OBSERVED" ->
                    new Evidence(
                            Finding.NOT_OBSERVED,
                            null,
                            "NOT_OBSERVED"
                    );

            case "PAYLOAD_CONFLICT" ->
                    new Evidence(
                            Finding.PAYLOAD_CONFLICT,
                            null,
                            "PAYLOAD_CONFLICT"
                    );

            case "INCONSISTENT" ->
                    new Evidence(
                            Finding.RECEIVER_INCONSISTENT,
                            null,
                            "RECEIVER_INCONSISTENT"
                    );

            default -> invalid();
        };
    }

    private static Evidence invalid() {
        return new Evidence(
                Finding.INVALID_RESPONSE,
                null,
                "INVALID_LOOKUP_RESPONSE"
        );
    }
}
