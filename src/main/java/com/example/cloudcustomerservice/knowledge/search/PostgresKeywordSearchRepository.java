package com.example.cloudcustomerservice.knowledge.search;

import com.example.cloudcustomerservice.knowledge.KnowledgeFilterFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 参数化全文/精确检索；每条 SQL 都执行与向量检索相同的四个范围限制。 */
@Repository
@Profile("local & knowledge")
public class PostgresKeywordSearchRepository {
    private static final String SCOPE = "metadata->>'tenantId'=? AND metadata->>'status'='PUBLISHED' AND metadata->>'knowledgeBase'='after-sales' AND metadata->>'language'='zh-CN'";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public PostgresKeywordSearchRepository(JdbcTemplate jdbc, ObjectMapper json) {
        // 单独的模板配置 3 秒 statement 超时，不能更改其他业务共享 JdbcTemplate 的超时。
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(3);this.json=json;
    }
    /** simple + ts_rank_cd 是 PostgreSQL 全文检索，不宣称实现 BM25 或中文分词。 */
    public List<Document> keyword(String tenant,String query,int limit) {
        validate(tenant,query,limit);
        return jdbc.query("SELECT id,content,metadata,ts_rank_cd(search_vector,websearch_to_tsquery('simple',?)) AS score FROM ai.knowledge_vector_store WHERE "+SCOPE+
                " AND search_vector @@ websearch_to_tsquery('simple',?) ORDER BY score DESC,id LIMIT ?",this::map,query,tenant,query,limit);
    }
    /** OR 合并至多八个已识别编码；JSON 包含判断是完整值匹配，不能把 E100 当成 E1000。 */
    public List<Document> exact(String tenant,List<String> codes,int limit) {
        validate(tenant,"exact",limit);if(codes.isEmpty())return List.of();
        if(codes.size()>8 || codes.stream().anyMatch(c->!BusinessIdentifierExtractor.extract(c).equals(List.of(c)))) throw new IllegalArgumentException("Invalid codes");
        var args=new ArrayList<Object>();args.add(tenant);
        for(String code:codes) args.add("[\""+code+"\"]"); args.add(limit);
        String conditions=String.join(" OR ",Collections.nCopies(codes.size(),"(metadata::jsonb->'businessCodes') @> ?::jsonb"));
        return jdbc.query("SELECT id,content,metadata,1.0 AS score FROM ai.knowledge_vector_store WHERE "+SCOPE+" AND ("+conditions+") ORDER BY id LIMIT ?",this::map,args.toArray());
    }
    private Document map(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        try {return Document.builder().id(rs.getString("id")).text(rs.getString("content"))
                .metadata(json.readValue(rs.getString("metadata"),new TypeReference<Map<String,Object>>(){})).score(rs.getDouble("score")).build();}
        catch(java.io.IOException ex) {throw new java.sql.SQLException("Invalid knowledge metadata",ex);}
    }
    private void validate(String tenant,String query,int limit) {
        new KnowledgeFilterFactory().publishedAfterSales(tenant);BusinessIdentifierExtractor.validate(query);
        if(limit<1 || limit>24)throw new IllegalArgumentException("limit 必须为 1～24");
    }
}
