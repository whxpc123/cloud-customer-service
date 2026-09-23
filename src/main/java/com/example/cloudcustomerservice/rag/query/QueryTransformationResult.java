package com.example.cloudcustomerservice.rag.query;

import java.util.List;

/**
 * 查询转换的可观察结果，仅在本地接口返回原文；普通日志只记录长度和耗时。
 * stages 是逻辑转换步骤，不等于网络重试次数或账单 Token 数，不能据此计算实际费用。
 */
public record QueryTransformationResult(String originalQuery, String transformedQuery, int historyMessageCount,
        boolean clarificationRequired, List<Stage> stages) {
    public QueryTransformationResult { stages = List.copyOf(stages); }

    /** 每一步的耗时与降级原因；无历史跳过 Compression 时不会调用转换模型。 */
    public record Stage(String name, String status, long durationMs) { }
}
