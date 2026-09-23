package com.example.cloudcustomerservice.rag.rerank;

/** 本次请求的受控参数；实验可调整，不允许模型或客户端设置租户、URL、模型及鉴权。 */
public record RerankOptions(boolean enabled, int perQueryTopK, int topN, int maxContextTokens) {
    public static final RerankOptions DEFAULT = new RerankOptions(true, 6, 6, 5000);
    /** 小范围实验避免一次请求无限扩大收费和返回体；候选总上限另由服务器固定。 */
    public RerankOptions {
        if (perQueryTopK < 1 || perQueryTopK > 10 || topN < 1 || topN > 10
                || maxContextTokens < 100 || maxContextTokens > 10000)
            throw new IllegalArgumentException("每路候选及 Top N 须为 1～10，上下文预算须为 100～10000");
    }
}
