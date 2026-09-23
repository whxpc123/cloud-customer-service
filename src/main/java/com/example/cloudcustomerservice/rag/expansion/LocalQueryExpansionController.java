package com.example.cloudcustomerservice.rag.expansion;

import com.example.cloudcustomerservice.knowledge.LocalKnowledgeDocuments;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 第十二章只在 local + knowledge 同时启用时开放，不接收客户端声明的租户或过滤条件。 */
@RestController
@RequestMapping("/internal/query-expansion")
@Profile("local & knowledge")
public class LocalQueryExpansionController {
    private final LocalQueryExpansionService service;
    /** 注入实验服务，HTTP 层不直接拼接模型提示词。 */
    public LocalQueryExpansionController(LocalQueryExpansionService service) { this.service = service; }
    /** 由同一 Spring Boot 应用提供静态页面，无需前端开发服务器。 */
    @GetMapping(produces = "text/html;charset=UTF-8")
    public Resource page() { return new ClassPathResource("expansion-lab/index.html"); }
    /** 固定租户，演示身份与原知识会话保持一致，最多五条变体。 */
    @PostMapping("/{conversationId}/expand")
    public QueryExpansionExperiment expand(@PathVariable String conversationId,
            @RequestHeader(value = "X-Demo-User-Id", required = false) Long userId,
            @RequestBody QueryExpansionRequest request) {
        return service.expand(LocalKnowledgeDocuments.TENANT_ID, conversationId, userId, request);
    }
    /** 可预期的校验失败返回 400，不含供应商错误或凭证。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException ex) { return Map.of("code", "INVALID_INPUT", "message", ex.getMessage()); }
}
