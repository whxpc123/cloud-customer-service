package com.example.cloudcustomerservice.knowledge;

public record CustomKnowledgeImportResult(String sourceId, String sourceName, String sourceVersion,
        int importedDocuments, int characters) { }
