package com.example.cloudcustomerservice.rag.rerank;

import com.example.cloudcustomerservice.ai.advisor.*;
import com.example.cloudcustomerservice.rag.expansion.QueryExpansionTrace;
import java.util.*;
import org.slf4j.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/** 先排序再做上下文预算。范围校验在 try 外，权限错误绝不作为普通服务故障降级。 */
public final class QwenRerankDocumentPostProcessor implements DocumentPostProcessor {
    private static final Logger log=LoggerFactory.getLogger(QwenRerankDocumentPostProcessor.class);
    private final RerankGateway gateway; private final TokenCountEstimator estimator;
    public QwenRerankDocumentPostProcessor(RerankGateway gateway,TokenCountEstimator estimator) { this.gateway=gateway;this.estimator=estimator; }
    @Override public List<Document> process(Query query,List<Document> documents) {
        var trace=trace(query);
        // RAA 1.1.2 给 PostProcessor 的是 originalQuery；排序必须取补全后的完整问题，而不是“那运费呢”。
        String text=query.context().get(QueryExpansionTrace.KEY) instanceof QueryExpansionTrace e ? e.snapshot().transformedQuery():query.text();
        EvidenceRequiredAdvisor.validateDocuments(documents,query.context().get(CustomerAdvisorContextKeys.TENANT_ID));
        trace.begin(text,documents,String.valueOf(query.context().get(CustomerAdvisorContextKeys.REQUEST_ID)));
        List<Document> capped=new ArrayList<>();
        for(Document d:documents) {
            if(d.getText()==null || d.getText().isBlank()) { trace.exclude(d,"INPUT","EMPTY_TEXT"); continue; }
            if(capped.size()>=24) { trace.exclude(d,"INPUT","CANDIDATE_LIMIT"); continue; }
            capped.add(d);
        }
        long start=System.nanoTime();
        if(capped.isEmpty()) return finish(trace,"SKIPPED_EMPTY",start,null,0,List.of());
        if(!trace.options().enabled()) return finish(trace,"DISABLED",start,null,0,mark(capped,trace.options().topN(),false));
        if(capped.size()==1) return finish(trace,"SKIPPED_SINGLE",start,null,0,mark(capped,1,false));
        List<Document> eligible=new ArrayList<>(); int queryTokens=estimator.estimate(text), inputTokens=0;
        // 供应商总量按 Query×文档数 + 正文总量计算；3500/60000 为本项目估计阈值，不是服务端精确分词。
        if(queryTokens>3500) return finish(trace,"FALLBACK_INPUT_LIMIT",start,null,0,mark(capped,trace.options().topN(),true));
        for(Document d:capped) {
            int tokens=estimator.estimate(d.getText());
            if(tokens>3500 || inputTokens+queryTokens+tokens>60000) { trace.exclude(d,"INPUT","RERANK_INPUT_BUDGET"); continue; }
            eligible.add(d); inputTokens+=queryTokens+tokens;
        }
        if(eligible.isEmpty()) return finish(trace,"SKIPPED_INPUT_LIMIT",start,null,0,List.of());
        try {
            boolean exactPriority=eligible.stream().anyMatch(d->Boolean.TRUE.equals(d.getMetadata().get("exactMatch")));
            int n=exactPriority ? eligible.size() : Math.min(trace.options().topN(),eligible.size());
            var response=gateway.rerank(text,eligible.stream().map(Document::getText).toList(),n);
            // Gateway 可替换，处理器也验证契约，避免其他实现绕过索引和数量保护。
            if(response.scores().size()!=n) throw new IllegalStateException("Incomplete ranking");
            List<Document> result=new ArrayList<>(); Set<Integer> seen=new HashSet<>();
            for(var score:response.scores().stream().sorted(Comparator.comparingDouble(RerankGateway.Score::relevance).reversed()).toList()) {
                if(score.index()<0 || score.index()>=eligible.size() || !seen.add(score.index())
                        || !Double.isFinite(score.relevance()) || score.relevance()<0 || score.relevance()>1) throw new IllegalStateException("Invalid ranking");
                Document d=eligible.get(score.index()); var metadata=metadata(d,false);
                metadata.put("rerankScore",score.relevance());metadata.put("rerankRank",result.size()+1);metadata.put("rerankModel","qwen3-rerank");
                result.add(d.mutate().metadata(metadata).score(score.relevance()).build());
            }
            if(exactPriority) {
                result.sort(Comparator.comparing(d->!Boolean.TRUE.equals(d.getMetadata().get("exactMatch"))));
                result=new ArrayList<>(result.subList(0,Math.min(trace.options().topN(),result.size())));
            }
            Set<String> chosen=new HashSet<>(result.stream().map(Document::getId).toList());
            eligible.stream().filter(d->!chosen.contains(d.getId())).forEach(d->trace.exclude(d,"RERANK","TOP_N"));
            return finish(trace,"SUCCEEDED",start,response.totalTokens(),eligible.size(),result);
        } catch(RuntimeException ex) {
            String status=ex instanceof RerankGateway.NotConfigured?"FALLBACK_NOT_CONFIGURED":"FALLBACK_ERROR";
            log.warn("[RERANK FALLBACK] requestId={} status={} errorType={}",query.context().get(CustomerAdvisorContextKeys.REQUEST_ID),status,ex.getClass().getSimpleName());
            return finish(trace,status,start,null,ex instanceof RerankGateway.NotConfigured ? 0 : eligible.size(),mark(eligible,trace.options().topN(),true));
        }
    }
    /** 拷贝元数据，清除可能过期的排序字段；向量库中的原对象保持不变。 */
    private Map<String,Object> metadata(Document d,boolean fallback) {
        var m=new HashMap<>(d.getMetadata());m.remove("rerankScore");m.remove("rerankRank");m.remove("rerankModel");
        m.put("retrievalScore",d.getScore());m.put("rerankFallback",fallback);return m;
    }
    private List<Document> mark(List<Document> docs,int n,boolean fallback) {
        return docs.stream().limit(n).map(d->d.mutate().metadata(metadata(d,fallback)).build()).toList();
    }
    private List<Document> finish(RerankTrace t,String status,long start,Integer tokens,int count,List<Document> docs) {
        long ms=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        t.ranked(status,"qwen3-rerank",ms,tokens,count,docs);
        log.info("[RERANK] requestId={} status={} candidateCount={} resultCount={} durationMs={} totalTokens={}",t.requestId(),status,count,docs.size(),ms,tokens);
        return List.copyOf(docs);
    }
    /** 正式服务创建轨迹，缺少它意味着装配错误，不偷偷使用跨请求共享状态。 */
    static RerankTrace trace(Query q) {
        if(!(q.context().get(RerankTrace.KEY) instanceof RerankTrace t)) throw new IllegalStateException("Missing rerank trace");return t;
    }
}
