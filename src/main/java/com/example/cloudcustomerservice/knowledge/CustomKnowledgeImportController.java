package com.example.cloudcustomerservice.knowledge;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 第七章保留的同步自定义导入接口，支持粘贴正文与 multipart 文件。
 * 它现在复用第八章的读取、清理和切分流程；需要先核对切分结果时使用预览接口。
 */
@RestController
@RequestMapping("/internal/knowledge/import")
@Profile("local & knowledge")
public class CustomKnowledgeImportController {
    private final CustomKnowledgeImportService importer;

    /**
     * 注入同步导入编排服务，上传和粘贴两种传输方式复用同一准备流程。
     */
    public CustomKnowledgeImportController(CustomKnowledgeImportService importer) {
        this.importer = importer;
    }
    /**
     * 同步导入粘贴正文；名称确定来源，默认切分参数由准备服务统一提供。
     */
    @PostMapping(consumes = "application/json")
    public CustomKnowledgeImportResult text(@RequestBody CustomKnowledgeImportRequest request) {
        return importer.importText(request.sourceName(), request.text());
    }
    /**
     * 未填写资料名时取上传文件的基本名称，剥离路径部分后进入受限读取流程。
     */
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
