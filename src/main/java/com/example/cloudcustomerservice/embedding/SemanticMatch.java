package com.example.cloudcustomerservice.embedding;

/**
 * 一个候选文本与问题的相似度结果，保留原文以便核对排序。
 *
 * @param content 候选文本原文
 * @param score 余弦相似度，范围 -1～1，不表示正确率
 */
public record SemanticMatch(String content, double score) { }
