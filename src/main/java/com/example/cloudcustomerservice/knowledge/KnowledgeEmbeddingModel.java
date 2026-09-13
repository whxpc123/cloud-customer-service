package com.example.cloudcustomerservice.knowledge;

import java.util.ArrayList;
import java.util.List;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;

/** 仅供知识库：正文入库 document，搜索 query；不改第六章同角色实验模型。 */
public final class KnowledgeEmbeddingModel implements EmbeddingModel {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeEmbeddingModel.class);
    private final EmbeddingModel delegate;
    public KnowledgeEmbeddingModel(EmbeddingModel delegate) { this.delegate = delegate; }
    @Override public EmbeddingResponse call(EmbeddingRequest request) { return delegate.call(request); }
    @Override public float[] embed(Document document) { return vectors(List.of(document.getText()), "document").get(0); }
    @Override public float[] embed(String query) { return vectors(List.of(query), "query").get(0); }
    @Override public int dimensions() { return 1024; }
    @Override public List<float[]> embed(List<Document> documents, EmbeddingOptions options, BatchingStrategy batchingStrategy) {
        return vectors(documents.stream().map(Document::getText).toList(), "document");
    }
    private List<float[]> vectors(List<String> texts, String role) {
        var results = new ArrayList<float[]>();
        for (int offset=0; offset<texts.size(); offset+=10) {
            var batch = texts.subList(offset, Math.min(offset+10, texts.size()));
            var options = DashScopeEmbeddingOptions.builder().model("text-embedding-v4").dimensions(1024).textType(role).build();
            var response = delegate.call(new EmbeddingRequest(batch, options));
            if (response == null || response.getResults().size() != batch.size()) throw new IllegalStateException("Invalid embedding count");
            float[][] ordered = new float[batch.size()][];
            for (var embedding : response.getResults()) {
                int index = embedding.getIndex();
                float[] vector = embedding.getOutput();
                if (index<0 || index>=ordered.length || ordered[index]!=null || vector==null || vector.length!=1024) throw new IllegalStateException("Invalid embedding shape");
                boolean nonzero=false;
                for (float v:vector) { if (!Float.isFinite(v)) throw new IllegalStateException("Invalid vector value"); nonzero |= v!=0; }
                if (!nonzero) throw new IllegalStateException("Zero vector");
                ordered[index]=vector;
            }
            results.addAll(List.of(ordered));
        }
        log.info("[KNOWLEDGE EMBEDDING] role={} texts={} dimensions=1024", role, texts.size());
        return results;
    }
}
