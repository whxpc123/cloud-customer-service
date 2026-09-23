package com.example.cloudcustomerservice.rag.query;

import java.util.ArrayList;
import java.util.List;

/**
 * 每次请求单独创建的观察容器，随 Advisor Context 传递，不放进单例字段或 ThreadLocal。
 * 框架会复制 Context Map；容器引用保留，异常/空证据时服务仍能知道实际转换与检索状态。
 * 本章只做同步单路检索，不能直接复用为未来并行多查询的可变共享状态。
 */
public final class QueryTransformationTrace {
    public static final String KEY = "customer.queryTransformationTrace";
    private final String original;
    private String transformed;
    private String retrievalQuery;
    private int historyCount;
    private boolean clarification;
    private final List<QueryTransformationResult.Stage> stages = new ArrayList<>();

    /** 从客户原文开始，检索字段保持空值直到真正调用检索器。 */
    public QueryTransformationTrace(String original) { this.original = original; this.transformed = original; }
    /** 逐阶段累积轨迹；一旦要求澄清，后续 Rewrite 不得撤销该状态。 */
    public void record(String query, int count, String stage, String status, long ms, boolean needsClarification) {
        transformed = query;
        historyCount = count;
        clarification |= needsClarification;
        stages.add(new QueryTransformationResult.Stage(stage, status, ms));
    }
    /** 供后续转换和证据门读取澄清信号。 */
    public boolean clarificationRequired() { return clarification; }
    /** 只有真正进入检索器才记录查询；未搜索时返回 null，避免伪造“实际检索”。 */
    public void searched(String query) { retrievalQuery = query; }
    /** 返回实际交给检索器的文本；未进入检索时为 null。 */
    public String retrievalQuery() { return retrievalQuery; }
    /** 构建不可变响应，record 构造器复制阶段列表以隔离后续写入。 */
    public QueryTransformationResult snapshot() {
        return new QueryTransformationResult(original, transformed, historyCount, clarification, stages);
    }
}
