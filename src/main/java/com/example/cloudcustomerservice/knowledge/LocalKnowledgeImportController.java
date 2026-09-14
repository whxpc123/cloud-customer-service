package com.example.cloudcustomerservice.knowledge;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/**
 * 课程样例导入入口，与上传自己的资料分开，便于复现第七章检索实验。
 * 重复调用会更新稳定 ID 对应的样例，但不会清空整个知识库。
 */
@RestController
@RequestMapping("/internal/knowledge")
@Profile("local & knowledge")
public class LocalKnowledgeImportController {
    private final LocalKnowledgeImportService service;
    /**
     * 注入样例发布服务，HTTP 层不自己生成向量或拼 SQL。
     */
    public LocalKnowledgeImportController(LocalKnowledgeImportService service) { this.service = service; }
    /**
     * 触发样例向量化和 upsert，返回本次样例数量；请求成功前页面不能宣称已入库。
     */
    @PostMapping("/seed")
    public KnowledgeImportResponse seed() { return new KnowledgeImportResponse(service.importDocuments()); }
}
