package com.example.cloudcustomerservice.rag.expansion;

import com.example.cloudcustomerservice.ai.advisor.CustomerAdvisorContextKeys;
import com.example.cloudcustomerservice.knowledge.KnowledgeFilterFactory;
import com.example.cloudcustomerservice.rag.*;
import com.example.cloudcustomerservice.rag.query.*;
import java.util.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 只读会话实验：与正式请求使用相同的 Compression、扩展保护、检索和合并。 */
@Service
@Profile("local & knowledge")
public class LocalQueryExpansionService {
    private final ChatMemory memory;
    private final SafeQueryTransformer compression;
    private final GuardedQueryExpander expander;
    private final MultiQueryRetrieval retrieval;
    private final KnowledgeFilterFactory filters;

    /** 身份、过滤器及数量范围都在模型外部确定。 */
    public LocalQueryExpansionService(@Qualifier("customerChatMemory") ChatMemory memory,
            @Qualifier("conversationCompressionTransformer") SafeQueryTransformer compression,
            GuardedQueryExpander expander, MultiQueryRetrieval retrieval, KnowledgeFilterFactory filters) {
        this.memory = memory; this.compression = compression; this.expander = expander;
        this.retrieval = retrieval; this.filters = filters;
    }

    /** 共享知识会话锁读取稳定历史，不把实验 Query 或占位回答写回记忆。 */
    public QueryExpansionExperiment expand(String tenantId, String conversationId, Long userId, QueryExpansionRequest request) {
        String memoryKey = AdvisorKnowledgeAnswerService.memoryId(tenantId, conversationId, userId);
        if (request == null || request.question() == null || request.question().isBlank() || request.question().length() > 2000)
            throw new IllegalArgumentException("问题需要包含 1 至 2000 个字符");
        String question = request.question().strip();
        String requestId = UUID.randomUUID().toString();
        var transformation = new QueryTransformationTrace(question);
        var expansion = new QueryExpansionTrace(question, ExpansionMode.ON, request.numberOfQueries(), request.includeOriginal());
        Map<String, Object> context = Map.of(QueryTransformationTrace.KEY, transformation, QueryExpansionTrace.KEY, expansion,
                CustomerAdvisorContextKeys.REQUEST_ID, requestId, CustomerAdvisorContextKeys.TENANT_ID, tenantId,
                VectorStoreDocumentRetriever.FILTER_EXPRESSION, filters.publishedAfterSales(tenantId));
        synchronized (KnowledgeConversationLocks.forKey(memoryKey)) {
            Query original = Query.builder().text(question).history(List.copyOf(memory.get(memoryKey))).context(context).build();
            Query transformed = compression.transform(original);
            List<Query> queries = expander.expand(transformed);
            String comparisonStatus = "NOT_REQUESTED";
            QueryExpansionExperiment.Baseline baseline = null;
            if (request.compare()) {
                if (transformation.clarificationRequired()) comparisonStatus = "SKIPPED_CLARIFICATION";
                else {
                    baseline = baseline(transformed);
                    try {
                        retrieval.searchAndJoin(queries);
                        comparisonStatus = baseline.status().equals("COMPLETED") ? "COMPLETED" : "FAILED";
                    } catch (RuntimeException ex) { comparisonStatus = "FAILED"; }
                }
            }
            return new QueryExpansionExperiment(requestId, conversationId, transformation.snapshot(), expansion.snapshot(), baseline, comparisonStatus);
        }
    }

    /** 对补全后的完整问题做一次独立 Top 5，不污染多路轨迹；失败只返回状态，不暴露异常正文。 */
    private QueryExpansionExperiment.Baseline baseline(Query transformed) {
        long started = System.nanoTime();
        var trace = new QueryExpansionTrace(transformed.text(), ExpansionMode.OFF, 3, true);
        trace.planned(transformed.text(), "DISABLED", List.of(transformed.text()), 0, 0, false);
        Map<String, Object> context = new HashMap<>(transformed.context());
        context.put(QueryExpansionTrace.KEY, trace);
        // 基线仅用于对照，不将它写入正式首路检索字段。
        context.remove(QueryTransformationTrace.KEY);
        try {
            var documents = retrieval.retrieve(transformed.mutate().context(context).build());
            return new QueryExpansionExperiment.Baseline(transformed.text(), 5, "COMPLETED", elapsed(started),
                    documents.stream().map(KnowledgeReference::from).toList());
        } catch (RuntimeException ex) {
            return new QueryExpansionExperiment.Baseline(transformed.text(), 5, "FAILED", elapsed(started), List.of());
        }
    }
    /** 比较检索耗时包含该路 Embedding 和数据库调用。 */
    private long elapsed(long started) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
}
