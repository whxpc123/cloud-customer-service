package com.example.cloudcustomerservice.knowledge;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/internal/knowledge/import")
@Profile("local & knowledge")
public class CustomKnowledgeImportController {
    private final CustomKnowledgeImportService importer;

    public CustomKnowledgeImportController(CustomKnowledgeImportService importer) {
        this.importer = importer;
    }
    @PostMapping(consumes = "application/json")
    public CustomKnowledgeImportResult text(@RequestBody CustomKnowledgeImportRequest request) {
        return importer.importText(request.sourceName(), request.text());
    }
    @PostMapping(path = "/file", consumes = "multipart/form-data")
    public CustomKnowledgeImportResult file(@RequestParam("file") MultipartFile file,
            @RequestParam(value = "sourceName", required = false) String sourceName) {
        String name = sourceName;
        if (name == null || name.isBlank()) {
            name = file.getOriginalFilename();
            if (name != null) name = name.replace('\\', '/').substring(name.replace('\\', '/').lastIndexOf('/') + 1);
        }
        return importer.importFile(name, file);
    }
}
