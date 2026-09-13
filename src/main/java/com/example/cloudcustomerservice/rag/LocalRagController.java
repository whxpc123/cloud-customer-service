package com.example.cloudcustomerservice.rag;

import com.example.cloudcustomerservice.knowledge.LocalKnowledgeDocuments;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/rag")
@Profile("local & knowledge")
public class LocalRagController {
    private final CustomerKnowledgeAnswerService service;
    public LocalRagController(CustomerKnowledgeAnswerService service) { this.service = service; }

    @GetMapping(produces="text/html;charset=UTF-8")
    public Resource page() { return new ClassPathResource("rag-lab/index.html"); }

    @PostMapping("/answer")
    public KnowledgeAnswerResponse answer(@RequestBody KnowledgeQuestionRequest request) {
        return service.answer(LocalKnowledgeDocuments.TENANT_ID, request.question());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException ex) {
        return Map.of("code", "INVALID_INPUT", "message", ex.getMessage());
    }
}
