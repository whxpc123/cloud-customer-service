package com.example.cloudcustomerservice.knowledge.search;

import com.example.cloudcustomerservice.ai.advisor.CustomerAdvisorContextKeys;
import com.example.cloudcustomerservice.knowledge.*;
import com.example.cloudcustomerservice.rag.KnowledgeReference;
import com.example.cloudcustomerservice.rag.rerank.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 第十四章真实三路对照：无会话改写、无最终生成，同一次向量结果用于单路与融合展示。 */
@RestController
@Profile("local & knowledge")
@RequestMapping("/internal/hybrid-search")
public class LocalHybridSearchController {
    private final VectorStore store;private final HybridKnowledgeRetriever hybrid;
    private final QwenRerankDocumentPostProcessor ranker;private final ContextBudgetDocumentPostProcessor budget;
    private final Semaphore slots=new Semaphore(2);
    private final java.util.ArrayDeque<Long> starts=new java.util.ArrayDeque<>();
    public LocalHybridSearchController(VectorStore store,HybridKnowledgeRetriever hybrid,QwenRerankDocumentPostProcessor ranker,ContextBudgetDocumentPostProcessor budget){
        this.store=store;this.hybrid=hybrid;this.ranker=ranker;this.budget=budget;
    }
    @GetMapping(produces="text/html;charset=UTF-8") public Resource page(){return new ClassPathResource("hybrid-lab/index.html");}
    public record Request(String question,Integer topK,Boolean rerankEnabled) {
        public Request {BusinessIdentifierExtractor.extract(question);topK=topK==null?10:topK;
            if(topK<1||topK>24)throw new IllegalArgumentException("Top K 须为 1～24");rerankEnabled=Boolean.TRUE.equals(rerankEnabled);}
    }
    public record Result(String requestId,String status,String message,String query,String keywordQuery,List<String> identifiers,
            List<KnowledgeReference> vector,List<KnowledgeReference> keyword,List<KnowledgeReference> exact,List<KnowledgeReference> fused,RerankTrace.Snapshot reranking) { }
    /** 两个在途、每分钟最多二十次实验；429 不自动重试，失败不伪装空召回。 */
    @PostMapping("/compare") public Result compare(@RequestBody Request request){
        String id=UUID.randomUUID().toString(),tenant=LocalKnowledgeDocuments.TENANT_ID;
        if(BusinessIdentifierExtractor.requiresTool(request.question()))return new Result(id,"BUSINESS_TOOL_REQUIRED","请到首页普通客服通过订单工具查询实时状态。",request.question(),null,List.of(),List.of(),List.of(),List.of(),List.of(),null);
        acquire();
        try {
            var vectors=store.similaritySearch(SearchRequest.builder().query(request.question()).topK(request.topK()).similarityThreshold(.45)
                    .filterExpression(new KnowledgeFilterFactory().publishedAfterSales(tenant)).build());
            var r=hybrid.combine(tenant,request.question(),vectors,request.topK());
            var trace=new RerankTrace(new RerankOptions(request.rerankEnabled(),6,6,5000));
            Query query=Query.builder().text(request.question()).context(Map.of(RerankTrace.KEY,trace,CustomerAdvisorContextKeys.TENANT_ID,tenant,CustomerAdvisorContextKeys.REQUEST_ID,id)).build();
            budget.process(query,ranker.process(query,r.fused()));
            return new Result(id,"COMPLETED","已完成真实检索；命中不等于规则适用或答案正确。",r.query(),r.keywordQuery(),r.identifiers(),refs(r.vector()),refs(r.keyword()),refs(r.exact()),refs(r.fused()),trace.snapshot());
        } catch(RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"检索失败，本次没有可比较的完整结果");
        } finally {slots.release();}
    }
    private synchronized void acquire(){
        long now=System.nanoTime();while(!starts.isEmpty()&&now-starts.peekFirst()>60_000_000_000L)starts.removeFirst();
        if(starts.size()>=20||!slots.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"实验请求过于频繁，请稍后再试");starts.addLast(now);
    }
    private List<KnowledgeReference> refs(List<org.springframework.ai.document.Document> docs){return docs.stream().map(KnowledgeReference::from).toList();}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException ex){return Map.of("message",ex.getMessage());}
}
