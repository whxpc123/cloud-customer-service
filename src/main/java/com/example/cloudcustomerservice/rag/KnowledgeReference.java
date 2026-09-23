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
 * @param score 检索相似度，不是可信度
 * @param content 本次送给模型的完整知识块正文
 */
public record KnowledgeReference(String documentId, String sourceId, String sourceName,
        String sourceVersion, int chunkIndex, String category, double score, String content) {
    /** 统一正式回答与实验的展示契约；只接受已经过范围与分数校验的文档。 */
    public static KnowledgeReference from(org.springframework.ai.document.Document d) {
        var m = d.getMetadata();
        return new KnowledgeReference(d.getId(), text(m.get("sourceId")),
                text(m.getOrDefault("sourceName", m.get("sourceId"))), text(m.get("sourceVersion")),
                m.get("chunkIndex") instanceof Number n ? n.intValue() : 0,
                text(m.get("category")), d.getScore(), d.getText());
    }
    /** 旧课程数据可能没有展示元数据，空值用空串而不是字符串 null。 */
    private static String text(Object value) { return value == null ? "" : value.toString(); }
}
