package com.example.cloudcustomerservice.knowledge;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@Profile("local & knowledge")
public class KnowledgeVectorConfiguration {
    @Bean
    @DependsOn("flywayInitializer")
    public PgVectorStore vectorStore(JdbcTemplate jdbc, EmbeddingModel embeddingModel, PgVectorStoreProperties properties) {
        // Flyway 先建表，PgVectorStore 再验证；没有第二个 EmbeddingModel Bean，保持前六章注入不变。
        return PgVectorStore.builder(jdbc, new KnowledgeEmbeddingModel(embeddingModel))
                .schemaName(properties.getSchemaName()).vectorTableName(properties.getTableName())
                .dimensions(1024).distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW).initializeSchema(false)
                .vectorTableValidationsEnabled(true).maxDocumentBatchSize(10).build();
    }
}
