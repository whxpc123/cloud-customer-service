package com.example.cloudcustomerservice.knowledge;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/knowledge")
@Profile("local & knowledge")
public class LocalKnowledgeSearchController {
    private final KnowledgeSearchService service;
    public LocalKnowledgeSearchController(KnowledgeSearchService service) { this.service = service; }
    @PostMapping("/search")
    public KnowledgeSearchResult search(@RequestBody KnowledgeSearchRequest request) {
        // 演示中固定在服务端，忽略请求正文/请求头中自行声称的租户，不使用模型决定身份。
        return service.search(LocalKnowledgeDocuments.TENANT_ID, request.query(), request.topK(), request.threshold());
    }
    @GetMapping(produces="text/html;charset=UTF-8")
    public Resource page() { return new ClassPathResource("knowledge-lab/index.html"); }
}
