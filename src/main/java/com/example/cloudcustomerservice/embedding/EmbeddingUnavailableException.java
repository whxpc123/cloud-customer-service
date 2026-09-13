package com.example.cloudcustomerservice.embedding;

/** 不携带上游错误正文或原始文本，供实验接口转换为稳定 502。 */
public class EmbeddingUnavailableException extends RuntimeException {
    public EmbeddingUnavailableException() { super("Embedding service unavailable"); }
}
