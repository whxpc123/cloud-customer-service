package com.example.cloudcustomerservice.knowledge;

import com.example.cloudcustomerservice.knowledge.ingestion.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 连接本地文档准备、远程向量化和数据库批量发布三个步骤。
 * 只有全部向量生成成功后才开始数据库事务，同一来源旧块的替换由 writer 原子完成。
 */
@Service
@Profile("local & knowledge")
public class CustomKnowledgeImportService {
    private static final Logger log=LoggerFactory.getLogger(CustomKnowledgeImportService.class);
    private final KnowledgePreparationService preparation;
    private final KnowledgeEmbeddingModel embeddings;
    private final KnowledgeBatchWriter writer;
    /**
     * 复用已有 EmbeddingModel 创建知识角色装饰器，不注册第二个模型 Bean 影响前六章。
     */
    public CustomKnowledgeImportService(KnowledgePreparationService preparation, EmbeddingModel model, KnowledgeBatchWriter writer) {
        this.preparation=preparation;this.embeddings=new KnowledgeEmbeddingModel(model);this.writer=writer;
    }
    /**
     * 粘贴导入的兼容流程：按默认 500 Token 准备后立即向量化、发布。
     */
    public CustomKnowledgeImportResult importText(String name,String input) {
        return importPrepared(preparation.text(name,null,input,ChunkingOptions.defaults()));
    }
    /**
     * 文件导入的兼容流程：统一解析及切分，不在控制器另做一套读取实现。
     */
    public CustomKnowledgeImportResult importFile(String name,MultipartFile file) {
        return importPrepared(preparation.file(name,null,file,ChunkingOptions.defaults()));
    }
    /**
     * 导入已经预览确认的块，避免确认时重新解析导致内容变化。
     * 所有远程向量请求先完成，再调用 writer 的短事务；异常隐藏内部细节并统一报告不可用。
     */
    public CustomKnowledgeImportResult importPrepared(PreparedKnowledge prepared) {
        try {
            // 必须先在事务外完成远程调用；JdbcTemplate 写入阶段不再调用模型。
            var vectors=embeddings.embed(prepared.chunks(),null,null);
            writer.replace(prepared,vectors);
            log.info("[KNOWLEDGE IMPORT] mode=etl chunks={} characters={}",prepared.chunks().size(),prepared.totalCharacters());
            return new CustomKnowledgeImportResult(prepared.source().sourceId(),prepared.source().sourceName(),prepared.source().sourceVersion(),
                    prepared.chunks().size(),prepared.totalCharacters());
        } catch(RuntimeException ex) {
            log.warn("[KNOWLEDGE ERROR] operation=etl-import errorType={}",ex.getClass().getSimpleName());
            throw new KnowledgeUnavailableException();
        }
    }
}
