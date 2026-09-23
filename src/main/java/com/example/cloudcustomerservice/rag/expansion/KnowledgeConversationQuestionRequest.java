package com.example.cloudcustomerservice.rag.expansion;

/** 原知识会话 API 的兼容扩展，旧客户端只发送 question 时默认 AUTO，不影响第九章无状态接口。 */
public record KnowledgeConversationQuestionRequest(String question, ExpansionMode expansionMode) {
    /** 空策略使用服务端规则，未知枚举值由 HTTP 解析拒绝。 */
    public KnowledgeConversationQuestionRequest {
        expansionMode = expansionMode == null ? ExpansionMode.AUTO : expansionMode;
    }
}
