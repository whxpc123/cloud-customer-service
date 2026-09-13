package com.example.cloudcustomerservice.knowledge;

import java.util.List;
public record KnowledgeSearchResult(String query, List<KnowledgeHit> hits) {
    public KnowledgeSearchResult { hits = List.copyOf(hits); }
}
