package com.example.cloudcustomerservice.knowledge.management;

import com.example.cloudcustomerservice.knowledge.LocalKnowledgeDocuments;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 知识管理的真实数据库读写层：分页来源目录、完整切片、原件以及可恢复的下架操作。
 * 所有入口固定为服务器演示租户的中文售后库，不接受浏览器传入 SQL、租户或文件路径。
 */
@Service
@Profile("local & knowledge")
public class KnowledgeDocumentCatalog {
    /** 一份来源的聚合信息；旧资料原件字段和时间为 null，不能推断历史上传时间。 */
    public record Source(String sourceId, String name, String version, String fileType, String status,
            int chunks, long characters, Long bytes, String createdAt, String updatedAt, boolean hasOriginal) { }
    /** 服务端分页结果；total 是当前筛选下的来源数量，不能用当前页行数代替。 */
    public record Page(List<Source> items, long total, int page, int pageSize) { }
    /** 切片正文与元数据来自实际向量表，不返回向量本身。 */
    public record Chunk(String documentId, String content, Map<String,Object> metadata) { }
    /** 正文类型区分原始文本、Reader 提取文本和旧切片拼接。 */
    public record Detail(Source source, String preview, String previewKind, String fileName, String fileHash,
            int shortestChunk, int longestChunk, double averageChunk, List<Chunk> chunks) { }
    /** 下载体只在已授权范围内取出；控制器强制 attachment，避免执行用户 HTML。 */
    public record Download(String name, byte[] bytes) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final String table;
    private static final String TENANT = LocalKnowledgeDocuments.TENANT_ID;
    private static final String SCOPE = "metadata->>'tenantId'=? AND metadata->>'knowledgeBase'='after-sales' AND metadata->>'language'='zh-CN'";

    /** 标识符来自配置并做白名单校验，参数值一律使用 JDBC 占位符。 */
    public KnowledgeDocumentCatalog(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper json, PgVectorStoreProperties properties) {
        this.jdbc=jdbc; this.transaction=transaction; this.json=json;
        for (String name: List.of(properties.getSchemaName(),properties.getTableName()))
            if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid vector table name");
        table="\""+properties.getSchemaName()+"\".\""+properties.getTableName()+"\"";
    }

    /** 只聚合目录字段，不为列表载入原始文件和全部正文。 */
    private String grouped() {
        return """
                SELECT metadata->>'sourceId' AS source_id,
                coalesce(min(metadata->>'sourceName'),metadata->>'sourceId') AS name,
                string_agg(DISTINCT metadata->>'sourceVersion', ', ') AS version,
                coalesce(min(metadata->>'fileType'),'LEGACY') AS file_type,
                CASE WHEN bool_and(metadata->>'status'='ARCHIVED') THEN 'ARCHIVED'
                     WHEN bool_and(metadata->>'status'='PUBLISHED') THEN 'PUBLISHED' ELSE 'OTHER' END AS status,
                count(*) AS chunks, sum(length(content)) AS characters
                FROM %s WHERE %s AND metadata->>'sourceId' IS NOT NULL GROUP BY metadata->>'sourceId'
                """.formatted(table,SCOPE);
    }

    /** 根据名称/来源、状态和文件类型分页；限制搜索长度与页大小，不执行浏览器通配 SQL。 */
    public Page list(String query, String status, String type, int page, int size) {
        if (query==null || query.length()>120 || page<1 || page>100000 || size<1 || size>100
                || !Set.of("ALL","PUBLISHED","ARCHIVED","OTHER").contains(status)
                || !Set.of("ALL","TEXT","MARKDOWN","PDF","DOCX","PPTX","LEGACY").contains(type))
            throw new IllegalArgumentException("列表筛选参数不合法");
        String filter=" WHERE (?='' OR position(lower(?) in lower(s.name))>0 OR position(lower(?) in lower(s.source_id))>0)"
                +" AND (?='ALL' OR s.status=?) AND (?='ALL' OR s.file_type=?)";
        Object[] args={TENANT,query.strip(),query.strip(),query.strip(),status,status,type,type};
        Long count=jdbc.queryForObject("WITH s AS ("+grouped()+") SELECT count(*) FROM s"+filter,Long.class,args);
        Object[] listArgs={TENANT,TENANT,query.strip(),query.strip(),query.strip(),status,status,type,type,size,(page-1)*size};
        var items=jdbc.query("WITH s AS ("+grouped()+") SELECT s.*,octet_length(o.file_bytes) AS bytes,o.created_at,o.updated_at "
                +"FROM s LEFT JOIN ai.knowledge_originals o ON o.source_id=s.source_id AND o.tenant_id=?"+filter
                +" ORDER BY o.updated_at DESC NULLS LAST,s.source_id LIMIT ? OFFSET ?",(rs,row)->new Source(
                rs.getString("source_id"),rs.getString("name"),rs.getString("version"),rs.getString("file_type"),rs.getString("status"),
                rs.getInt("chunks"),rs.getLong("characters"),rs.getObject("bytes")==null?null:((Number)rs.getObject("bytes")).longValue(),
                rs.getString("created_at"),rs.getString("updated_at"),rs.getObject("bytes")!=null),listArgs);
        return new Page(items,count==null?0:count,page,size);
    }

    /** 同一只读快照中读取来源、块和原件，避免版本更新时详情混用新旧内容。 */
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Detail detail(String id) {
        validateId(id);
        List<Chunk> chunks=jdbc.query("SELECT id,content,metadata::text FROM "+table+" WHERE "+SCOPE+" AND metadata->>'sourceId'=? "
                +"ORDER BY CASE WHEN metadata->>'chunkIndex' ~ '^[0-9]{1,8}$' THEN (metadata->>'chunkIndex')::int ELSE 0 END,id",
                (rs,row)-> {
                    try { return new Chunk(rs.getString(1),rs.getString(2),json.readValue(rs.getString(3),new TypeReference<Map<String,Object>>(){})); }
                    catch (java.io.IOException ex) { throw new IllegalStateException("Invalid stored metadata",ex); }
                },TENANT,id);
        if (chunks.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"文档不存在或不属于当前知识库");
        Map<String,Object> first=chunks.get(0).metadata();
        List<Map<String,Object>> originals=jdbc.queryForList("SELECT file_name,file_type,file_hash,file_bytes,extracted_text,created_at,updated_at FROM ai.knowledge_originals WHERE tenant_id=? AND source_id=?",TENANT,id);
        Map<String,Object> original=originals.isEmpty()?Map.of():originals.get(0);
        boolean hasOriginal=!original.isEmpty();
        String state=chunks.stream().map(c->String.valueOf(c.metadata().get("status"))).distinct().reduce((a,b)->"OTHER").orElse("OTHER");
        String previewKind="CHUNKS",preview=chunks.stream().map(Chunk::content).collect(java.util.stream.Collectors.joining("\n\n"));
        byte[] bytes=hasOriginal?(byte[])original.get("file_bytes"):null;
        String type=String.valueOf(first.getOrDefault("fileType","LEGACY"));
        if (hasOriginal) {
            previewKind=Set.of("TEXT","MARKDOWN").contains(type)?"ORIGINAL_TEXT":"EXTRACTED_TEXT";
            preview=previewKind.equals("ORIGINAL_TEXT")?new String(bytes,StandardCharsets.UTF_8):(String)original.get("extracted_text");
        }
        var stats=chunks.stream().mapToInt(c->c.content().codePointCount(0,c.content().length())).summaryStatistics();
        var source=new Source(id,String.valueOf(first.getOrDefault("sourceName",id)),String.valueOf(first.getOrDefault("sourceVersion","")),type,state,
                chunks.size(),stats.getSum(),bytes==null?null:(long)bytes.length,text(original.get("created_at")),text(original.get("updated_at")),hasOriginal);
        return new Detail(source,preview,previewKind,text(original.get("file_name")),text(original.get("file_hash")),stats.getMin(),stats.getMax(),stats.getAverage(),chunks);
    }

    /** 原件下载先检查相同库范围。新件可精确下载，旧件须用导出知识正文接口。 */
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Download download(String id) {
        validateId(id);
        Long count=jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE "+SCOPE+" AND metadata->>'sourceId'=?",Long.class,TENANT,id);
        if (count==null || count==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"文档不存在");
        return jdbc.query("SELECT file_name,file_bytes FROM ai.knowledge_originals WHERE tenant_id=? AND source_id=?",
                (rs,row)->new Download(rs.getString(1),rs.getBytes(2)),TENANT,id).stream().findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"旧资料未保存原文件，可导出知识块正文"));
    }

    /**
     * 移入回收站只修改发布状态，原件和向量完整保留；恢复后重新参与检索，不调用模型。
     * 使用与整批导入相同的来源级事务锁。导入完成后执行的同名发布会重新上线该来源。
     * 只在全来源 PUBLISHED/ARCHIVED 时允许操作，不能借恢复把草稿或混合状态发布。
     */
    public void archive(String id, boolean archived) {
        validateId(id);
        transaction.executeWithoutResult(tx->{
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",id);
            var states=jdbc.queryForList("SELECT DISTINCT metadata->>'status' FROM "+table+" WHERE "+SCOPE+" AND metadata->>'sourceId'=?",String.class,TENANT,id);
            if (states.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"文档不存在");
            String expected=archived?"PUBLISHED":"ARCHIVED", target=archived?"ARCHIVED":"PUBLISHED";
            if (states.size()==1 && target.equals(states.get(0))) return;
            if (states.size()!=1 || !expected.equals(states.get(0))) throw new ResponseStatusException(HttpStatus.CONFLICT,"文档状态已变化，请刷新后操作");
            jdbc.update("UPDATE "+table+" SET metadata=jsonb_set(metadata::jsonb,'{status}',to_jsonb(?::text))::json WHERE "+SCOPE+" AND metadata->>'sourceId'=?",target,TENANT,id);
        });
    }
    /** 来源 ID 仅用于参数绑定与 URL，限制字符避免控制字符和不合法路径。 */
    private void validateId(String id) { if(id==null || !id.matches("[a-zA-Z0-9_-]{1,160}")) throw new IllegalArgumentException("文档 ID 不合法"); }
    /** 对尚未保存的历史信息保留 null，让 UI 明确显示未知。 */
    private String text(Object value) { return value==null?null:value.toString(); }
}
