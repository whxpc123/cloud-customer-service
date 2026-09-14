package com.example.cloudcustomerservice.knowledge;

/**
 * 同步粘贴导入的 JSON 契约，详细切分配置使用第八章预览接口。
 *
 * @param sourceName 资料名称，用于在当前租户内确定稳定来源 ID；同名导入替换旧内容
 * @param text 完整粘贴正文，最多 50000 个 UTF-16 代码单元
 */
public record CustomKnowledgeImportRequest(String sourceName, String text) { }
