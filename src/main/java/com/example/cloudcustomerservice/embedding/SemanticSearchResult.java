package com.example.cloudcustomerservice.embedding;

import java.util.List;
public record SemanticSearchResult(int dimensions, List<SemanticMatch> matches) {
    public SemanticSearchResult { matches = List.copyOf(matches); }
}
