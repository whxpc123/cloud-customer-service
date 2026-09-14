package com.example.cloudcustomerservice.embedding;

/**
 * 两段文本的语义比较请求，两边使用同一实验向量模型。
 *
 * @param left 左侧文本，非空且不超过 2000 个 UTF-16 代码单元
 * @param right 右侧文本，限制与左侧一致
 */
public record SimilarityRequest(String left, String right) { }
