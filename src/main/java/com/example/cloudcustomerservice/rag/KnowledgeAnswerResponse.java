package com.example.cloudcustomerservice.rag;

import java.util.List;

public record KnowledgeAnswerResponse(KnowledgeAnswerStatus status, String answer,
        List<KnowledgeReference> references) {
    public KnowledgeAnswerResponse { references = List.copyOf(references); }

    public static KnowledgeAnswerResponse noEvidence() {
        return new KnowledgeAnswerResponse(KnowledgeAnswerStatus.NO_EVIDENCE,
                "当前知识库中没有找到足够依据，暂时无法回答。可以补充问题细节或在知识检索页检查资料。", List.of());
    }

    public static KnowledgeAnswerResponse unavailable() {
        return new KnowledgeAnswerResponse(KnowledgeAnswerStatus.TEMPORARILY_UNAVAILABLE,
                "知识问答服务暂时不可用，请稍后重试。", List.of());
    }
}
