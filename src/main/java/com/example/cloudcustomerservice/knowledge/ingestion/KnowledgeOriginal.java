package com.example.cloudcustomerservice.knowledge.ingestion;

/**
 * 待发布的原文件与 Reader 提取全文；预览阶段只在内存保存，整批发布成功后才持久化。
 * 字节数组双向复制，避免预览确认之后上传内容被修改；文件名、类型由 KnowledgeSource 提供。
 */
public record KnowledgeOriginal(byte[] bytes, String extractedText) {
    /** 保存独立副本，不持有调用方可变数组。 */
    public KnowledgeOriginal { bytes = bytes.clone(); }
    /** 下载/写库调用者取得副本，不得改写确认过的原件。 */
    @Override public byte[] bytes() { return bytes.clone(); }
}
