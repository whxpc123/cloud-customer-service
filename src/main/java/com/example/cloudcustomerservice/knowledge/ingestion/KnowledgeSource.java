package com.example.cloudcustomerservice.knowledge.ingestion;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.UUID;
import com.example.cloudcustomerservice.knowledge.LocalKnowledgeDocuments;

/** 身份和知识库在服务器确定；不接受客户端 Resource URL 或文件系统路径。 */
public record KnowledgeSource(String sourceName, String sourceVersion, String fileName, String fileType) {
    public KnowledgeSource {
        sourceName = sourceName == null ? "" : Normalizer.normalize(sourceName.strip(), Normalizer.Form.NFC);
        sourceVersion = sourceVersion == null || sourceVersion.isBlank() ? "1.0" : sourceVersion.strip();
        if (sourceName.isBlank() || sourceName.length() > 120 || sourceName.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("资料名称须为 1–120 字符，不能包含控制字符");
        if (sourceVersion.length() > 60 || sourceVersion.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("资料版本不能超过 60 字符或包含控制字符");
    }
    public String tenantId() { return LocalKnowledgeDocuments.TENANT_ID; }
    public String sourceId() {
        return "upload-" + UUID.nameUUIDFromBytes((tenantId() + "|" + sourceName).getBytes(StandardCharsets.UTF_8));
    }
}
