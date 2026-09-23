package com.example.cloudcustomerservice.ai.advisor;

import com.example.cloudcustomerservice.rag.NoKnowledgeEvidenceException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import com.example.cloudcustomerservice.rag.query.*;
import org.springframework.ai.document.Document;

/**
 * 必须排在 RetrievalAugmentationAdvisor 之后，检查它放入 Context 的真实检索结果。
 * 不重复访问 VectorStore；无正文阻断生成，跨范围结果视为配置/存储异常并阻断。
 */
public final class EvidenceRequiredAdvisor implements CallAdvisor {
    private static final Logger log = LoggerFactory.getLogger(EvidenceRequiredAdvisor.class);

    /**
     * 证据正文此时已被 QueryAugmenter 组装进 Prompt，但只有通过本方法才会到达模型及模型正文日志。
     * 范围复核延续第七章的防御：不能仅因为存储声称应用了过滤器就信任返回记录。
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // 无法可靠补全指代时先追问，不把“未搜索”误报为“没有企业资料”。
        if (request.context().get(QueryTransformationTrace.KEY) instanceof QueryTransformationTrace trace
                && trace.clarificationRequired()) throw new NeedsQueryClarificationException();
        Object value = request.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        if (value == null || value instanceof List<?> list && list.isEmpty()) throw new NoKnowledgeEvidenceException();
        if (!(value instanceof List<?> values)) throw new IllegalStateException("Invalid evidence context");
        Object tenantId = request.context().get(CustomerAdvisorContextKeys.TENANT_ID);
        int usable = validateDocuments(values, tenantId);
        if (usable == 0) throw new NoKnowledgeEvidenceException();
        log.info("[AI EVIDENCE] requestId={} documents={} status=PASSED",
                request.context().get(CustomerAdvisorContextKeys.REQUEST_ID), usable);
        return chain.nextCall(request);
    }

    /**
     * 第十二章复用同一验证：去重前逐路检查，去重后证据门再次检查。
     * 空列表在检索阶段合法，真正的空证据阻断仍由 adviseCall 负责。
     */
    public static int validateDocuments(List<?> values, Object tenantId) {
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
        // 混有空正文时也不发布不一致的证据列表；数据库非空块才是合法的整组知识结果。
        if (usable > 0 && usable != values.size()) throw new IllegalStateException("Incomplete evidence documents");
        return usable;
    }

    /** 检索之后、模型之前执行。 */
    @Override public int getOrder() { return CustomerAdvisorOrders.EVIDENCE_GATE; }
    /** 稳定的处理器名称。 */
    @Override public String getName() { return "evidence-required-advisor"; }
}
