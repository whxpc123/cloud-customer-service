package com.example.cloudcustomerservice.knowledge;

import java.util.List;

/**
 * 检索问题与命中列表的响应快照，空命中是正常结果。
 *
 * @param query 本次请求的问题，用于前端核对返回对应关系
 * @param hits 符合范围与阈值的命中列表，复制后不允许增删
 */
public record KnowledgeSearchResult(String query, List<KnowledgeHit> hits) {
    /**
     * 复制命中列表，保证接口返回结果不受存储实现后续修改集合的影响。
     */
    public KnowledgeSearchResult { hits = List.copyOf(hits); }
}
