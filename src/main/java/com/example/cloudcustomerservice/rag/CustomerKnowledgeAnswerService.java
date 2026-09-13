package com.example.cloudcustomerservice.rag;

import com.example.cloudcustomerservice.knowledge.KnowledgeSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("local & knowledge")
public class CustomerKnowledgeAnswerService {
    private static final Logger log = LoggerFactory.getLogger(CustomerKnowledgeAnswerService.class);
    private final KnowledgeSearchService search;
    private final KnowledgeEvidenceFormatter formatter;
    private final ChatClient client;

    public CustomerKnowledgeAnswerService(KnowledgeSearchService search, KnowledgeEvidenceFormatter formatter,
            @Qualifier("knowledgeAnswerChatClient") ChatClient client) {
        this.search = search;
        this.formatter = formatter;
        this.client = client;
    }

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
            return new KnowledgeAnswerResponse(KnowledgeAnswerStatus.ANSWERED, answer.strip(), references);
        } catch (RuntimeException ex) {
            log.warn("[RAG] status=TEMPORARILY_UNAVAILABLE phase={} errorType={}", phase, ex.getClass().getSimpleName());
            return KnowledgeAnswerResponse.unavailable();
        }
    }
}
