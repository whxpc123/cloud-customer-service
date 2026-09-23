package com.example.cloudcustomerservice.rag.rerank;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.ai.tokenizer.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 第十三章独立 HTTP 客户端，不把排序接口当成 ChatModel，不添加新的 Key 变量。 */
@Configuration
@Profile("local & knowledge")
public class RerankConfiguration {
    @Bean public RerankGateway rerankGateway(@Value("${app.ai.rerank.base-url:}") String base,
            @Value("${DASHSCOPE_API_KEY:}") String key) {
        if(base.isBlank()) return new DashScopeQwenRerankGateway(null,"qwen3-rerank");
        URI uri=URI.create(base);
        // 只接受百炼官方业务空间主机，避免误配置时将 Key 发到任意地址；地址不能来自 HTTP 请求。
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || !uri.getHost().endsWith(".maas.aliyuncs.com")
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || uri.getPort()!=-1 || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Rerank Base URL 必须为百炼 HTTPS 业务空间根地址");
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        return new DashScopeQwenRerankGateway(RestClient.builder().baseUrl(base).requestFactory(factory)
                .defaultHeader("Authorization","Bearer "+key).build(),"qwen3-rerank");
    }
    /** CL100K_BASE 仅用于预算估计，不能拿来和 Qwen 账单逐 token 对账。 */
    @Bean public TokenCountEstimator rerankTokenEstimator() { return new JTokkitTokenCountEstimator(); }
    @Bean public QwenRerankDocumentPostProcessor rerankPostProcessor(RerankGateway gateway,TokenCountEstimator estimator) {
        return new QwenRerankDocumentPostProcessor(gateway,estimator);
    }
    @Bean public ContextBudgetDocumentPostProcessor contextBudgetPostProcessor(TokenCountEstimator estimator) {
        return new ContextBudgetDocumentPostProcessor(estimator);
    }
}
