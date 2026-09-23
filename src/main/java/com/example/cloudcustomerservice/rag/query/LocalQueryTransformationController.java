package com.example.cloudcustomerservice.rag.query;

import com.example.cloudcustomerservice.knowledge.LocalKnowledgeDocuments;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 第十一章本地实验入口，复用知识会话 ID 和演示身份；不暴露任意历史读取或改写权限。 */
@RestController
@RequestMapping("/internal/query-transformation")
@Profile("local & knowledge")
public class LocalQueryTransformationController {
    private final LocalQueryTransformationService service;
    /** HTTP 层仅注入实验服务，不直接访问模型或记忆仓库。 */
    public LocalQueryTransformationController(LocalQueryTransformationService service) { this.service = service; }

    /** 静态实验页由 Spring Boot 托管，普通非知识配置下整个 Controller 不注册。 */
    @GetMapping(produces = "text/html;charset=UTF-8")
    public Resource page() { return new ClassPathResource("query-lab/index.html"); }

    /** 默认只压缩；rewrite 和 compare 是可选实验开关，不影响正式多轮问答配置。 */
    @PostMapping("/{conversationId}/compress")
    public QueryTransformationExperiment compress(@PathVariable String conversationId,
            @RequestHeader(value = "X-Demo-User-Id", required = false) Long userId,
            @RequestBody QueryTransformationRequest request) {
        return service.transform(LocalKnowledgeDocuments.TENANT_ID, conversationId, userId, request);
    }

    /** 返回受控的输入错误，不向浏览器传播服务商异常。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException ex) {
        return Map.of("code", "INVALID_INPUT", "message", ex.getMessage());
    }
}
