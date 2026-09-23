package com.example.cloudcustomerservice.rag.expansion;

/** 原知识会话 API 的兼容扩展，旧客户端只发送 question 时默认 AUTO，不影响第九章无状态接口。 */
public record KnowledgeConversationQuestionRequest(String question, ExpansionMode expansionMode, Boolean rerankEnabled) {
    /** 旧客户端默认启用；关闭只跳过重排，保持相同宽召回与上下文预算以便对照。 */
    public KnowledgeConversationQuestionRequest(String question,ExpansionMode expansionMode) { this(question,expansionMode,true); }
    /** 空策略使用服务端规则，未知枚举值由 HTTP 解析拒绝。 */
    public KnowledgeConversationQuestionRequest {
        rerankEnabled = rerankEnabled == null ? true : rerankEnabled;
        expansionMode = expansionMode == null ? ExpansionMode.AUTO : expansionMode;
    }
}
