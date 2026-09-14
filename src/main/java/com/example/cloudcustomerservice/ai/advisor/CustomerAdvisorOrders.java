package com.example.cloudcustomerservice.ai.advisor;

import org.springframework.core.Ordered;

/** 第十章同步调用链顺序。请求按数值从小到大进入，响应按相反顺序返回。 */
public final class CustomerAdvisorOrders {
    /** 无需实例化，顺序由各 Advisor 统一引用。 */
    private CustomerAdvisorOrders() { }
    /** 最外层先验证本次上下文，最后记录整条链的耗时与结果。 */
    public static final int AUDIT = Ordered.HIGHEST_PRECEDENCE + 10;
    /** 先加载旧历史，并保存本轮原始用户问题。 */
    public static final int MEMORY = Ordered.HIGHEST_PRECEDENCE + 100;
    /** 再检索并增强当前问题，避免把增强后的证据保存为客户原话。 */
    public static final int RAG = Ordered.HIGHEST_PRECEDENCE + 200;
    /** 检索完成才检查证据；放在 RAG 之前会错误地阻断所有调用。 */
    public static final int EVIDENCE_GATE = Ordered.HIGHEST_PRECEDENCE + 300;
}
