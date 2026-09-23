package com.example.cloudcustomerservice.rag.expansion;

import com.example.cloudcustomerservice.rag.KnowledgeReference;
import com.example.cloudcustomerservice.rag.query.QueryTransformationResult;
import java.util.List;

/** 实验不会写入聊天历史或调用最终回答模型；compare 开启才真实执行两种检索。 */
public record QueryExpansionExperiment(String requestId, String conversationId, QueryTransformationResult transformation,
        QueryExpansionResult expansion, Baseline baseline, String comparisonStatus) {
    /** 单路基线使用补全后的完整问题，Top 5；与扩展后每路 Top 3 区分展示。 */
    public record Baseline(String query, int topK, String status, long durationMs, List<KnowledgeReference> documents) {
        /** 防止实验结果发布后被内部集合修改。 */
        public Baseline { documents = List.copyOf(documents); }
    }
}
