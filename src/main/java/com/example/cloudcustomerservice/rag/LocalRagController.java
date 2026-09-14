package com.example.cloudcustomerservice.rag;

import com.example.cloudcustomerservice.knowledge.LocalKnowledgeDocuments;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 本地无状态知识问答页面与接口，复用服务端固定租户。
 * 问题正文不能改变召回范围，页面也不能为本章自行打开订单工具或历史记忆。
 */
@RestController
@RequestMapping("/internal/rag")
@Profile("local & knowledge")
public class LocalRagController {
    private final CustomerKnowledgeAnswerService service;
    /**
     * 注入无状态 RAG 服务，控制器负责固定租户与请求/响应契约。
     */
    public LocalRagController(CustomerKnowledgeAnswerService service) { this.service = service; }

    /**
     * 返回独立知识问答页面，页面载入不会调用检索或模型。
     */
    @GetMapping(produces="text/html;charset=UTF-8")
    public Resource page() { return new ClassPathResource("rag-lab/index.html"); }

    /**
     * 仅接受问题参数，由服务端固定租户；召回条数和阈值在服务内统一确定。
     */
    @PostMapping("/answer")
    public KnowledgeAnswerResponse answer(@RequestBody KnowledgeQuestionRequest request) {
        return service.answer(LocalKnowledgeDocuments.TENANT_ID, request.question());
    }

    /**
     * 问题格式或长度不符合要求时返回 400，与正常无证据状态区分。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException ex) {
        return Map.of("code", "INVALID_INPUT", "message", ex.getMessage());
    }
}
