package com.example.cloudcustomerservice.embedding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

/**
 * 第六章的语义计算流程：先验证所有文本，再批量向量化并计算余弦相似度。
 * 排序只是候选间的相关性比较，分数不表示答案正确率，否定句也可能高度相似。
 */
@Service
public class SemanticSimilarityService {
    private final TextEmbeddingService embeddings;
    /**
     * 注入文本向量适配服务，比较和排序统一经过同一套输入与响应验证。
     */
    public SemanticSimilarityService(TextEmbeddingService embeddings) { this.embeddings = embeddings; }

    /**
     * 两条输入验证通过后作为同一批向量化，避免额外探测维度的远程请求。
     */
    public SimilarityResult compare(String left, String right) {
        TextEmbeddingService.validated(left, "left");
        TextEmbeddingService.validated(right, "right");
        var vectors = embeddings.embedAll(List.of(left, right));
        return new SimilarityResult(vectors.get(0).length, VectorMath.cosineSimilarity(vectors.get(0), vectors.get(1)));
    }

    /**
     * 把问题放在输入下标 0，候选 i 对应向量 i+1，再按分数降序排序。
     * 最多 20 条候选；有序流的稳定排序保留同分候选原顺序。
     */
    public SemanticSearchResult rank(String query, List<String> candidates) {
        TextEmbeddingService.validated(query, "query");
        if (candidates == null || candidates.isEmpty() || candidates.size() > 20) {
            throw new IllegalArgumentException("Expected 1 to 20 candidates");
        }
        candidates.forEach(text -> TextEmbeddingService.validated(text, "candidate"));
        var inputs = new ArrayList<String>(); inputs.add(query); inputs.addAll(candidates);
        var vectors = embeddings.embedAll(inputs);
        var matches = IntStream.range(0, candidates.size())
                .mapToObj(i -> new SemanticMatch(inputs.get(i + 1), VectorMath.cosineSimilarity(vectors.get(0), vectors.get(i + 1))))
                .sorted(Comparator.comparingDouble(SemanticMatch::score).reversed()).toList();
        return new SemanticSearchResult(vectors.get(0).length, matches);
    }
}
