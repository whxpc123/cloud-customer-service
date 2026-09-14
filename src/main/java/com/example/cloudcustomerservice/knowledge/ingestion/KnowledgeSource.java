package com.example.cloudcustomerservice.knowledge.ingestion;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.UUID;
import com.example.cloudcustomerservice.knowledge.LocalKnowledgeDocuments;

/**
 * 服务端管理的上传来源；名称按 NFC 规范化后用于确定来源 ID。
 * 仅接收文件字节和显示名称，不允许客户端指定读取 URL 或本机路径。
 *
 * @param sourceName 1～120 字符资料名；同租户同名资料视为同一来源
 * @param sourceVersion 最长 60 字符的版本说明，空值默认 1.0
 * @param fileName 入口校验后的原始文件名或粘贴正文占位文件名
 * @param fileType ReaderFactory 使用的 TEXT、MARKDOWN、PDF、DOCX 或 PPTX 类型
 */
public record KnowledgeSource(String sourceName, String sourceVersion, String fileName, String fileType) {
    /**
     * 名称去首尾空白并做 Unicode NFC 规范化，减少视觉相同但编码不同造成的重复来源。
     * 版本空值回落 1.0；名称和版本拒绝控制字符，避免异常显示与元数据污染。
     */
    public KnowledgeSource {
        sourceName = sourceName == null ? "" : Normalizer.normalize(sourceName.strip(), Normalizer.Form.NFC);
        sourceVersion = sourceVersion == null || sourceVersion.isBlank() ? "1.0" : sourceVersion.strip();
        if (sourceName.isBlank() || sourceName.length() > 120 || sourceName.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("资料名称须为 1–120 字符，不能包含控制字符");
        if (sourceVersion.length() > 60 || sourceVersion.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("资料版本不能超过 60 字符或包含控制字符");
    }
    /**
     * 由服务端返回固定演示租户，客户端提交的正文与元数据不能覆盖。
     */
    public String tenantId() { return LocalKnowledgeDocuments.TENANT_ID; }
    /**
     * 同租户、同规范化名称得到同一 ID；版本未参与来源 ID，所以更新版本仍整体替换旧块。
     */
    public String sourceId() {
        return "upload-" + UUID.nameUUIDFromBytes((tenantId() + "|" + sourceName).getBytes(StandardCharsets.UTF_8));
    }
}
