package com.example.cloudcustomerservice.rag.rerank;

import com.example.cloudcustomerservice.rag.KnowledgeReference;
import java.util.*;
import org.springframework.ai.document.Document;

/** 请求级轨迹，只保存本轮真实候选和阶段结果；绝不能放进单例处理器字段。 */
public final class RerankTrace {
    public static final String KEY="customer.rerankTrace";
    private final RerankOptions options;
    private String requestId="", query="", status="NOT_STARTED", budgetStatus="NOT_STARTED";
    private String model="qwen3-rerank";
    private long durationMs;
    private Integer totalTokens;
    private int inputCount, usedTokens;
    private List<KnowledgeReference> before=List.of(), after=List.of(), selected=List.of();
    private final List<Exclusion> exclusions=new ArrayList<>();
    public RerankTrace(RerankOptions options) { this.options=Objects.requireNonNull(options); }
    public RerankOptions options() { return options; }
    /** 记录本次合并顺序；被候选或输入预算淘汰的条目也保留在 before 中。 */
    public void begin(String query, List<Document> documents,String requestId) { this.query=query;this.requestId=requestId; before=refs(documents); }
    public String requestId() { return requestId; }
    public void exclude(Document d,String stage,String reason) { exclusions.add(new Exclusion(d.getId(),stage,reason)); }
    /** after 是排序阶段输出；实际最终证据要看 selected，二者不混为一谈。 */
    public void ranked(String status,String model,long ms,Integer tokens,int count,List<Document> result) {
        this.status=status;this.model=model;durationMs=ms;totalTokens=tokens;inputCount=count;after=refs(result);
        if(!status.equals("SUCCEEDED")) {
            Set<String> retained=new HashSet<>(result.stream().map(Document::getId).toList());
            Set<String> excluded=new HashSet<>(exclusions.stream().map(Exclusion::documentId).toList());
            before.stream().filter(d->!retained.contains(d.documentId()) && !excluded.contains(d.documentId()))
                .forEach(d->exclusions.add(new Exclusion(d.documentId(),"RERANK",status.equals("DISABLED")?"TOP_N_WITHOUT_RERANK":"FALLBACK_TOP_N")));
        }
    }
    /** 预算按最终拼接正文估算，未选中整个知识块，不截断原文。 */
    public void budget(List<Document> result,int tokens) { selected=refs(result);usedTokens=tokens;budgetStatus="COMPLETED"; }
    public Snapshot snapshot() { return new Snapshot(query,status,model,durationMs,totalTokens,inputCount,options,before,after,selected,List.copyOf(exclusions),budgetStatus,usedTokens); }
    private static List<KnowledgeReference> refs(List<Document> docs) { return docs.stream().map(KnowledgeReference::from).toList(); }
    public record Exclusion(String documentId,String stage,String reason) { }
    /** 所有列表均来自不可变快照；before/after/finalDocuments 是同一候选池的阶段对照。 */
    public record Snapshot(String query,String status,String model,long durationMs,Integer totalTokens,int inputCount,
            RerankOptions options,List<KnowledgeReference> before,List<KnowledgeReference> after,
            List<KnowledgeReference> finalDocuments,List<Exclusion> exclusions,String budgetStatus,int estimatedContextTokens) { }
}
