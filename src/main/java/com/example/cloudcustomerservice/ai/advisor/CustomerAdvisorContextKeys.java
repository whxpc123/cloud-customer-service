package com.example.cloudcustomerservice.ai.advisor;

/** 本次调用的数据键，不是单例 Advisor 的可变字段，也不会自动成为 Prompt 文本。 */
public final class CustomerAdvisorContextKeys {
    /** 只提供键名常量，无需实例。 */
    private CustomerAdvisorContextKeys() { }
    /** 服务端生成的请求关联 ID，用来连接页面结果与审计日志。 */
    public static final String REQUEST_ID = "customer.requestId";
    /** 服务端确定的租户，不接受模型或任意 HTTP 字段自行指定。 */
    public static final String TENANT_ID = "customer.tenantId";
    /** 应用侧演示身份，仅用于上下文观察；真实生产须替换为认证结果。 */
    public static final String USER_ID = "customer.userId";
}
