package com.example.cloudcustomerservice.knowledge;

/**
 * 导入完成后的统计与来源信息，不把向量数组传给浏览器。
 *
 * @param sourceId 服务端计算的稳定来源 ID
 * @param sourceName 规范化后的资料名称
 * @param sourceVersion 本次版本标签，同名替换不保留历史版本档案
 * @param importedDocuments 实际写入的知识块数量，不是原始文件数
 * @param characters 所有知识块正文长度之和，可能包含重复附加的标题
 */
public record CustomKnowledgeImportResult(String sourceId, String sourceName, String sourceVersion,
        int importedDocuments, int characters) { }
