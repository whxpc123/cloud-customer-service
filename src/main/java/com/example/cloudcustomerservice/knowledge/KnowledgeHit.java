package com.example.cloudcustomerservice.knowledge;

public record KnowledgeHit(String documentId, String content, double score, String sourceId, String sourceName, String sourceVersion, int chunkIndex, String category) { }
