package com.example.cloudcustomerservice.rag;

import java.util.List;

/**
 * 多轮知识页的一次响应，沿用第九章状态和来源契约并附带调用关联信息。
 * @param requestId 服务端生成的审计 ID
 * @param conversationId 页面使用的外部会话 ID，不是内部记忆键
 * @param retrievalQuery 当前实际用于检索的原始问题，本章尚未自动改写
 * @param status 执行状态，ANSWERED 不代表答案已经逐句核实
 * @param answer 模型答复或 Java 的固定说明
 * @param references 本次 QAA Context 中实际检索并提供给模型的文档快照
 */
public record AdvisorKnowledgeAnswerResponse(String requestId, String conversationId, String retrievalQuery,
        KnowledgeAnswerStatus status, String answer, List<KnowledgeReference> references) {
    /** 复制来源集合，避免后续修改影响已经返回给页面的结果。 */
    public AdvisorKnowledgeAnswerResponse { references = List.copyOf(references); }
}
