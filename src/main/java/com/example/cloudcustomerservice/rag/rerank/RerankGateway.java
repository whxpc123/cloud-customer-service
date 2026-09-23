package com.example.cloudcustomerservice.rag.rerank;
import java.util.List;
/** 供应商边界，业务处理器和离线测试均依赖此接口。 */
public interface RerankGateway {
    Result rerank(String query, List<String> documents, int topN);
    /** index 对应请求 documents 的下标，绝不是模型返回的文档正文。 */
    record Score(int index, double relevance) { }
    /** totalTokens 是供应商实际 usage，缺失时为 null，不用估算冒充。 */
    record Result(List<Score> scores, Integer totalTokens) {
        public Result { scores = List.copyOf(scores); }
    }
    /** 未配置地址属于可识别降级，不在异常里携带 API Key 或请求内容。 */
    final class NotConfigured extends RuntimeException { public NotConfigured() { super("Rerank endpoint not configured"); } }
}
