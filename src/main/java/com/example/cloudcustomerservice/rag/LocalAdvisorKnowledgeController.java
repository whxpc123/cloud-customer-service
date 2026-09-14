package com.example.cloudcustomerservice.rag;

import com.example.cloudcustomerservice.conversation.CreateConversationResponse;
import com.example.cloudcustomerservice.knowledge.LocalKnowledgeDocuments;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 第十章本地多轮知识问答入口，保留原 /internal/rag 的第九章无状态实验。 */
@RestController
@RequestMapping("/internal/advisor-rag")
@Profile("local & knowledge")
public class LocalAdvisorKnowledgeController {
    private final AdvisorKnowledgeAnswerService service;
    /** 注入 Advisor 编排服务，HTTP 层只确定租户与演示身份。 */
    public LocalAdvisorKnowledgeController(AdvisorKnowledgeAnswerService service) { this.service = service; }

    /** 返回实验页，不触发模型或数据库查询。 */
    @GetMapping(produces = "text/html;charset=UTF-8")
    public Resource page() { return new ClassPathResource("advisor-lab/index.html"); }

    /** 只分配会话编号，生成历史要等首次发送消息。 */
    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateConversationResponse create() { return new CreateConversationResponse(UUID.randomUUID().toString()); }

    /** 当前租户固定在服务器；正文或 X-Tenant-Id 中的声明不能改变检索范围。 */
    @PostMapping("/conversations/{id}/messages")
    public AdvisorKnowledgeAnswerResponse answer(@PathVariable String id,
            @RequestHeader(value = "X-Demo-User-Id", required = false) Long userId,
            @RequestBody KnowledgeQuestionRequest request) {
        return service.answer(LocalKnowledgeDocuments.TENANT_ID, id, userId, request.question());
    }

    /** 清空当前演示身份的知识会话上下文，返回 204；页面记录由前端单独管理。 */
    @DeleteMapping("/conversations/{id}/memory")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable String id, @RequestHeader(value = "X-Demo-User-Id", required = false) Long userId) {
        service.clearMemory(LocalKnowledgeDocuments.TENANT_ID, id, userId);
    }

    /** 将受控的输入校验错误映射为 400，不输出内部异常或凭证。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException ex) { return Map.of("code", "INVALID_INPUT", "message", ex.getMessage()); }
}
