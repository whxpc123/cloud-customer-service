package com.example.cloudcustomerservice.rag;

/**
 * 无状态知识问答请求，每次提供完整问题。
 *
 * @param question 本次问题，服务层要求非空且不超过 2000 个 UTF-16 代码单元
 */
public record KnowledgeQuestionRequest(String question) { }
