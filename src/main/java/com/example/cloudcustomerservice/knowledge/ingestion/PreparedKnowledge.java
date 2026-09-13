package com.example.cloudcustomerservice.knowledge.ingestion;

import java.util.List;
import org.springframework.ai.document.Document;

public record PreparedKnowledge(KnowledgeSource source, ChunkingOptions options,
        int extractedDocuments, int normalizedDocuments, int rawCharacters, int totalCharacters,
        List<Document> chunks, List<String> warnings) {
    public PreparedKnowledge { chunks = List.copyOf(chunks); warnings = List.copyOf(warnings); }
}
