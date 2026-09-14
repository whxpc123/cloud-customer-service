package com.example.cloudcustomerservice.rag;

/**
 * RAG 执行状态，而非答案正确性评级。
 * ANSWERED 包括模型生成的条件说明、冲突提示或拒答；无证据与依赖故障单独区分。
 */
public enum KnowledgeAnswerStatus {
    /**
     * 模型返回非空答复，包括拒答或冲突提示；未验证真实性。
     */
    ANSWERED,
    /**
     * 无可用证据，Java 跳过聊天生成。
     */
    NO_EVIDENCE,
    /**
     * 检索或生成失败，不能解释成没有知识。
     */
    TEMPORARILY_UNAVAILABLE
}
