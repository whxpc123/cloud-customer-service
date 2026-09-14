package com.example.cloudcustomerservice.embedding;

/**
 * 向量调用或响应校验失败时使用的稳定异常。
 * 不保留上游 cause 与原始正文，HTTP 层可安全地映射为统一错误提示。
 */
public class EmbeddingUnavailableException extends RuntimeException {
    /**
     * 构造不携带上游异常链的稳定错误，供实验接口映射 502。
     */
    public EmbeddingUnavailableException() { super("Embedding service unavailable"); }
}
