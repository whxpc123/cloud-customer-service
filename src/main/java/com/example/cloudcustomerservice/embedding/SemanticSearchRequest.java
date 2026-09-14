package com.example.cloudcustomerservice.embedding;

import java.util.List;

/**
 * 候选排序实验的请求，暂不读写向量数据库。
 *
 * @param query 作为比较基准的问题
 * @param candidates 1～20 条候选文本，每条最多 2000 个 UTF-16 代码单元
 */
public record SemanticSearchRequest(String query, List<String> candidates) { }
