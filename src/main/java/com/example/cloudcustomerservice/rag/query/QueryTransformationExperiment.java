package com.example.cloudcustomerservice.rag.query;

import com.example.cloudcustomerservice.knowledge.KnowledgeSearchResult;

/** 对比检索是两次独立的真实向量搜索，不调用最终回答模型，也不写会话记忆。 */
public record QueryTransformationExperiment(String requestId, String conversationId,
        QueryTransformationResult transformation, KnowledgeSearchResult originalSearch,
        KnowledgeSearchResult transformedSearch, String comparisonStatus) { }
