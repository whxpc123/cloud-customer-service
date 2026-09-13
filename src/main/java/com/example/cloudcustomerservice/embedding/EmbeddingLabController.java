package com.example.cloudcustomerservice.embedding;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/embedding-lab")
@Profile("local")
public class EmbeddingLabController {
    private final SemanticSimilarityService service;
    public EmbeddingLabController(SemanticSimilarityService service) { this.service = service; }

    @GetMapping(produces = "text/html;charset=UTF-8")
    public Resource page() { return new ClassPathResource("embedding-lab/index.html"); }

    @PostMapping("/compare")
    public SimilarityResult compare(@RequestBody SimilarityRequest request) {
        return service.compare(request.left(), request.right());
    }

    @PostMapping("/rank")
    public SemanticSearchResult rank(@RequestBody SemanticSearchRequest request) {
        return service.rank(request.query(), request.candidates());
    }
}
