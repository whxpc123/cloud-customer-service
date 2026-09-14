package com.example.cloudcustomerservice.rag;

import java.util.List;

/**
 * 第九章问答响应，将执行状态、答复和证据分开，避免仅凭自然语言判断流程。
 *
 * @param status 生成、无证据或依赖故障状态
 * @param answer 模型答复或 Java 提供的固定说明
 * @param references 本次实际提供给模型的证据；无证据或故障时为空列表
 */
public record KnowledgeAnswerResponse(KnowledgeAnswerStatus status, String answer,
        List<KnowledgeReference> references) {
    /**
     * 复制来源列表，保证响应不随外部增删变化。
     */
    public KnowledgeAnswerResponse { references = List.copyOf(references); }

    /**
     * 由 Java 直接返回缺少依据的固定答复，不让聊天模型用自身知识补齐。
     */
    public static KnowledgeAnswerResponse noEvidence() {
        return new KnowledgeAnswerResponse(KnowledgeAnswerStatus.NO_EVIDENCE,
                "当前知识库中没有找到足够依据，暂时无法回答。可以补充问题细节或在知识检索页检查资料。", List.of());
    }

    /**
     * 检索或生成失败时返回稳定说明及空来源，避免展示不完整的成功结果。
     */
    public static KnowledgeAnswerResponse unavailable() {
        return new KnowledgeAnswerResponse(KnowledgeAnswerStatus.TEMPORARILY_UNAVAILABLE,
                "知识问答服务暂时不可用，请稍后重试。", List.of());
    }
}
