package com.example.cloudcustomerservice.rag;

import com.example.cloudcustomerservice.ai.advisor.CustomerAdvisorContextKeys;
import com.example.cloudcustomerservice.knowledge.KnowledgeFilterFactory;
import java.util.List;
import com.example.cloudcustomerservice.rag.expansion.*;
import com.example.cloudcustomerservice.rag.rerank.*;
import com.example.cloudcustomerservice.rag.query.*;
import java.util.UUID;
import com.example.cloudcustomerservice.knowledge.search.*;
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
        return answer(tenantId, conversationId, userId, question, ExpansionMode.AUTO);
    }

    /** 第十二章策略只作为服务端上下文传递，旧客户端默认 AUTO；关闭时仍保留历史补全。 */
    public AdvisorKnowledgeAnswerResponse answer(String tenantId, String conversationId, Long userId, String question, ExpansionMode mode) {
        return answer(tenantId,conversationId,userId,question,mode,true);
    }

    /** 重排开关只影响排序阶段，检索范围仍由服务器决定。 */
    public AdvisorKnowledgeAnswerResponse answer(String tenantId,String conversationId,Long userId,String question,ExpansionMode mode,boolean rerankEnabled) {
        String filter = filters.publishedAfterSales(tenantId);
        String memoryId = memoryId(tenantId, conversationId, userId);
        if (question == null || question.isBlank() || question.length() > 2000) {
            throw new IllegalArgumentException("问题需要包含 1 至 2000 个字符");
        }
        synchronized (KnowledgeConversationLocks.forKey(memoryId)) {
            return answerLocked(tenantId, conversationId, userId, question.strip(), memoryId, filter, mode, rerankEnabled);
        }
    }

    /** 在会话锁内执行完整读取/转换/生成/写记忆，防止清空与同会话请求交错。 */
    private AdvisorKnowledgeAnswerResponse answerLocked(String tenantId, String conversationId, Long userId,
            String query, String memoryId, String filter, ExpansionMode mode, boolean rerankEnabled) {
        var trace = new QueryTransformationTrace(query);
        var expansion = new QueryExpansionTrace(query, mode, 3, true);
        var rerank = new RerankTrace(new RerankOptions(rerankEnabled,6,6,5000));
        String requestId = UUID.randomUUID().toString();
        KnowledgeAnswerResponse result;
        try {
            if(BusinessIdentifierExtractor.requiresTool(query))throw new HybridKnowledgeRetriever.BusinessToolRequiredException();
            ChatClientResponse response = client.prompt().user(query)
                    .advisors(a -> a.param(HybridKnowledgeRetriever.ENABLED,true).param(RerankTrace.KEY, rerank).param(QueryExpansionTrace.KEY, expansion).param(QueryTransformationTrace.KEY, trace).param(ChatMemory.CONVERSATION_ID, memoryId)
                            .param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, filter)
                            .param(CustomerAdvisorContextKeys.REQUEST_ID, requestId)
                            .param(CustomerAdvisorContextKeys.TENANT_ID, tenantId)
                            .param(CustomerAdvisorContextKeys.USER_ID, userId == null ? "guest" : userId.toString()))
                    .call().chatClientResponse();
            result = new KnowledgeAnswerResponse(KnowledgeAnswerStatus.ANSWERED, extractAnswer(response), references(response));
        } catch (HybridKnowledgeRetriever.BusinessToolRequiredException ex) {
            result = new KnowledgeAnswerResponse(KnowledgeAnswerStatus.BUSINESS_TOOL_REQUIRED,
                    "这类问题需要查询实时业务数据，请到首页普通客服选择身份后使用订单工具查询；知识库不能确认订单、物流或退款实时状态。",List.of());
        } catch (NeedsQueryClarificationException ex) {
            result = new KnowledgeAnswerResponse(KnowledgeAnswerStatus.NEEDS_CLARIFICATION,
                    "请补充具体商品或场景，例如是在问购买配送费、个人原因退货运费，还是质量问题退货运费？", List.of());
        } catch (NoKnowledgeEvidenceException ex) {
            result = KnowledgeAnswerResponse.noEvidence();
        } catch (RuntimeException ex) {
            result = KnowledgeAnswerResponse.unavailable();
        }
        return new AdvisorKnowledgeAnswerResponse(requestId, conversationId, trace.retrievalQuery(), result.status(), result.answer(), result.references(), trace.snapshot(), expansion.snapshot(), rerank.snapshot());
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
            return KnowledgeReference.from(d);
        }).toList();
    }

}
