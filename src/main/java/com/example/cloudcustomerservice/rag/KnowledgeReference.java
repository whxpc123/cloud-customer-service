package com.example.cloudcustomerservice.rag;

/**
 * 由 Java 确定的本次证据快照，响应来源与送给模型的内容保持一致。
 * 这些来源不是模型自动生成的引用，也没有经过逐句事实支持校验。
 *
 * @param documentId 知识块 UUID
 * @param sourceId 原资料 ID
 * @param sourceName 资料名称
 * @param sourceVersion 来源版本
 * @param chunkIndex 块序号
 * @param category 业务分类
 * @param score 当前阶段分数；重排成功时是 rerankScore，混合召回时为 RRF 分数，旧链为向量分数，不是可信度
 * @param content 本次送给模型的完整知识块正文
 */
public record KnowledgeReference(String documentId, String sourceId, String sourceName,
        String sourceVersion, int chunkIndex, String category, double score, String content,
        Double retrievalScore, Double rerankScore, Integer rerankRank, String rerankModel, Boolean rerankFallback, java.util.Map<String,Object> hybrid) {
    /** 兼容第十三章持久化评测及构造，历史快照没有混合召回信息。 */
    public KnowledgeReference(String documentId,String sourceId,String sourceName,String sourceVersion,int chunkIndex,
            String category,double score,String content,Double retrievalScore,Double rerankScore,Integer rerankRank,String rerankModel,Boolean rerankFallback) {
        this(documentId,sourceId,sourceName,sourceVersion,chunkIndex,category,score,content,retrievalScore,rerankScore,rerankRank,rerankModel,rerankFallback,java.util.Map.of());
    }
    /** 兼容前十二章和已有评测记录，旧 score 的语义仍为向量分数。 */
    public KnowledgeReference(String documentId,String sourceId,String sourceName,String sourceVersion,int chunkIndex,
            String category,double score,String content) {
        this(documentId,sourceId,sourceName,sourceVersion,chunkIndex,category,score,content,null,null,null,null,null);
    }
    /** 统一正式回答与实验的展示契约；只接受已经过范围与分数校验的文档。 */
    public static KnowledgeReference from(org.springframework.ai.document.Document d) {
        var m = d.getMetadata();
        return new KnowledgeReference(d.getId(), text(m.get("sourceId")),
                text(m.getOrDefault("sourceName", m.get("sourceId"))), text(m.get("sourceVersion")),
                m.get("chunkIndex") instanceof Number n ? n.intValue() : 0,
                text(m.get("category")), d.getScore(), d.getText(),
                m.get("retrievalScore") instanceof Number n ? n.doubleValue():d.getScore(),
                m.get("rerankScore") instanceof Number n ? n.doubleValue():null,
                m.get("rerankRank") instanceof Number n ? n.intValue():null,
                m.get("rerankModel") instanceof String v ? v:null,
                m.get("rerankFallback") instanceof Boolean v ? v:null, hybridMetadata(m));
    }
    /** 只暴露可解释性字段，不将整份库元数据或内部上下文返回给页面。 */
    private static java.util.Map<String,Object> hybridMetadata(java.util.Map<String,Object> metadata) {
        var values=new java.util.LinkedHashMap<String,Object>();
        for(String key:java.util.List.of("retrievalSources","exactMatch","vectorRank","keywordRank","vectorScore","keywordScore","rrfScore"))
            if(metadata.containsKey(key))values.put(key,metadata.get(key));
        return java.util.Map.copyOf(values);
    }
    /** 旧课程数据可能没有展示元数据，空值用空串而不是字符串 null。 */
    private static String text(Object value) { return value == null ? "" : value.toString(); }
}
