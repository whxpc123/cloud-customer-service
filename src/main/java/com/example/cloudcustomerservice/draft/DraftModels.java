package com.example.cloudcustomerservice.draft;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Assessment;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 用户陈述、可信事实快照、版本与确认回执分开；没有审批或退款命令字段。 */
public final class DraftModels {
    private DraftModels() { }
    public static final String SCOPE = "DRAFT_CONTENT_ONLY";
    public static final String NOTICE = "本版用于核对用户问题描述和申请诉求。不代表质量问题已核实、售后已获批、申请已提交或退款已执行。";

    /** 模型只能填写这两项；Bean Validation 管结构和长度，用户仍需核对含义与否定词。 */
    public record ProposedText(@NotBlank @Size(max=1000) String userDescription,
            @NotBlank @Size(max=300) String requestedHandling) { }
    /** 订单和 checkedSnapshot 从服务器最近完成结果复制，不允许客户端或模型覆盖。 */
    public record Body(int schemaVersion, String orderNo, ProposedText userStatement,
            Assessment checkedSnapshot, String notice) { }
    /** 时间由数据库生成，确认人取当前认证账户；scope 仅表示内容确认。 */
    public record Confirmation(UUID confirmationId, UUID taskId, long draftVersion,
            long confirmedBy, OffsetDateTime confirmedAt, String scope) { }
    /** 历史确认可能存在但已失效，页面必须分别展示 confirmation 与 confirmationEffective。 */
    public record View(UUID taskId, long draftVersion, long taskVersion, UUID basisRunId,
            OffsetDateTime createdAt, Body body, boolean current, boolean confirmationEffective,
            Confirmation confirmation) { }
    /** 目录不重复传输正文；查看时必须请求明确版本，不能让“最新”替换用户看见的内容。 */
    public record RevisionSummary(long draftVersion, UUID basisRunId, OffsetDateTime createdAt,
            boolean current, boolean confirmationEffective, Confirmation confirmation) { }
}
