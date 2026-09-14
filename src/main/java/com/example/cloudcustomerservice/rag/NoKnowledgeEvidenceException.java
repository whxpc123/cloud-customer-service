package com.example.cloudcustomerservice.rag;

/** 证据门槛的预期短路信号，Service 转成 NO_EVIDENCE，不把异常原文交给客户。 */
public final class NoKnowledgeEvidenceException extends RuntimeException {
    /** 缺少可用知识时停止后续同步链，尤其不能调用 ChatModel 猜测企业制度。 */
    public NoKnowledgeEvidenceException() { super("No published knowledge evidence was retrieved"); }
}
