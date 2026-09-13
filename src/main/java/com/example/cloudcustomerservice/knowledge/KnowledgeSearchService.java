package com.example.cloudcustomerservice.knowledge;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("local & knowledge")
public class KnowledgeSearchService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchService.class);
    private final VectorStore vectorStore;
    public KnowledgeSearchService(VectorStore vectorStore) { this.vectorStore = vectorStore; }

    public KnowledgeSearchResult search(String tenantId, String query, Integer topK, Double threshold) {
        if (tenantId == null || !tenantId.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid tenantId");
        if (query == null || query.isBlank() || query.length() > 2000) throw new IllegalArgumentException("query must contain 1 to 2000 characters");
        int limit = topK == null ? 5 : topK;
        double minimum = threshold == null ? 0.60 : threshold;
        if (limit < 1 || limit > 10) throw new IllegalArgumentException("topK must be between 1 and 10");
        if (!Double.isFinite(minimum) || minimum < 0 || minimum > 1) throw new IllegalArgumentException("threshold must be between 0 and 1");
        var builder = new FilterExpressionBuilder();
        var filter = builder.and(builder.and(builder.eq("tenantId", tenantId), builder.eq("status", "PUBLISHED")),
                builder.and(builder.eq("knowledgeBase", "after-sales"), builder.eq("language", "zh-CN"))).build();
        var request = SearchRequest.builder().query(query).topK(limit).similarityThreshold(minimum).filterExpression(filter).build();
        try {
            List<Document> documents = vectorStore.similaritySearch(request);
            if (documents == null) throw new IllegalStateException("Missing search result");
            // 再次检查返回元数据，防止误配置的存储实现向接口返回跨范围知识。
            var hits = documents.stream().filter(d -> tenantId.equals(d.getMetadata().get("tenantId"))
                    && "PUBLISHED".equals(d.getMetadata().get("status"))
                    && "after-sales".equals(d.getMetadata().get("knowledgeBase"))
                    && "zh-CN".equals(d.getMetadata().get("language")))
                    .map(this::hit).toList();
            log.info("[KNOWLEDGE SEARCH] topK={} threshold={} hits={}", limit, minimum, hits.size());
            return new KnowledgeSearchResult(query, hits);
        } catch (RuntimeException ex) {
            log.warn("[KNOWLEDGE ERROR] operation=search errorType={}", ex.getClass().getSimpleName());
            throw new KnowledgeUnavailableException();
        }
    }
    private KnowledgeHit hit(Document d) {
        if (d.getScore() == null || !Double.isFinite(d.getScore())) throw new IllegalStateException("Invalid score");
        var m = d.getMetadata();
        return new KnowledgeHit(d.getId(), d.getText(), d.getScore(), String.valueOf(m.getOrDefault("sourceId", "")),
                String.valueOf(m.getOrDefault("sourceVersion", "")), m.get("chunkIndex") instanceof Number n ? n.intValue() : 0,
                String.valueOf(m.getOrDefault("category", "")));
    }
}
