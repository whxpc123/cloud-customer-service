package com.example.cloudcustomerservice.embedding;

import java.util.List;
public record SemanticSearchRequest(String query, List<String> candidates) { }
