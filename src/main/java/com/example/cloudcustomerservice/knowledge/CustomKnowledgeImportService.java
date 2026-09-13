package com.example.cloudcustomerservice.knowledge;

import com.example.cloudcustomerservice.knowledge.ingestion.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@Profile("local & knowledge")
public class CustomKnowledgeImportService {
    private static final Logger log=LoggerFactory.getLogger(CustomKnowledgeImportService.class);
    private final KnowledgePreparationService preparation;
    private final KnowledgeEmbeddingModel embeddings;
    private final KnowledgeBatchWriter writer;
    public CustomKnowledgeImportService(KnowledgePreparationService preparation, EmbeddingModel model, KnowledgeBatchWriter writer) {
        this.preparation=preparation;this.embeddings=new KnowledgeEmbeddingModel(model);this.writer=writer;
    }
    public CustomKnowledgeImportResult importText(String name,String input) {
        return importPrepared(preparation.text(name,null,input,ChunkingOptions.defaults()));
    }
    public CustomKnowledgeImportResult importFile(String name,MultipartFile file) {
        return importPrepared(preparation.file(name,null,file,ChunkingOptions.defaults()));
    }
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
