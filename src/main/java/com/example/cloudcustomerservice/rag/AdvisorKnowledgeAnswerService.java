package com.example.cloudcustomerservice.rag;

import com.example.cloudcustomerservice.ai.advisor.CustomerAdvisorContextKeys;
import com.example.cloudcustomerservice.knowledge.KnowledgeFilterFactory;
import java.util.List;
import com.example.cloudcustomerservice.rag.query.*;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 第十章服务在第十一章升级检索链，只准备业务上下文、触发一次 Advisor 链并转换结果；检索和 Prompt 增强交给 Modular RAG。
 * 同一会话的发送/清空通过有界分段锁串行执行；不同身份、租户及普通客服使用互不相同的记忆键。
 */
@Service
@Profile("local & knowledge")
public class AdvisorKnowledgeAnswerService {
    private final ChatClient client;
    private final KnowledgeFilterFactory filters;
    private final ChatMemory memory;

    /** 注入专用客户端和原内存仓库；绝不把请求身份存在这个单例 Service 的可变字段中。 */
    public AdvisorKnowledgeAnswerService(@Qualifier("knowledgeConversationChatClient") ChatClient client,
            KnowledgeFilterFactory filters, @Qualifier("customerChatMemory") ChatMemory memory) {
        this.client = client; this.filters = filters; this.memory = memory;
    }

    /** 章节三参数入口，未提供身份时使用访客命名空间。 */
    public AdvisorKnowledgeAnswerResponse answer(String tenantId, String conversationId, String question) {
        return answer(tenantId, conversationId, null, question);
    }

    /**
     * 每次传入完整隔离键和动态过滤器；仅调用一次 chatClientResponse，再从同一对象取答复及来源。
     * 无证据不调用最终回答模型（查询转换可能已调用模型），但前面的 Memory 已存入客户原文；网络/生成失败也可能保留该原文。
     */
    public AdvisorKnowledgeAnswerResponse answer(String tenantId, String conversationId, Long userId, String question) {
        String filter = filters.publishedAfterSales(tenantId);
        String memoryId = memoryId(tenantId, conversationId, userId);
        if (question == null || question.isBlank() || question.length() > 2000) {
            throw new IllegalArgumentException("问题需要包含 1 至 2000 个字符");
        }
        synchronized (KnowledgeConversationLocks.forKey(memoryId)) {
            return answerLocked(tenantId, conversationId, userId, question.strip(), memoryId, filter);
        }
    }

    /** 在会话锁内执行完整读取/转换/生成/写记忆，防止清空与同会话请求交错。 */
    private AdvisorKnowledgeAnswerResponse answerLocked(String tenantId, String conversationId, Long userId,
            String query, String memoryId, String filter) {
        var trace = new QueryTransformationTrace(query);
        String requestId = UUID.randomUUID().toString();
        KnowledgeAnswerResponse result;
        try {
            ChatClientResponse response = client.prompt().user(query)
                    .advisors(a -> a.param(QueryTransformationTrace.KEY, trace).param(ChatMemory.CONVERSATION_ID, memoryId)
                            .param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, filter)
                            .param(CustomerAdvisorContextKeys.REQUEST_ID, requestId)
                            .param(CustomerAdvisorContextKeys.TENANT_ID, tenantId)
                            .param(CustomerAdvisorContextKeys.USER_ID, userId == null ? "guest" : userId.toString()))
                    .call().chatClientResponse();
            result = new KnowledgeAnswerResponse(KnowledgeAnswerStatus.ANSWERED, extractAnswer(response), references(response));
        } catch (NeedsQueryClarificationException ex) {
            result = new KnowledgeAnswerResponse(KnowledgeAnswerStatus.NEEDS_CLARIFICATION,
                    "请补充具体商品或场景，例如是在问购买配送费、个人原因退货运费，还是质量问题退货运费？", List.of());
        } catch (NoKnowledgeEvidenceException ex) {
            result = KnowledgeAnswerResponse.noEvidence();
        } catch (RuntimeException ex) {
            result = KnowledgeAnswerResponse.unavailable();
        }
        return new AdvisorKnowledgeAnswerResponse(requestId, conversationId, trace.retrievalQuery(), result.status(), result.answer(), result.references(), trace.snapshot());
    }

    /** 验证清空目标后只删除对应知识会话，不影响第九章、其他身份或普通客服。 */
    public void clearMemory(String tenantId, String conversationId, Long userId) {
        String key = memoryId(tenantId, conversationId, userId);
        synchronized (KnowledgeConversationLocks.forKey(key)) { memory.clear(key); }
    }

    /** 从服务器租户、演示身份和合法外部 ID 构造不可碰撞的内部键；这不是生产认证。 */
    public static String memoryId(String tenantId, String conversationId, Long userId) {
        if (tenantId == null || !tenantId.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid tenantId");
        if (conversationId == null || !conversationId.matches("[a-zA-Z0-9_-]{1,100}")) throw new IllegalArgumentException("Invalid conversationId");
        if (userId != null && userId <= 0) throw new IllegalArgumentException("Invalid demo user id");
        return "knowledge/" + tenantId + "/" + (userId == null ? "guest" : "user-" + userId) + "/" + conversationId;
    }

    /** 防止空候选、空输出或空白回答被当成 ANSWERED；异常统一交给上层稳定降级。 */
    private String extractAnswer(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) throw new IllegalStateException("Empty AI response");
        String text = response.chatResponse().getResult().getOutput().getText();
        if (text == null || text.isBlank()) throw new IllegalStateException("Empty AI answer");
        return text.strip();
    }

    /**
     * 从唯一一次终结调用的 Context 取文档，不再检索，也不让模型生成来源。
     * QueryAugmenter 增强的内容是正文拼接，来源元数据留给 Java 展示；不代表逐句引用证明。
     */
    private List<KnowledgeReference> references(ChatClientResponse response) {
        Object value = response.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        if (!(value instanceof List<?> documents) || documents.isEmpty()) throw new IllegalStateException("Missing evidence response");
        return documents.stream().map(item -> {
            if (!(item instanceof Document d)) throw new IllegalStateException("Invalid evidence response");
            var m = d.getMetadata();
            return new KnowledgeReference(d.getId(), text(m.get("sourceId")),
                    text(m.getOrDefault("sourceName", m.get("sourceId"))), text(m.get("sourceVersion")),
                    m.get("chunkIndex") instanceof Number n ? n.intValue() : 0, text(m.get("category")), d.getScore(), d.getText());
        }).toList();
    }

    /** 兼容课程旧数据缺少部分展示字段，空值用空串而不是字符串 null。 */
    private String text(Object value) { return value == null ? "" : value.toString(); }
}
