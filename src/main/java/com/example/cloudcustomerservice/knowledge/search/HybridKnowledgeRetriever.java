package com.example.cloudcustomerservice.knowledge.search;

import com.example.cloudcustomerservice.ai.advisor.EvidenceRequiredAdvisor;
import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 正式问答在多路向量合并后调用一次词法召回，避免每条扩展问题重复执行相同编码检索。 */
@Service
@Profile("local & knowledge")
public class HybridKnowledgeRetriever {
    public static final String ENABLED="customer.hybridEnabled";
    private final PostgresKeywordSearchRepository repository;
    public HybridKnowledgeRetriever(PostgresKeywordSearchRepository repository){this.repository=repository;}
    public record Result(String query,String keywordQuery,List<String> identifiers,List<Document> vector,List<Document> keyword,List<Document> exact,List<Document> fused) { }
    /** 任一路失败都中止本次融合，不能把数据库超时当成无知识；失败由正式服务统一呈现。 */
    public Result combine(String tenant,String query,List<Document> vector,int limit) {
        var codes=BusinessIdentifierExtractor.extract(query);
        if(BusinessIdentifierExtractor.requiresTool(query))throw new BusinessToolRequiredException();
        // 带编码的问题按编码召回全文，避免中文长句被 simple 视为一个 AND 必需词而导致零命中。
        String keywordQuery=codes.isEmpty()?query:String.join(" OR ",codes);
        var keyword=repository.keyword(tenant,keywordQuery,limit);var exact=repository.exact(tenant,codes,limit);
        for(var route:List.of(vector,keyword,exact))EvidenceRequiredAdvisor.validateDocuments(route,tenant);
        var fused=ReciprocalRankFusion.fuse(vector,keyword,exact,limit);
        return new Result(query,keywordQuery,codes,List.copyOf(vector),keyword,exact,fused);
    }
    /** 只提示转到既有业务客服入口，不声称查询了实时订单，也不自动扩大工具权限。 */
    public static final class BusinessToolRequiredException extends RuntimeException { }
}
