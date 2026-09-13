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

/** 接收已生成的向量，短事务仅执行 SQL。检索继续使用 PgVectorStore。 */
@Service
@Profile("local & knowledge")
public class KnowledgeBatchWriter {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final String table;
    public KnowledgeBatchWriter(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper json, PgVectorStoreProperties properties) {
        this.jdbc=jdbc;this.transaction=transaction;this.json=json;
        for(String name:List.of(properties.getSchemaName(),properties.getTableName()))
            if(!name.matches("[a-zA-Z_][a-zA-Z0-9_]*"))throw new IllegalArgumentException("Invalid vector table name");
        table="\""+properties.getSchemaName()+"\".\""+properties.getTableName()+"\"";
    }
    public void replace(PreparedKnowledge prepared, List<float[]> vectors) {
        if(vectors.size()!=prepared.chunks().size())throw new IllegalStateException("Invalid vector count");
        var rows=new ArrayList<Object[]>();
        try {
            for(int i=0;i<vectors.size();i++) {
                Document d=prepared.chunks().get(i);
                rows.add(new Object[]{UUID.fromString(d.getId()),d.getText(),json.writeValueAsString(d.getMetadata()),new PGvector(vectors.get(i))});
            }
        } catch(java.io.IOException ex) {throw new IllegalStateException("Invalid metadata");}
        transaction.executeWithoutResult(status->{
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",prepared.source().sourceId());
            jdbc.batchUpdate("INSERT INTO "+table+" (id,content,metadata,embedding) VALUES (?,?,?::json,?) " +
                    "ON CONFLICT(id) DO UPDATE SET content=EXCLUDED.content,metadata=EXCLUDED.metadata,embedding=EXCLUDED.embedding",rows);
            removeOldChunks(prepared);
        });
    }
    public void removeOldChunks(PreparedKnowledge prepared) {
        String placeholders=String.join(",", Collections.nCopies(prepared.chunks().size(),"?::uuid"));
        var args=new ArrayList<Object>();args.add(prepared.source().tenantId());args.add(prepared.source().sourceId());
        prepared.chunks().forEach(d->args.add(d.getId()));
        jdbc.update("DELETE FROM "+table+" WHERE metadata->>'tenantId'=? AND metadata->>'sourceId'=? AND id NOT IN ("+placeholders+")",args.toArray());
    }
}
