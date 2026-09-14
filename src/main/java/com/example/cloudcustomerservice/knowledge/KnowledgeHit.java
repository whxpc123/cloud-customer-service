package com.example.cloudcustomerservice.knowledge;

/**
 * 带来源信息的向量命中，用于检索页以及第九章证据构造。
 *
 * @param documentId 数据库中知识块的稳定 UUID
 * @param content 完整知识块正文
 * @param score 向量相似度分数，不是事实可信度
 * @param sourceId 原资料来源 ID
 * @param sourceName 资料显示名称
 * @param sourceVersion 资料版本标签
 * @param chunkIndex 知识块序号，上传流程从 1 开始；缺失元数据时为 0
 * @param category 业务分类或 UPLOADED_DOCUMENT
 * @param metadata 检索层复制的来源元数据，包括页号、切分和向量配置
 */
public record KnowledgeHit(String documentId, String content, double score, String sourceId, String sourceName, String sourceVersion, int chunkIndex, String category, java.util.Map<String,Object> metadata) { }
