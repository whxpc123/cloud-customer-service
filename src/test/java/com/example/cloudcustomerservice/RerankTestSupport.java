package com.example.cloudcustomerservice;
import com.example.cloudcustomerservice.rag.rerank.*;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
/** 旧章节回归使用真实后处理器的可识别未配置路径，不产生云端调用。 */
final class RerankTestSupport {
    static QwenRerankDocumentPostProcessor ranker() {
        return new QwenRerankDocumentPostProcessor((q,d,n)->{throw new RerankGateway.NotConfigured();},new JTokkitTokenCountEstimator());
    }
    static ContextBudgetDocumentPostProcessor budget() { return new ContextBudgetDocumentPostProcessor(new JTokkitTokenCountEstimator()); }
}
