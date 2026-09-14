package com.example.cloudcustomerservice.knowledge.ingestion;

import java.util.List;
import org.springframework.ai.document.Document;

/**
 * 读取、清理和切分后的准备结果，尚未调用向量模型或写数据库。
 * 列表复制用于防止增删；内部 Document 仍是可变对象，后续步骤应按只读使用。
 *
 * @param source 已确定的来源与租户
 * @param options 本次使用的切分和 PDF 清理参数
 * @param extractedDocuments Reader 返回的原始段或页数
 * @param normalizedDocuments 清理并过滤空正文后的段数
 * @param rawCharacters 清理前正文的 UTF-16 长度总和
 * @param totalCharacters 最终块正文长度总和，包含附加标题
 * @param chunks 带稳定 ID 和元数据的待入库知识块
 * @param warnings 提取与切分过程中需要人工核对的提示
 */
public record PreparedKnowledge(KnowledgeSource source, ChunkingOptions options,
        int extractedDocuments, int normalizedDocuments, int rawCharacters, int totalCharacters,
        List<Document> chunks, List<String> warnings) {
    /**
     * 复制块列表和警告列表，保证预览期间集合成员不被外部增删；不是 Document 的深拷贝。
     */
    public PreparedKnowledge { chunks = List.copyOf(chunks); warnings = List.copyOf(warnings); }
}
