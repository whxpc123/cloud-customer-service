package com.example.cloudcustomerservice.knowledge.ingestion;

import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 第八章“先预览、再确认导入”的 HTTP 入口。
 * 预览只读取并切分文本；确认时提交服务端缓存的预览 ID，后台才生成向量并发布。
 * 客户端不上传自定义租户或块元数据来绕过服务端准备过程。
 */
@RestController
@RequestMapping("/internal/knowledge")
@Profile("local & knowledge")
public class KnowledgeEtlController {
    /**
     * 粘贴预览的 JSON 输入：资料名、版本、原文以及可空切分档位，缺省档位为 500。
     */
    public record TextPreviewRequest(String sourceName,String sourceVersion,String text,Integer chunkSize) { }
    private final KnowledgePreparationService preparation;
    private final KnowledgePreviewService previews;
    /**
     * 注入无模型的准备服务与后台任务服务，把预览和实际发布分成两个明确步骤。
     */
    public KnowledgeEtlController(KnowledgePreparationService preparation,KnowledgePreviewService previews) {
        this.preparation=preparation;this.previews=previews;
    }
    /**
     * 只生成正文预览；粘贴模式没有 PDF 页眉页脚，所以两个清理参数固定为 0。
     */
    @PostMapping("/preview")
    public KnowledgePreviewService.Preview text(@RequestBody TextPreviewRequest request) {
        return previews.save(preparation.text(request.sourceName(),request.sourceVersion(),request.text(),
                new ChunkingOptions(request.chunkSize()==null?500:request.chunkSize(),0,0)));
    }
    /**
     * 上传预览支持五种文件格式，并携带切分档位与 PDF 页首页尾清理行数。
     */
    @PostMapping(path="/preview/file",consumes="multipart/form-data")
    public KnowledgePreviewService.Preview file(@RequestParam("file") MultipartFile file,
            @RequestParam(required=false) String sourceName,@RequestParam(required=false) String sourceVersion,
            @RequestParam(defaultValue="500") int chunkSize,@RequestParam(defaultValue="0") int pdfTopLines,
            @RequestParam(defaultValue="0") int pdfBottomLines) {
        return previews.save(preparation.file(sourceName,sourceVersion,file,new ChunkingOptions(chunkSize,pdfTopLines,pdfBottomLines)));
    }
    /**
     * 读取项目内置售后制度生成可核对预览；此 GET 不会向量化或写知识表。
     */
    @GetMapping("/files/refund-policy/preview")
    public KnowledgePreviewService.Preview sample(@RequestParam(defaultValue="500") int chunkSize) throws IOException {
        byte[] bytes=new ClassPathResource("knowledge/refund-policy-v3.2.md").getContentAsByteArray();
        return previews.save(preparation.bytes("云杉商城售后服务规范","3.2","refund-policy-v3.2.md",bytes,new ChunkingOptions(chunkSize,0,0)));
    }
    /**
     * 确认服务端保存的预览令牌，返回 202 表示任务已接收，不能等同于导入已完成。
     */
    @PostMapping("/previews/{id}/import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KnowledgePreviewService.Job commit(@PathVariable String id) {return previews.submit(id);}
    /**
     * 供前端轮询发布进度，成功与失败由任务终态明确区分。
     */
    @GetMapping("/jobs/{id}")
    public KnowledgePreviewService.Job job(@PathVariable String id) {return previews.job(id);}
}
