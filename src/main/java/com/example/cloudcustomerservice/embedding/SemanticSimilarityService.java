package com.example.cloudcustomerservice.embedding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

@Service
public class SemanticSimilarityService {
    private final TextEmbeddingService embeddings;
    public SemanticSimilarityService(TextEmbeddingService embeddings) { this.embeddings = embeddings; }

    public SimilarityResult compare(String left, String right) {
        TextEmbeddingService.validated(left, "left");
        TextEmbeddingService.validated(right, "right");
        var vectors = embeddings.embedAll(List.of(left, right));
        return new SimilarityResult(vectors.get(0).length, VectorMath.cosineSimilarity(vectors.get(0), vectors.get(1)));
    }

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
