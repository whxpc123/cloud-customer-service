package com.example.cloudcustomerservice.knowledge;

/**
 * 知识检索参数，服务端另行指定租户和知识范围。
 *
 * @param query 非空搜索问题，最多 2000 个 UTF-16 代码单元
 * @param topK 最多返回条数，1～10；null 使用 5
 * @param threshold 最低相似度阈值，0～1；null 使用 0.60
 */
public record KnowledgeSearchRequest(String query, Integer topK, Double threshold) { }
