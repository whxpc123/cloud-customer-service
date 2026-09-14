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
        String sourceVersion, int chunkIndex, String category, double score, String content) { }
