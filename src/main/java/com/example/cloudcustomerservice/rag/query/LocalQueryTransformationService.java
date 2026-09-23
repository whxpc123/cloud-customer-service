package com.example.cloudcustomerservice.rag.query;

import com.example.cloudcustomerservice.ai.advisor.CustomerAdvisorContextKeys;
import com.example.cloudcustomerservice.knowledge.*;
import com.example.cloudcustomerservice.rag.AdvisorKnowledgeAnswerService;
import java.util.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 查询实验直接使用同身份知识会话的历史快照；不会重复保存试验问题或伪造助手回答。 */
@Service
@Profile("local & knowledge")
public class LocalQueryTransformationService {
    private final ChatMemory memory;
    private final SafeQueryTransformer compression;
    private final SafeQueryTransformer rewrite;
    private final KnowledgeSearchService search;
    private final KnowledgeFilterFactory filters;

    public LocalQueryTransformationService(@Qualifier("customerChatMemory") ChatMemory memory,
            @Qualifier("conversationCompressionTransformer") SafeQueryTransformer compression,
            @Qualifier("searchRewriteTransformer") SafeQueryTransformer rewrite,
            KnowledgeSearchService search, KnowledgeFilterFactory filters) {
        this.memory = memory; this.compression = compression; this.rewrite = rewrite;
        this.search = search; this.filters = filters;
    }

    /**
     * 与发送/清空共享会话锁，避免实验读到半轮历史。compare 每次新增两次向量查询，页面显式提示。
     * 搜索故障保留转换结果和明确 FAILED 状态，不把故障伪装为零命中；不自动重试模型调用。
     */
    public QueryTransformationExperiment transform(String tenantId, String conversationId, Long userId,
            QueryTransformationRequest request) {
        String key = AdvisorKnowledgeAnswerService.memoryId(tenantId, conversationId, userId);
        if (request == null || request.question() == null || request.question().isBlank() || request.question().length() > 2000)
            throw new IllegalArgumentException("问题需要包含 1 至 2000 个字符");
        String question = request.question().strip();
        String requestId = UUID.randomUUID().toString();
        var trace = new QueryTransformationTrace(question);
        Map<String,Object> context = Map.of(QueryTransformationTrace.KEY, trace,
                CustomerAdvisorContextKeys.REQUEST_ID, requestId,
                VectorStoreDocumentRetriever.FILTER_EXPRESSION, filters.publishedAfterSales(tenantId));
        synchronized (KnowledgeConversationLocks.forKey(key)) {
            Query query = Query.builder().text(question).history(List.copyOf(memory.get(key))).context(context).build();
            Query transformed = compression.transform(query);
            if (request.rewrite()) transformed = rewrite.transform(transformed);
            KnowledgeSearchResult originalSearch = null, transformedSearch = null;
            String comparisonStatus = "NOT_REQUESTED";
            if (request.compare()) {
                if (trace.clarificationRequired()) comparisonStatus = "SKIPPED_CLARIFICATION";
                else {
                    try {
                        originalSearch = search.search(tenantId, question, 5, .60);
                        transformedSearch = search.search(tenantId, transformed.text(), 5, .60);
                        comparisonStatus = "COMPLETED";
                    } catch (KnowledgeUnavailableException ex) { comparisonStatus = "FAILED"; }
                }
            }
            return new QueryTransformationExperiment(requestId, conversationId, trace.snapshot(),
                    originalSearch, transformedSearch, comparisonStatus);
        }
    }
}
