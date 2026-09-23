package com.example.cloudcustomerservice.rag.expansion;

/** 本地实验参数；数量只允许 1～5，省略时为三条变体并保留完整查询。 */
public record QueryExpansionRequest(String question, Integer numberOfQueries, Boolean includeOriginal, boolean compare) {
    /** 在模型调用之前验证范围，不将任意数量直接交给供应商。 */
    public QueryExpansionRequest {
        numberOfQueries = numberOfQueries == null ? 3 : numberOfQueries;
        includeOriginal = includeOriginal == null ? true : includeOriginal;
        if (numberOfQueries < 1 || numberOfQueries > 5) throw new IllegalArgumentException("扩展数量必须在 1 至 5 之间");
    }
}
