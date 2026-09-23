package com.example.cloudcustomerservice.rag.expansion;

import com.example.cloudcustomerservice.rag.query.QueryTransformationTrace;
import java.util.*;
import java.util.function.BiFunction;
import org.slf4j.*;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;

/** 官方 MultiQueryExpander 外层的数量、权限和失败保护；模型不能更改 Query 的 history/context。 */
public final class GuardedQueryExpander implements QueryExpander {
    private static final Logger log = LoggerFactory.getLogger(GuardedQueryExpander.class);
    private final BiFunction<Integer, Boolean, QueryExpander> factory;
    private final QueryExpander defaults;

    /** 默认三条变体实例可复用，实验参数通过新实例配置，绝不修改共享 Builder。 */
    public GuardedQueryExpander(BiFunction<Integer, Boolean, QueryExpander> factory) {
        this.factory = factory;
        this.defaults = factory.apply(3, true);
    }

    /** 先裁决是否值得扩展，再校验、去重；扩展失败只退回一条完整查询，不额外重试。 */
    @Override
    public List<Query> expand(Query query) {
        QueryExpansionTrace trace = (QueryExpansionTrace) query.context().get(QueryExpansionTrace.KEY);
        if (trace == null) throw new IllegalStateException("Missing query expansion trace");
        long start = System.nanoTime();
        String status;
        int rejected = 0;
        boolean expanded = false;
        LinkedHashSet<String> accepted = new LinkedHashSet<>();
        boolean clarify = query.context().get(QueryTransformationTrace.KEY) instanceof QueryTransformationTrace t && t.clarificationRequired();
        if (clarify) status = "SKIPPED_CLARIFICATION";
        else if (trace.mode() == ExpansionMode.OFF) status = "DISABLED";
        else if (trace.mode() == ExpansionMode.AUTO && !ExpansionQueryGuard.multiTopic(query.text())) status = "SKIPPED_SIMPLE";
        else {
            try {
                QueryExpander delegate = trace.count() == 3 && trace.includeOriginal() ? defaults : factory.apply(trace.count(), trace.includeOriginal());
                List<Query> output = delegate.expand(query.mutate().context(Map.copyOf(query.context())).build());
                // 框架 1.1.2 对行数不符会返回原 Query，不应误报为成功扩展。
                if (output == null || output.isEmpty() || output.size() == 1 && output.get(0).text().equals(query.text())) {
                    status = "FALLBACK_INVALID";
                } else {
                    if (trace.includeOriginal()) accepted.add(query.text());
                    int variants = 0;
                    for (Query value : output) {
                        String text = value == null ? "" : ExpansionQueryGuard.clean(value.text());
                        if (text.equals(query.text())) continue;
                        if (variants >= trace.count() || !ExpansionQueryGuard.valid(query.text(), text) || !accepted.add(text)) {
                            rejected++; continue;
                        }
                        variants++;
                    }
                    expanded = variants > 0;
                    status = expanded ? rejected == 0 && variants == trace.count() ? "EXPANDED" : "PARTIAL" : "FALLBACK_INVALID";
                }
            } catch (RuntimeException ex) {
                // 服务商异常可能包含原文，日志只发布状态，保持原始过滤条件。
                status = "FALLBACK_ERROR";
            }
        }
        if (!expanded) { accepted.clear(); accepted.add(query.text()); }
        List<String> planned = clarify ? List.of() : List.copyOf(accepted);
        long ms = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        trace.planned(query.text(), status, planned, rejected, ms, expanded);
        log.info("[QUERY EXPANSION] requestId={} status={} queries={} rejected={} durationMs={} originalLength={}",
                query.context().get("customer.requestId"), status, planned.size(), rejected, ms, query.text().length());
        // 澄清保留框架必需的 Query；检索器会在真正查询前跳过，轨迹中仍标为零路检索。
        return accepted.stream().map(text -> query.mutate().text(text).build()).toList();
    }
}
