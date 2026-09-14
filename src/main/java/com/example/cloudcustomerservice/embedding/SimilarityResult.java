package com.example.cloudcustomerservice.embedding;

/**
 * 两句比较的摘要，只返回维度与分数，避免向页面传输长向量。
 *
 * @param dimensions 本次返回向量的实际长度
 * @param score 余弦相似度，范围 -1～1
 */
public record SimilarityResult(int dimensions, double score) { }
