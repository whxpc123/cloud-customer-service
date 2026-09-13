package com.example.cloudcustomerservice.rag;

/** Java 记录本次提供给模型的证据，不代表逐句引用校验。 */
public record KnowledgeReference(String documentId, String sourceId, String sourceName,
        String sourceVersion, int chunkIndex, String category, double score, String content) { }
