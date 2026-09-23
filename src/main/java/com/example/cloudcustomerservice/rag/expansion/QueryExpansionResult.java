package com.example.cloudcustomerservice.rag.expansion;

import com.example.cloudcustomerservice.rag.KnowledgeReference;
import java.util.List;

/**
 * 多路检索的真实轨迹，不宣称查询数量等于子问题数量或证据覆盖率。
 * contextTokensEstimated 是字符启发式估算，不是 Qwen 分词结果或账单用量。
 * joinedDocumentCount 为实际合并成功数量；失败时不发布半组候选作为最终证据。
 */
public record QueryExpansionResult(String originalQuery, String transformedQuery, ExpansionMode mode,
        String status, int requestedVariants, boolean includeOriginalRequested, boolean originalIncluded,
        List<String> queries, int rejectedVariants, long expansionDurationMs, int perQueryTopK,
        List<Branch> retrievals, String retrievalStatus, int rawDocumentCount, int joinedDocumentCount,
        int duplicateDocumentCount, int contextCharacters, int contextTokensEstimated,
        List<KnowledgeReference> joinedDocuments) {
    /** 防止异步序列化或页面保存受到集合后续修改影响。 */
    public QueryExpansionResult {
        queries = List.copyOf(queries);
        retrievals = List.copyOf(retrievals);
        joinedDocuments = List.copyOf(joinedDocuments);
    }

    /** 单路命中对应它自己的查询；不同路的分数不能直接解释为对总问题的重要性。 */
    public record Branch(int index, String query, String status, long durationMs, List<KnowledgeReference> documents) {
        /** 保存命中快照，不返回内部可变列表。 */
        public Branch { documents = List.copyOf(documents); }
    }
}
