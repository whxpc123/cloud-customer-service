package com.example.cloudcustomerservice.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 将课程的固定文档向量化后交给 VectorStore 执行 upsert。
 * synchronized 只限制本实例的重复导入，不是跨进程锁；不承担上传资料的整批版本替换。
 */
@Service
@Profile("local & knowledge")
public class LocalKnowledgeImportService {
    private static final Logger log = LoggerFactory.getLogger(LocalKnowledgeImportService.class);
    private final VectorStore vectorStore;
    private final LocalKnowledgeDocuments documents;
    /**
     * 注入样例文档提供者和向量存储，便于测试替换存储而保留真实样例元数据。
     */
    public LocalKnowledgeImportService(VectorStore vectorStore, LocalKnowledgeDocuments documents) {
        this.vectorStore = vectorStore; this.documents = documents;
    }
    /**
     * 同一实例内串行导入样例；直接调用 add，不先删除旧行。
     * 模型请求失败不会先清空旧样例，但此旧章节流程不是第八章的整来源替换事务。
     */
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
