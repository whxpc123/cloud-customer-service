package com.example.cloudcustomerservice.knowledge.management;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 基础评测 HTTP 接口：显式点击开始后才生成回答，列表/详情读取不调用模型。 */
@RestController
@Profile("local & knowledge")
@RequestMapping("/internal/knowledge-admin/evaluations")
public class KnowledgeEvaluationController {
    private final KnowledgeEvaluationService service;
    /** 统一注入评测队列与持久化服务。 */
    public KnowledgeEvaluationController(KnowledgeEvaluationService service) {this.service=service;}
    /** 返回最近运行列表。 */
    @GetMapping public List<KnowledgeEvaluationService.Summary> list() {return service.list();}
    /** 保存用户题集快照并排队执行，202 表示已受理，未代表完成。 */
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    public KnowledgeEvaluationService.Run create(@RequestBody KnowledgeEvaluationService.Request request) {return service.create(request);}
    /** 返回每题真实答复、来源、检查结果与耗时。 */
    @GetMapping("/{id}") public KnowledgeEvaluationService.Run get(@PathVariable String id) {return service.get(id);}
}
