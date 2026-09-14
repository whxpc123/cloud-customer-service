package com.example.cloudcustomerservice.knowledge;

import java.util.ArrayList;
import java.util.List;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;

/**
 * 知识库专用的 EmbeddingModel 装饰器：入库正文使用 document 角色，查询使用 query 角色。
 * 固定 text-embedding-v4 的 1024 维，与数据库列和检索配置一致；不向量化元数据。
 */
public final class KnowledgeEmbeddingModel implements EmbeddingModel {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeEmbeddingModel.class);
    private final EmbeddingModel delegate;
    /**
     * 保存原向量模型作为委托，仅在知识专用 embed 路径覆盖角色和批次约定。
     */
    public KnowledgeEmbeddingModel(EmbeddingModel delegate) { this.delegate = delegate; }
    /**
     * 底层接口原样透传；知识角色处理发生在下面各 embed 重载中。
     */
    @Override public EmbeddingResponse call(EmbeddingRequest request) { return delegate.call(request); }
    /**
     * 单文档入库仅向量化正文，明确指定 document 角色。
     */
    @Override public float[] embed(Document document) { return vectors(List.of(document.getText()), "document").get(0); }
    /**
     * 搜索问题使用 query 角色，与入库正文形成检索配对。
     */
    @Override public float[] embed(String query) { return vectors(List.of(query), "query").get(0); }
    /**
     * 直接声明固定维度，避免为了探测维度额外发送一次模型请求。
     */
    @Override public int dimensions() { return 1024; }
    /**
     * 批量入库只取每块正文；使用本适配器固定的模型、角色、维度及每批上限。
     * 传入的通用 options 和 batchingStrategy 在本章适配器中不改变这些约定。
     */
    @Override public List<float[]> embed(List<Document> documents, EmbeddingOptions options, BatchingStrategy batchingStrategy) {
        return vectors(documents.stream().map(Document::getText).toList(), "document");
    }
    /**
     * 每批最多 10 条，按响应 index 恢复原输入顺序，不假定服务商返回列表有序。
     * 拒绝越界或重复 index、维度错误、非有限值和全零向量，避免错误数据写入向量列。
     */
    private List<float[]> vectors(List<String> texts, String role) {
        var results = new ArrayList<float[]>();
        for (int offset=0; offset<texts.size(); offset+=10) {
            var batch = texts.subList(offset, Math.min(offset+10, texts.size()));
            var options = DashScopeEmbeddingOptions.builder().model("text-embedding-v4").dimensions(1024).textType(role).build();
            var response = delegate.call(new EmbeddingRequest(batch, options));
            if (response == null || response.getResults().size() != batch.size()) throw new IllegalStateException("Invalid embedding count");
            // 响应数组可能乱序；index 才是它对应本批输入的位置。
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
