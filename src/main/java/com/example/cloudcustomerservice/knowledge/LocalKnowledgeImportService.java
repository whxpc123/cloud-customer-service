package com.example.cloudcustomerservice.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("local & knowledge")
public class LocalKnowledgeImportService {
    private static final Logger log = LoggerFactory.getLogger(LocalKnowledgeImportService.class);
    private final VectorStore vectorStore;
    private final LocalKnowledgeDocuments documents;
    public LocalKnowledgeImportService(VectorStore vectorStore, LocalKnowledgeDocuments documents) {
        this.vectorStore = vectorStore; this.documents = documents;
    }
    public synchronized int importDocuments() {
        var batch = documents.documents();
        try {
            // PgVectorStore 1.1.2 的 add 使用 ON CONFLICT UPDATE，先完成向量化，再写数据库。
            // 不预先 delete，远程向量化失败时原有四条知识仍在。不宣称是完整的版本发布事务。
            vectorStore.add(batch);
            log.info("[KNOWLEDGE IMPORT] documents={} mode=upsert", batch.size());
            return batch.size();
        } catch (RuntimeException ex) {
            log.warn("[KNOWLEDGE ERROR] operation=import errorType={}", ex.getClass().getSimpleName());
            throw new KnowledgeUnavailableException();
        }
    }
}
