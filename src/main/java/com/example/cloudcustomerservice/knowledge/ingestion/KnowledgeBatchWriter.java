package com.example.cloudcustomerservice.knowledge.ingestion;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 将已生成的向量与知识块在同一数据库事务中发布。
 * 先 upsert 新块，再删除该租户该来源不再保留的旧块；任一步失败都会回滚。
 * 远程模型调用不在本类中执行，避免网络等待长期占用数据库连接与锁。
 */
@Service
@Profile("local & knowledge")
public class KnowledgeBatchWriter {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final String table;
    /**
     * 从配置构造 schema.table 标识符，先用白名单校验再加引号。
     * SQL 占位符只能绑定值，不能绑定表名，因此标识符必须独立校验。
     */
    public KnowledgeBatchWriter(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper json, PgVectorStoreProperties properties) {
        this.jdbc=jdbc;this.transaction=transaction;this.json=json;
        for(String name:List.of(properties.getSchemaName(),properties.getTableName()))
            if(!name.matches("[a-zA-Z_][a-zA-Z0-9_]*"))throw new IllegalArgumentException("Invalid vector table name");
        table="\""+properties.getSchemaName()+"\".\""+properties.getTableName()+"\"";
    }
    /**
     * 确认块数与向量数一致，先在事务外完成 UUID、JSON、PGvector 转换。
     * 事务内取来源级 advisory lock，写入全部新块后清理旧块；回滚保证不会发布半份资料。
     */
    public void replace(PreparedKnowledge prepared, List<float[]> vectors) {
        if(vectors.size()!=prepared.chunks().size())throw new IllegalStateException("Invalid vector count");
        var rows=new ArrayList<Object[]>();
        try {
            for(int i=0;i<vectors.size();i++) {
                Document d=prepared.chunks().get(i);
                rows.add(new Object[]{UUID.fromString(d.getId()),d.getText(),json.writeValueAsString(d.getMetadata()),new PGvector(vectors.get(i))});
            }
        } catch(java.io.IOException ex) {throw new IllegalStateException("Invalid metadata");}
        // 只有 SQL 留在事务中；同源发布互斥，避免两个版本的 upsert/delete 交错。
        transaction.executeWithoutResult(status->{
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",prepared.source().sourceId());
            jdbc.batchUpdate("INSERT INTO "+table+" (id,content,metadata,embedding) VALUES (?,?,?::json,?) " +
                    "ON CONFLICT(id) DO UPDATE SET content=EXCLUDED.content,metadata=EXCLUDED.metadata,embedding=EXCLUDED.embedding",rows);
            removeOldChunks(prepared);
        });
    }
    /**
     * 删除同租户、同来源中不属于本次块 ID 集合的旧记录，其他来源不受影响。
     * 由 replace 在同一事务内调用；独立调用不会自动获得整批发布的事务与来源锁。
     */
    public void removeOldChunks(PreparedKnowledge prepared) {
        String placeholders=String.join(",", Collections.nCopies(prepared.chunks().size(),"?::uuid"));
        var args=new ArrayList<Object>();args.add(prepared.source().tenantId());args.add(prepared.source().sourceId());
        prepared.chunks().forEach(d->args.add(d.getId()));
        jdbc.update("DELETE FROM "+table+" WHERE metadata->>'tenantId'=? AND metadata->>'sourceId'=? AND id NOT IN ("+placeholders+")",args.toArray());
    }
}
