package com.example.cloudcustomerservice.knowledge.ingestion;

import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/internal/knowledge")
@Profile("local & knowledge")
public class KnowledgeEtlController {
    public record TextPreviewRequest(String sourceName,String sourceVersion,String text,Integer chunkSize) { }
    private final KnowledgePreparationService preparation;
    private final KnowledgePreviewService previews;
    public KnowledgeEtlController(KnowledgePreparationService preparation,KnowledgePreviewService previews) {
        this.preparation=preparation;this.previews=previews;
    }
    @PostMapping("/preview")
    public KnowledgePreviewService.Preview text(@RequestBody TextPreviewRequest request) {
        return previews.save(preparation.text(request.sourceName(),request.sourceVersion(),request.text(),
                new ChunkingOptions(request.chunkSize()==null?500:request.chunkSize(),0,0)));
    }
    @PostMapping(path="/preview/file",consumes="multipart/form-data")
    public KnowledgePreviewService.Preview file(@RequestParam("file") MultipartFile file,
            @RequestParam(required=false) String sourceName,@RequestParam(required=false) String sourceVersion,
            @RequestParam(defaultValue="500") int chunkSize,@RequestParam(defaultValue="0") int pdfTopLines,
            @RequestParam(defaultValue="0") int pdfBottomLines) {
        return previews.save(preparation.file(sourceName,sourceVersion,file,new ChunkingOptions(chunkSize,pdfTopLines,pdfBottomLines)));
    }
    @GetMapping("/files/refund-policy/preview")
    public KnowledgePreviewService.Preview sample(@RequestParam(defaultValue="500") int chunkSize) throws IOException {
        byte[] bytes=new ClassPathResource("knowledge/refund-policy-v3.2.md").getContentAsByteArray();
        return previews.save(preparation.bytes("云杉商城售后服务规范","3.2","refund-policy-v3.2.md",bytes,new ChunkingOptions(chunkSize,0,0)));
    }
    @PostMapping("/previews/{id}/import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KnowledgePreviewService.Job commit(@PathVariable String id) {return previews.submit(id);}
    @GetMapping("/jobs/{id}")
    public KnowledgePreviewService.Job job(@PathVariable String id) {return previews.job(id);}
}
