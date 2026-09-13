package com.example.cloudcustomerservice.knowledge;

public record KnowledgeSearchRequest(String query, Integer topK, Double threshold) { }
