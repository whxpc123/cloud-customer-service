package com.example.cloudcustomerservice.knowledge;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/knowledge")
@Profile("local & knowledge")
public class LocalKnowledgeImportController {
    private final LocalKnowledgeImportService service;
    public LocalKnowledgeImportController(LocalKnowledgeImportService service) { this.service = service; }
    @PostMapping("/seed")
    public KnowledgeImportResponse seed() { return new KnowledgeImportResponse(service.importDocuments()); }
}
