package com.example.cloudcustomerservice.ai.advisor;

import com.example.cloudcustomerservice.knowledge.KnowledgeFilterFactory;
import com.example.cloudcustomerservice.rag.NoKnowledgeEvidenceException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import com.example.cloudcustomerservice.rag.query.NeedsQueryClarificationException;
import org.springframework.ai.chat.memory.ChatMemory;

/**
 * 同步链的最外层：检查必要上下文，记录耗时与结果，原样传递响应或异常。
 * 唯一实例字段是无状态依赖；requestId、tenantId 和计时全部是方法局部变量。
 * 只实现 CallAdvisor，不得把这条链直接改成 stream 后声称仍有审计与证据拦截。
 */
public final class RequestAuditAdvisor implements CallAdvisor {
    private static final Logger log = LoggerFactory.getLogger(RequestAuditAdvisor.class);
    private final KnowledgeFilterFactory filters;

    /** 注入同一过滤器工厂，校验调用方没有遗漏或放宽本次租户过滤。 */
    public RequestAuditAdvisor(KnowledgeFilterFactory filters) { this.filters = filters; }

    /**
     * 先阻止不完整上下文落入默认记忆或无范围检索，再把请求交给 nextCall。
     * 审计日志不打印问题、证据、完整会话 ID、模型配置或异常正文。
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long started = System.nanoTime();
        String requestId;
        String tenantId;
        try {
            requestId = requiredText(request.context(), CustomerAdvisorContextKeys.REQUEST_ID);
            if (!requestId.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid requestId");
            tenantId = requiredText(request.context(), CustomerAdvisorContextKeys.TENANT_ID);
            String expectedFilter = filters.publishedAfterSales(tenantId);
            String memoryId = requiredText(request.context(), ChatMemory.CONVERSATION_ID);
            if (!memoryId.matches("[a-zA-Z0-9_/-]{1,240}") || !memoryId.startsWith("knowledge/" + tenantId + "/")) {
                throw new IllegalArgumentException("Invalid knowledge conversationId");
            }
            if (!expectedFilter.equals(request.context().get(VectorStoreDocumentRetriever.FILTER_EXPRESSION))) {
                throw new IllegalArgumentException("Missing or mismatched knowledge filter");
            }
        } catch (IllegalArgumentException ex) {
            // 无效标识符可能包含换行等内容，不在日志中回显尚未验证的上下文。
            log.warn("[AI AUDIT] status=INVALID_CONTEXT durationMs={} errorType={}", elapsed(started), ex.getClass().getSimpleName());
            throw ex;
        }
        try {
            ChatClientResponse response = chain.nextCall(request);
            boolean hasResponse = response != null && response.chatResponse() != null
                    && response.chatResponse().getResult() != null
                    && response.chatResponse().getResult().getOutput() != null
                    && response.chatResponse().getResult().getOutput().getText() != null
                    && !response.chatResponse().getResult().getOutput().getText().isBlank();
            log.info("[AI AUDIT] requestId={} tenantId={} durationMs={} hasResponse={} status={}",
                    requestId, tenantId, elapsed(started), hasResponse, hasResponse ? "COMPLETED" : "EMPTY_RESPONSE");
            return response;
        } catch (NeedsQueryClarificationException ex) {
            log.info("[AI AUDIT] requestId={} tenantId={} durationMs={} status=NEEDS_CLARIFICATION generationCalled=false",
                    requestId, tenantId, elapsed(started));
            throw ex;
        } catch (NoKnowledgeEvidenceException ex) {
            log.info("[AI AUDIT] requestId={} tenantId={} durationMs={} hasResponse=false status=NO_EVIDENCE generationCalled=false",
                    requestId, tenantId, elapsed(started));
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("[AI AUDIT] requestId={} tenantId={} durationMs={} hasResponse=false status=FAILED errorType={}",
                    requestId, tenantId, elapsed(started), ex.getClass().getSimpleName());
            throw ex;
        }
    }

    /** 将单调时钟差转换为毫秒，不受系统日期调整影响。 */
    private long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

    /** 必填 Context 只能是非空字符串，不把任意对象 toString 后继续使用。 */
    private String requiredText(Map<String, Object> context, String key) {
        Object value = context.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Missing advisor context: " + key);
        return text;
    }

    /** 最早检查请求，最后观察返回。 */
    @Override public int getOrder() { return CustomerAdvisorOrders.AUDIT; }
    /** 稳定名称用于观察和测试，不包含请求数据。 */
    @Override public String getName() { return "request-audit-advisor"; }
}
