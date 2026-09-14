package com.example.cloudcustomerservice.rag;

import com.example.cloudcustomerservice.knowledge.KnowledgeSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 第九章的 RAG 编排：检索 Retrieval、增强提示 Augmentation、生成回答 Generation。
 * 无可用正文时由 Java 直接返回 NO_EVIDENCE；检索仍可能调用向量模型。
 * 此流程无会话记忆和订单工具，非空生成结果也不代表已经验证事实正确。
 */
@Service
@Profile("local & knowledge")
public class CustomerKnowledgeAnswerService {
    private static final Logger log = LoggerFactory.getLogger(CustomerKnowledgeAnswerService.class);
    private final KnowledgeSearchService search;
    private final KnowledgeEvidenceFormatter formatter;
    private final ChatClient client;

    /**
     * 组合检索、证据格式化和专用 ChatClient；限定 Bean 名称，避免误注入带记忆的客服客户端。
     */
    public CustomerKnowledgeAnswerService(KnowledgeSearchService search, KnowledgeEvidenceFormatter formatter,
            @Qualifier("knowledgeAnswerChatClient") ChatClient client) {
        this.search = search;
        this.formatter = formatter;
        this.client = client;
    }

    /**
     * 固定检索最多 5 块、阈值 0.60；先过滤空正文，再决定是否调用聊天模型。
     * 只有本次检索证据进入提示词，来源由 Java 生成；任何阶段失败统一返回暂不可用。
     * @param tenantId 服务端确定的检索租户
     * @param question 完整问题，不依赖此前对话上下文
     * @return 执行状态、回答和本次证据；ANSWERED 并不证明逐句有依据
     */
    public KnowledgeAnswerResponse answer(String tenantId, String question) {
        if (tenantId == null || !tenantId.matches("[a-zA-Z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Invalid tenantId");
        if (question == null || question.isBlank() || question.length() > 2000)
            throw new IllegalArgumentException("问题需要包含 1 至 2000 个字符");
        String phase = "retrieval";
        try {
            // R：复用服务端范围过滤，保持本章固定的召回参数。
            var hits = search.search(tenantId, question.strip(), 5, 0.60).hits();
            var references = formatter.references(hits);
            // “无证据”是召回后的判断；跳过的是聊天生成，不是前面的查询向量调用。
            if (references.isEmpty()) {
                log.info("[RAG] status=NO_EVIDENCE hits={} evidenceChars=0 approximateTokens=0 modelCalled=false", hits.size());
                return KnowledgeAnswerResponse.noEvidence();
            }
            // A：响应来源与模型证据使用同一份不可变列表，不接受模型生成的来源。
            phase = "augmentation";
            String evidence = formatter.format(references);
            log.info("[RAG] hits={} evidenceCount={} evidenceChars={} approximateTokens={} tokenizer=CL100K_BASE",
                    hits.size(), references.size(), evidence.length(), formatter.approximateTokens(evidence));
            phase = "generation";
            // G：本章独立、无状态调用；不注册订单工具或会话 Advisor。
            String answer = client.prompt().user(formatter.userMessage(question.strip(), evidence)).call().content();
            if (answer == null || answer.isBlank()) {
                log.warn("[RAG] status=TEMPORARILY_UNAVAILABLE phase=generation reason=empty_answer");
                return KnowledgeAnswerResponse.unavailable();
            }
            // 此处只判定生成了非空文本，拒答或冲突说明也属于 ANSWERED。
            return new KnowledgeAnswerResponse(KnowledgeAnswerStatus.ANSWERED, answer.strip(), references);
        } catch (RuntimeException ex) {
            log.warn("[RAG] status=TEMPORARILY_UNAVAILABLE phase={} errorType={}", phase, ex.getClass().getSimpleName());
            return KnowledgeAnswerResponse.unavailable();
        }
    }
}
