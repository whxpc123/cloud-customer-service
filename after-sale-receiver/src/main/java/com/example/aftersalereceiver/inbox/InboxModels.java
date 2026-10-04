package com.example.aftersalereceiver.inbox;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** 版本化入站协议；用户陈述与业务审核事实分开，接收不表示批准退款。 */
public final class InboxModels {

    private InboxModels() {
    }

    /*
     * 由认证后的服务端映射创建，
     * 不是客户端可填写的身份对象。
     */
    public record TrustedSource(
            String producerId,
            String tenantId
    ) {
        public TrustedSource {
            if (producerId == null
                    || !producerId.matches(
                            "[A-Za-z0-9_-]{1,128}"
                    )
                    || tenantId == null
                    || !tenantId.matches(
                            "[A-Za-z0-9_-]{1,64}"
                    )) {

                throw new IllegalArgumentException(
                        "Invalid trusted source"
                );
            }
        }
    }

    public record UserStatement(
            @NotBlank
            @Size(max = 1000)
            String userDescription,

            @NotBlank
            @Size(max = 300)
            String requestedHandling
    ) {
    }

    public record CreateApplicationEvent(
            @NotNull
            @Min(1)
            @Max(1)
            Integer schemaVersion,

            @NotNull
            UUID eventId,

            @NotBlank
            @Pattern(
                    regexp =
                        "AFTER_SALE_APPLICATION_CREATED"
            )
            String eventType,

            @NotNull
            UUID applicationId,

            @NotNull
            UUID operationId,

            @NotBlank
            @Pattern(
                    regexp = "[A-Za-z0-9_-]{1,64}"
            )
            String tenantId,

            @NotBlank
            @Pattern(regexp = "A\\d{5}")
            String orderNo,

            @NotNull
            @Positive
            Long draftVersion,

            @NotNull
            @Valid
            UserStatement userStatement,

            @NotNull
            OffsetDateTime occurredAt
    ) {
    }

    /*
     * 字段名与第二十七章发送方的 Ack 保持一致。
     */
    public record Ack(
            UUID eventId,
            UUID applicationId,
            String remoteApplicationId,
            String status
    ) {
    }
}
