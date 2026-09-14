package com.example.cloudcustomerservice.knowledge;

/**
 * 课程样例导入的简短响应。
 *
 * @param importedDocuments 本次 upsert 的样例块数，不代表整个数据库总量
 */
public record KnowledgeImportResponse(int importedDocuments) { }
