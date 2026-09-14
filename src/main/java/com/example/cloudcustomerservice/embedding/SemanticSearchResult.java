package com.example.cloudcustomerservice.embedding;

import java.util.List;

/**
 * 候选排序输出；相同分数保持原有顺序，重复候选不会被去重。
 *
 * @param dimensions 实际模型返回的向量维度
 * @param matches 按余弦相似度降序排列的全部候选，列表作防御性复制
 */
public record SemanticSearchResult(int dimensions, List<SemanticMatch> matches) {
    /**
     * 复制排序列表，返回后的候选顺序与数量不会被调用者增删改变。
     */
    public SemanticSearchResult { matches = List.copyOf(matches); }
}
