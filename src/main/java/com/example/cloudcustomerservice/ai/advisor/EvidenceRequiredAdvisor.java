package com.example.cloudcustomerservice.ai.advisor;

import com.example.cloudcustomerservice.rag.NoKnowledgeEvidenceException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;

/**
 * 必须排在 QuestionAnswerAdvisor 之后，检查它放入 Context 的真实检索结果。
 * 不重复访问 VectorStore；无正文阻断生成，跨范围结果视为配置/存储异常并阻断。
 */
public final class EvidenceRequiredAdvisor implements CallAdvisor {
    private static final Logger log = LoggerFactory.getLogger(EvidenceRequiredAdvisor.class);

    /**
     * 证据正文此时已被 QAA 组装进 Prompt，但只有通过本方法才会到达模型及模型正文日志。
     * 范围复核延续第七章的防御：不能仅因为存储声称应用了过滤器就信任返回记录。
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Object value = request.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if (value == null || value instanceof List<?> list && list.isEmpty()) throw new NoKnowledgeEvidenceException();
        if (!(value instanceof List<?> values)) throw new IllegalStateException("Invalid evidence context");
        Object tenantId = request.context().get(CustomerAdvisorContextKeys.TENANT_ID);
        int usable = 0;
        for (Object item : values) {
            if (!(item instanceof Document document)) throw new IllegalStateException("Invalid evidence document");
            var metadata = document.getMetadata();
            if (!(tenantId instanceof String) || !tenantId.equals(metadata.get("tenantId"))
                    || !"PUBLISHED".equals(metadata.get("status")) || !"after-sales".equals(metadata.get("knowledgeBase"))
                    || !"zh-CN".equals(metadata.get("language"))) throw new IllegalStateException("Evidence scope mismatch");
            if (document.getScore() == null || !Double.isFinite(document.getScore())) throw new IllegalStateException("Invalid evidence score");
            if (document.getText() != null && !document.getText().isBlank()) usable++;
        }
        if (usable == 0) throw new NoKnowledgeEvidenceException();
        // 混有空正文时也不发布不一致的证据列表；数据库非空块才是合法的整组知识结果。
        if (usable != values.size()) throw new IllegalStateException("Incomplete evidence documents");
        log.info("[AI EVIDENCE] requestId={} documents={} status=PASSED",
                request.context().get(CustomerAdvisorContextKeys.REQUEST_ID), usable);
        return chain.nextCall(request);
    }

    /** 检索之后、模型之前执行。 */
    @Override public int getOrder() { return CustomerAdvisorOrders.EVIDENCE_GATE; }
    /** 稳定的处理器名称。 */
    @Override public String getName() { return "evidence-required-advisor"; }
}
