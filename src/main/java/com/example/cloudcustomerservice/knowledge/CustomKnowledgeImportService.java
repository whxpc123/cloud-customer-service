package com.example.cloudcustomerservice.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("local & knowledge")
public class CustomKnowledgeImportService {
    private static final Logger log = LoggerFactory.getLogger(CustomKnowledgeImportService.class);
    private final VectorStore store;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public CustomKnowledgeImportService(VectorStore store, JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.store = store;
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    public CustomKnowledgeImportResult importText(String sourceName, String input) {
        String name = sourceName == null ? "" : Normalizer.normalize(sourceName.strip(), Normalizer.Form.NFC);
        if (name.isBlank() || name.length() > 120 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("资料名称须为 1–120 字符，不能包含控制字符");
        }
        String text = KnowledgeDocumentReader.normalize(input);
        String tenant = LocalKnowledgeDocuments.TENANT_ID;
        String sourceId = "upload-" + uuid(tenant + "|" + name);
        String version = digest(text);
        var documents = new ArrayList<Document>();
        for (int start = 0, chunk = 1; start < text.length(); chunk++) {
            int end = Math.min(start + 1000, text.length());
            // 不在 UTF-16 代理对中间切断字符。
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            documents.add(new Document(uuid(sourceId + "|" + chunk), text.substring(start, end), Map.ofEntries(
                    Map.entry("tenantId", tenant), Map.entry("knowledgeBase", "after-sales"),
                    Map.entry("sourceId", sourceId), Map.entry("sourceName", name), Map.entry("sourceVersion", version),
                    Map.entry("chunkIndex", chunk), Map.entry("category", "UPLOADED_DOCUMENT"),
                    Map.entry("status", "PUBLISHED"), Map.entry("language", "zh-CN"),
                    Map.entry("embeddingModel", "text-embedding-v4"), Map.entry("embeddingDimensions", 1024),
                    Map.entry("embeddingProfileVersion", "1"), Map.entry("chunkingVersion", "fixed-1000-v1"))));
            start = end;
        }
        try {
            transaction.executeWithoutResult(status -> {
                // 同一租户/资料的并发导入顺序执行；锁随事务完成释放。
                jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", sourceId);
                store.add(documents);
                var f = new FilterExpressionBuilder();
                // 新内容全部写入后才清理旧版本尾块；任何失败均回滚整个替换。
                store.delete(f.and(f.and(f.eq("tenantId", tenant), f.eq("sourceId", sourceId)),
                        f.ne("sourceVersion", version)).build());
            });
            log.info("[KNOWLEDGE IMPORT] mode=custom chunks={} characters={}", documents.size(), text.length());
            return new CustomKnowledgeImportResult(sourceId, name, version, documents.size(), text.length());
        } catch (RuntimeException ex) {
            log.warn("[KNOWLEDGE ERROR] operation=custom-import errorType={}", ex.getClass().getSimpleName());
            throw new KnowledgeUnavailableException();
        }
    }

    private static String uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
