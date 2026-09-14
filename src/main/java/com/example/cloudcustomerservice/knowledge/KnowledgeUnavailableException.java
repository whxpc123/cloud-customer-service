package com.example.cloudcustomerservice.knowledge;

/**
 * 知识库依赖失败的稳定业务异常，屏蔽数据库及模型服务的内部细节。
 * 调用方按知识库暂不可用处理，而不能把故障解释为没有相关资料。
 */
public class KnowledgeUnavailableException extends RuntimeException {
    /**
     * 构造没有底层 cause 的稳定异常，防止 SQL 或供应商正文传入响应。
     */
    public KnowledgeUnavailableException() { super("Knowledge service unavailable"); }
}
