package com.example.cloudcustomerservice.rag.expansion;

import com.example.cloudcustomerservice.ai.advisor.CustomerAdvisorContextKeys;
import com.example.cloudcustomerservice.ai.advisor.EvidenceRequiredAdvisor;
import com.example.cloudcustomerservice.rag.query.QueryTransformationTrace;
import com.example.cloudcustomerservice.rag.rerank.RerankTrace;
import java.util.*;
import org.slf4j.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * 共享的真实多路检索与合并边界：每路都用服务器过滤，全部成功才向最终模型发布候选。
 * 本章在请求线程顺序执行，最多六路，避免默认线程池泄漏和请求失败后后台继续发起收费调用。
 */
public final class MultiQueryRetrieval {
    private static final Logger log = LoggerFactory.getLogger(MultiQueryRetrieval.class);
    private final VectorStoreDocumentRetriever single, multi;
    private final DocumentJoiner joiner;
    private final VectorStore store;
    private com.example.cloudcustomerservice.knowledge.search.HybridKnowledgeRetriever hybrid;

    /** 单路 Top 5、多路每路 Top 3，相同阈值 0.60；策略由服务端轨迹决定。 */
    public MultiQueryRetrieval(VectorStore store, DocumentJoiner joiner) {
        this.store = store;
        this.single = VectorStoreDocumentRetriever.builder().vectorStore(store).topK(5).similarityThreshold(.60).build();
        this.multi = VectorStoreDocumentRetriever.builder().vectorStore(store).topK(3).similarityThreshold(.60).build();
        this.joiner = joiner;
    }

    /** 生产装配开启混合能力；两参数构造保留前面章节的独立测试与实验语义。 */
    public MultiQueryRetrieval(VectorStore store,DocumentJoiner joiner,com.example.cloudcustomerservice.knowledge.search.HybridKnowledgeRetriever hybrid) {
        this(store,joiner);this.hybrid=hybrid;
    }

    /** 复核每一条原始命中，防止错误租户的重复 ID 在去重时被隐藏。 */
    public List<Document> retrieve(Query query) {
        var expansion = trace(query);
        if (expansion.failed()) throw new IllegalStateException("Earlier retrieval failed");
        var transformation = (QueryTransformationTrace) query.context().get(QueryTransformationTrace.KEY);
        if (transformation != null && transformation.clarificationRequired()) return List.of();
        if(Boolean.TRUE.equals(query.context().get(com.example.cloudcustomerservice.knowledge.search.HybridKnowledgeRetriever.ENABLED))
                && com.example.cloudcustomerservice.knowledge.search.BusinessIdentifierExtractor.requiresTool(query.text()))
            throw new com.example.cloudcustomerservice.knowledge.search.HybridKnowledgeRetriever.BusinessToolRequiredException();
        long started = System.nanoTime();
        // 旧字段只代表首路；完整多路查询从 expansion.retrievals 获取。
        if (transformation != null && transformation.retrievalQuery() == null) transformation.searched(query.text());
        try {
            VectorStoreDocumentRetriever selected = expansion.topK() == 3 ? multi : single;
            // 新正式链和第十三章实验宽召回；第十二章历史实验保持原有 3/5、0.60 对照语义。
            if(query.context().get(RerankTrace.KEY) instanceof RerankTrace r) {
                expansion.useTopK(r.options().perQueryTopK());
                selected=VectorStoreDocumentRetriever.builder().vectorStore(store).topK(expansion.topK()).similarityThreshold(.45).build();
            }
            List<Document> documents = selected.retrieve(query);
            if (documents == null || documents.size() > expansion.topK()) throw new IllegalStateException("Invalid retrieval count");
            if (EvidenceRequiredAdvisor.validateDocuments(documents, query.context().get(CustomerAdvisorContextKeys.TENANT_ID)) == 0)
                documents = List.of();
            expansion.retrieved(query.text(), "COMPLETED", elapsed(started), documents);
            return documents;
        } catch (RuntimeException ex) {
            expansion.retrieved(query.text(), "FAILED", elapsed(started), List.of());
            throw ex;
        }
    }

    /**
     * RAA 1.1.2 收集结果用 HashMap，先按计划顺序恢复 LinkedHashMap 再交给官方 Joiner。
     * 因而同 ID 保留“计划中最早命中的路”的分数，而不是最大分数；这仍然不是全局相关性评分。
     */
    public List<Document> join(Map<Query, List<List<Document>>> results) {
        if (results.isEmpty()) return List.of();
        Query first = results.keySet().iterator().next();
        QueryExpansionTrace trace = trace(first);
        Map<Query, List<List<Document>>> ordered = new LinkedHashMap<>();
        results.entrySet().stream().sorted(Comparator.comparingInt(e -> trace.queries().indexOf(e.getKey().text())))
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));
        List<Document> joined = joiner.join(ordered);
        trace.joined(joined);
        // 在向量多路合并后执行一次精确/词法召回；第十二、十三章对照实验仍保持向量候选语义。
        if(hybrid!=null && Boolean.TRUE.equals(first.context().get(com.example.cloudcustomerservice.knowledge.search.HybridKnowledgeRetriever.ENABLED))) {
            joined=hybrid.combine((String)first.context().get(CustomerAdvisorContextKeys.TENANT_ID),
                    trace.snapshot().transformedQuery(),joined,24).fused();
        }
        var result = trace.snapshot();
        log.info("[MULTI QUERY RETRIEVAL] requestId={} routes={} raw={} joined={} duplicates={} contextCharacters={} contextTokensEstimated={}",
                first.context().get(CustomerAdvisorContextKeys.REQUEST_ID), result.retrievals().size(), result.rawDocumentCount(),
                result.joinedDocumentCount(), result.duplicateDocumentCount(), result.contextCharacters(), result.contextTokensEstimated());
        return joined;
    }

    /** 实验直接复用同一组回调，串行失败即停止，绝不把已完成的部分证据冒充完整检索结果。 */
    public List<Document> searchAndJoin(List<Query> queries) {
        Map<Query, List<List<Document>>> results = new LinkedHashMap<>();
        for (Query query : queries) results.put(query, List.of(retrieve(query)));
        return join(results);
    }

    /** 没有服务端轨迹的请求属于装配错误，不偷偷启用默认查询范围。 */
    private QueryExpansionTrace trace(Query query) {
        if (!(query.context().get(QueryExpansionTrace.KEY) instanceof QueryExpansionTrace trace))
            throw new IllegalStateException("Missing expansion context");
        return trace;
    }
    /** 单调时钟记录单路 Embedding + 数据库整体耗时。 */
    private long elapsed(long started) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
}
