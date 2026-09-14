package com.example.cloudcustomerservice.knowledge;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 第七章 PgVectorStore 的显式配置，仅在 local 与 knowledge 同时启用时生效。
 * Flyway 负责表结构，向量存储负责校验与检索；不再由存储自行创建另一套表。
 */
@Configuration
@Profile("local & knowledge")
public class KnowledgeVectorConfiguration {
    /**
     * 等 Flyway 建表后构造存储，使用余弦距离和 HNSW 索引配置。
     * 固定 1024 维且关闭自动建表，同时启用表校验；模型、列类型和距离度量必须一致。
     */
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
