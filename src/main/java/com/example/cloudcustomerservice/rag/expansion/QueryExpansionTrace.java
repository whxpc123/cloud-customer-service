package com.example.cloudcustomerservice.rag.expansion;

import com.example.cloudcustomerservice.rag.KnowledgeReference;
import java.util.*;
import org.springframework.ai.document.Document;

/**
 * 每请求创建，随 Query.context 传递；只在本章顺序执行的多路检索中使用。
 * 保存计划、实际执行和最终合并三种状态，未执行不能冒充零命中。不得放入单例字段。
 */
public final class QueryExpansionTrace {
    public static final String KEY = "customer.queryExpansionTrace";
    private final String original;
    private final ExpansionMode mode;
    private final int count;
    private final boolean includeOriginal;
    private String transformed, status = "NOT_STARTED", retrievalStatus = "NOT_REQUESTED";
    private List<String> queries = List.of();
    private final List<QueryExpansionResult.Branch> branches = new ArrayList<>();
    private List<KnowledgeReference> joined = List.of();
    private int rejected, topK = 5, raw, duplicates, characters, tokens;
    private long duration;

    /** 正式接口固定三条变体，实验接口也必须先验证 1～5 的范围。 */
    public QueryExpansionTrace(String original, ExpansionMode mode, int count, boolean includeOriginal) {
        if (count < 1 || count > 5) throw new IllegalArgumentException("扩展数量必须在 1 至 5 之间");
        this.original = original; this.transformed = original; this.mode = mode == null ? ExpansionMode.AUTO : mode;
        this.count = count; this.includeOriginal = includeOriginal;
    }
    /** 请求策略值只读，模型无法修改。 */
    public ExpansionMode mode() { return mode; }
    /** 请求生成的变体数量，不含保留的完整问题。 */
    public int count() { return count; }
    /** 实验可不保留完整问题，但失败兜底始终恢复原查询。 */
    public boolean includeOriginal() { return includeOriginal; }
    /** 返回实际各路的统一 Top K。 */
    public int topK() { return topK; }
    /** 任一路失败后，后续任务不再访问收费或数据库服务。 */
    public boolean failed() { return retrievalStatus.equals("FAILED"); }
    /** 查询顺序用于稳定“第一次命中”的去重语义。 */
    public List<String> queries() { return queries; }

    /** 扩展结束时一次性保存计划，只有成功扩展的查询使用每路 Top 3。 */
    public void planned(String transformed, String status, List<String> queries, int rejected, long duration, boolean expanded) {
        this.transformed = transformed; this.status = status; this.queries = List.copyOf(queries);
        this.rejected = rejected; this.duration = duration; this.topK = expanded ? 3 : 5;
        if (status.equals("SKIPPED_CLARIFICATION")) retrievalStatus = "SKIPPED_CLARIFICATION";
    }

    /** 只对真正执行的检索记录结果；失败单列，不把网络错误当作空命中。 */
    public void retrieved(String query, String resultStatus, long ms, List<Document> documents) {
        branches.add(new QueryExpansionResult.Branch(queries.indexOf(query), query, resultStatus, ms,
                documents.stream().map(KnowledgeReference::from).toList()));
        raw += documents.size();
        retrievalStatus = resultStatus.equals("FAILED") ? "FAILED" : "RUNNING";
    }

    /** 合并成功后发布完整候选；Token 估计采用汉字等非 ASCII 约 1.5、ASCII 约 0.25 的启发式。 */
    public void joined(List<Document> documents) {
        joined = documents.stream().map(KnowledgeReference::from).toList();
        duplicates = raw - joined.size();
        String context = String.join("\n\n", documents.stream().map(Document::getText).toList());
        characters = context.length();
        long ascii = context.chars().filter(c -> c < 128).count();
        tokens = (int) Math.ceil(ascii / 4.0 + (characters - ascii) * 1.5);
        if (!retrievalStatus.equals("SKIPPED_CLARIFICATION")) retrievalStatus = "COMPLETED";
    }

    /** 构造只读 DTO；没有成功 join 时合并列表仍为空。 */
    public QueryExpansionResult snapshot() {
        return new QueryExpansionResult(original, transformed, mode, status, count, includeOriginal,
                queries.contains(transformed), queries, rejected, duration, topK, branches, retrievalStatus,
                raw, joined.size(), duplicates, characters, tokens, joined);
    }
}
