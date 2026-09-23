package com.example.cloudcustomerservice.ai.advisor;

import com.example.cloudcustomerservice.knowledge.KnowledgeFilterFactory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;

/** 第十章共享且无请求状态的 Advisor 配置，只在本地知识库环境启用。 */
@Configuration
@Profile("local & knowledge")
public class CustomerAdvisorConfiguration {
    /** 审计层统一验证请求标识、会话键和动态过滤器。 */
    @Bean
    public RequestAuditAdvisor requestAuditAdvisor(KnowledgeFilterFactory filters) { return new RequestAuditAdvisor(filters); }

    /** 复用原内存仓库，但服务使用 knowledge/租户/身份/会话的独立键，避免污染普通客服历史。 */
    @Bean("customerMemoryAdvisor")
    public MessageChatMemoryAdvisor customerMemoryAdvisor(@Qualifier("customerChatMemory") ChatMemory memory) {
        return MessageChatMemoryAdvisor.builder(memory).order(CustomerAdvisorOrders.MEMORY).build();
    }

    /** 空证据和不合范围的证据在模型边界之前被拦截。 */
    @Bean
    public EvidenceRequiredAdvisor evidenceRequiredAdvisor() { return new EvidenceRequiredAdvisor(); }
}
