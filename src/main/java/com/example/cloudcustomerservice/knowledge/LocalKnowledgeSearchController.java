package com.example.cloudcustomerservice.knowledge;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 本地知识检索页面及搜索入口；租户由服务端指定，页面只能调整问题、条数与阈值。
 * 返回的是检索到的原始知识块，尚未经过聊天模型生成答复。
 */
@RestController
@RequestMapping("/internal/knowledge")
@Profile("local & knowledge")
public class LocalKnowledgeSearchController {
    private final KnowledgeSearchService service;
    /**
     * 注入检索服务，所有搜索入口共用同一套服务端范围过滤。
     */
    public LocalKnowledgeSearchController(KnowledgeSearchService service) { this.service = service; }
    /**
     * 服务端固定演示租户，仅把问题、topK 和阈值传给搜索服务。
     */
    @PostMapping("/search")
    public KnowledgeSearchResult search(@RequestBody KnowledgeSearchRequest request) {
        // 演示中固定在服务端，忽略请求正文/请求头中自行声称的租户，不使用模型决定身份。
        return service.search(LocalKnowledgeDocuments.TENANT_ID, request.query(), request.topK(), request.threshold());
    }
    /**
     * 提供知识检索与导入的静态界面，加载页面本身不调用模型或导入样例。
     */
    @GetMapping(produces="text/html;charset=UTF-8")
    public Resource page() { return new ClassPathResource("knowledge-lab/index.html"); }
}
