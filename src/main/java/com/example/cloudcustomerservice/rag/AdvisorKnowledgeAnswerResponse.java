package com.example.cloudcustomerservice.rag;

import java.util.List;
import com.example.cloudcustomerservice.rag.expansion.QueryExpansionResult;
import com.example.cloudcustomerservice.rag.query.QueryTransformationResult;

/**
 * 多轮知识页的一次响应，沿用第九章状态和来源契约并附带调用关联信息。
 * @param requestId 服务端生成的审计 ID
 * @param conversationId 页面使用的外部会话 ID，不是内部记忆键
 * @param retrievalQuery 首路实际搜索的查询；完整多路见 expansion，未进入检索时为 null
 * @param status 执行状态，ANSWERED 不代表答案已经逐句核实
 * @param answer 模型答复或 Java 的固定说明
 * @param references 本次 Modular RAG Context 中实际检索并提供给模型的文档快照
 */
public record AdvisorKnowledgeAnswerResponse(String requestId, String conversationId, String retrievalQuery,
        KnowledgeAnswerStatus status, String answer, List<KnowledgeReference> references, QueryTransformationResult transformation, QueryExpansionResult expansion,
        com.example.cloudcustomerservice.rag.rerank.RerankTrace.Snapshot reranking) {
    /** 兼容第十二章构造与旧评测快照。 */
    public AdvisorKnowledgeAnswerResponse(String requestId,String conversationId,String retrievalQuery,KnowledgeAnswerStatus status,
            String answer,List<KnowledgeReference> references,QueryTransformationResult transformation,QueryExpansionResult expansion) {
        this(requestId,conversationId,retrievalQuery,status,answer,references,transformation,expansion,null);
    }
    /** 兼容旧章节测试和评测记录构造，旧数据没有转换轨迹。 */
    public AdvisorKnowledgeAnswerResponse(String requestId, String conversationId, String retrievalQuery,
            KnowledgeAnswerStatus status, String answer, List<KnowledgeReference> references) {
        this(requestId, conversationId, retrievalQuery, status, answer, references, null, null);
    }
    /** 兼容第十一章响应构造，历史记录没有多路轨迹。 */
    public AdvisorKnowledgeAnswerResponse(String requestId, String conversationId, String retrievalQuery,
            KnowledgeAnswerStatus status, String answer, List<KnowledgeReference> references, QueryTransformationResult transformation) {
        this(requestId, conversationId, retrievalQuery, status, answer, references, transformation, null);
    }
    /** 复制来源集合，避免后续修改影响已经返回给页面的结果。 */
    public AdvisorKnowledgeAnswerResponse { references = List.copyOf(references); }
}
