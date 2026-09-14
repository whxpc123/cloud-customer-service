package com.example.cloudcustomerservice.embedding;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 第六章本地向量实验入口，提供两句比较、候选排序和实验页面。
 * 仅 local 环境注册；页面读取静态资源不会调用大模型。
 */
@RestController
@RequestMapping("/internal/embedding-lab")
@Profile("local")
public class EmbeddingLabController {
    private final SemanticSimilarityService service;
    /**
     * 注入语义计算服务，使 HTTP 参数接收与数学/模型流程分离。
     */
    public EmbeddingLabController(SemanticSimilarityService service) { this.service = service; }

    /**
     * 直接返回类路径中的实验页面，不触发向量化。
     */
    @GetMapping(produces = "text/html;charset=UTF-8")
    public Resource page() { return new ClassPathResource("embedding-lab/index.html"); }

    /**
     * 接收两段文本，返回实际向量维度与余弦分数。
     */
    @PostMapping("/compare")
    public SimilarityResult compare(@RequestBody SimilarityRequest request) {
        return service.compare(request.left(), request.right());
    }

    /**
     * 接收一个问题与候选列表，返回完整降序结果，保留重复候选。
     */
    @PostMapping("/rank")
    public SemanticSearchResult rank(@RequestBody SemanticSearchRequest request) {
        return service.rank(request.query(), request.candidates());
    }
}
