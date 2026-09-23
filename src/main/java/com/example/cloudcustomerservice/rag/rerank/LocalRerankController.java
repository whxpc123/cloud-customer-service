package com.example.cloudcustomerservice.rag.rerank;

import com.example.cloudcustomerservice.ai.advisor.CustomerAdvisorContextKeys;
import com.example.cloudcustomerservice.knowledge.*;
import com.example.cloudcustomerservice.rag.*;
import com.example.cloudcustomerservice.rag.expansion.*;
import com.example.cloudcustomerservice.rag.query.*;
import java.util.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 同一批真实候选的 A/B 对照；不生成客服回答，不写会话历史、不改知识库。 */
@RestController
@RequestMapping("/internal/rerank")
@Profile("local & knowledge")
public class LocalRerankController {
    private final ChatMemory memory; private final SafeQueryTransformer compression;
    private final GuardedQueryExpander expansion; private final MultiQueryRetrieval retrieval;
    private final KnowledgeFilterFactory filters; private final QwenRerankDocumentPostProcessor ranker;
    private final ContextBudgetDocumentPostProcessor budget;
    public LocalRerankController(@Qualifier("customerChatMemory") ChatMemory memory,
            @Qualifier("conversationCompressionTransformer") SafeQueryTransformer compression,
            GuardedQueryExpander expansion,MultiQueryRetrieval retrieval,KnowledgeFilterFactory filters,
            QwenRerankDocumentPostProcessor ranker,ContextBudgetDocumentPostProcessor budget) {
        this.memory=memory;this.compression=compression;this.expansion=expansion;this.retrieval=retrieval;
        this.filters=filters;this.ranker=ranker;this.budget=budget;
    }
    @GetMapping(produces="text/html;charset=UTF-8") public Resource page() { return new ClassPathResource("rerank-lab/index.html"); }
    /** 可调整数量与预算，不能传入自选文档、供应商地址或租户，防止绕过范围检索。 */
    public record Request(String question,ExpansionMode expansionMode,Integer perQueryTopK,Integer topN,Integer maxContextTokens) {
        public Request {
            if(question==null || question.isBlank() || question.length()>2000) throw new IllegalArgumentException("问题须为 1～2000 字符");
            expansionMode=expansionMode==null?ExpansionMode.AUTO:expansionMode;
            perQueryTopK=perQueryTopK==null?6:perQueryTopK;topN=topN==null?6:topN;maxContextTokens=maxContextTokens==null?5000:maxContextTokens;
            new RerankOptions(true,perQueryTopK,topN,maxContextTokens);
        }
    }
    public record Result(String requestId,String status,QueryTransformationResult transformation,QueryExpansionResult expansion,
            RerankTrace.Snapshot baseline,RerankTrace.Snapshot reranking) { }
    /** 两条后处理分支复用同一列表，只有 B 调用精排模型；不存在两个不同候选集冒充 A/B。 */
    @PostMapping("/{conversationId}/compare")
    public Result compare(@PathVariable String conversationId,@RequestHeader(value="X-Demo-User-Id",required=false) Long userId,@RequestBody Request request) {
        String tenant=LocalKnowledgeDocuments.TENANT_ID;
        String key=AdvisorKnowledgeAnswerService.memoryId(tenant,conversationId,userId), id=UUID.randomUUID().toString();
        var transform=new QueryTransformationTrace(request.question().strip());
        var expand=new QueryExpansionTrace(request.question().strip(),request.expansionMode(),3,true);
        var trace=new RerankTrace(new RerankOptions(true,request.perQueryTopK(),request.topN(),request.maxContextTokens()));
        var baseline=new RerankTrace(new RerankOptions(false,request.perQueryTopK(),request.topN(),request.maxContextTokens()));
        Map<String,Object> context=Map.of(QueryTransformationTrace.KEY,transform,QueryExpansionTrace.KEY,expand,RerankTrace.KEY,trace,
                CustomerAdvisorContextKeys.REQUEST_ID,id,CustomerAdvisorContextKeys.TENANT_ID,tenant,
                VectorStoreDocumentRetriever.FILTER_EXPRESSION,filters.publishedAfterSales(tenant));
        synchronized(KnowledgeConversationLocks.forKey(key)) {
            Query original=Query.builder().text(request.question().strip()).history(List.copyOf(memory.get(key))).context(context).build();
            Query transformed=compression.transform(original);
            var queries=expansion.expand(transformed);
            if(transform.clarificationRequired()) return new Result(id,"NEEDS_CLARIFICATION",transform.snapshot(),expand.snapshot(),baseline.snapshot(),trace.snapshot());
            try {
                var docs=retrieval.searchAndJoin(queries);
                var baselineContext=new HashMap<>(context);baselineContext.put(RerankTrace.KEY,baseline);
                Query baselineQuery=original.mutate().context(baselineContext).build();
                budget.process(baselineQuery,ranker.process(baselineQuery,docs));
                budget.process(original,ranker.process(original,docs));
                return new Result(id,"COMPLETED",transform.snapshot(),expand.snapshot(),baseline.snapshot(),trace.snapshot());
            } catch(RuntimeException ex) {
                return new Result(id,"RETRIEVAL_OR_VALIDATION_FAILED",transform.snapshot(),expand.snapshot(),baseline.snapshot(),trace.snapshot());
            }
        }
    }
    /** 只返回受控输入提示，不向浏览器传播 HTTP 供应商错误正文或 Key。 */
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException ex) { return Map.of("code","INVALID_INPUT","message",ex.getMessage()); }
}
