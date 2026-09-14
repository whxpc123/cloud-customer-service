package com.example.cloudcustomerservice.knowledge.management;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 知识管理页面与目录 API，只在本地知识环境启用；数据访问范围由服务端确定。 */
@RestController
@Profile("local & knowledge")
@RequestMapping("/internal/knowledge-admin")
public class KnowledgeAdminController {
    private final KnowledgeDocumentCatalog catalog;
    /** 注入目录服务，不让 HTTP 层拼接数据库查询。 */
    public KnowledgeAdminController(KnowledgeDocumentCatalog catalog) { this.catalog=catalog; }
    /** 管理应用使用 hash 导航，刷新各视图仍请求这个 Spring Boot 页面。 */
    @GetMapping(produces="text/html;charset=UTF-8")
    public Resource page() { return new ClassPathResource("knowledge-admin/index.html"); }
    /** 可搜索、筛选的服务端分页目录，缺省不展示回收站。 */
    @GetMapping("/documents")
    public KnowledgeDocumentCatalog.Page list(@RequestParam(defaultValue="") String query,
            @RequestParam(defaultValue="PUBLISHED") String status,@RequestParam(defaultValue="ALL") String type,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="15") int pageSize) {
        return catalog.list(query,status,type,page,pageSize);
    }
    /** 查看来源、原文和全部切片。 */
    @GetMapping("/documents/{id}")
    public KnowledgeDocumentCatalog.Detail detail(@PathVariable String id) { return catalog.detail(id); }
    /** 下载确切原文件：强制附件，不在同源下解释文件中的脚本。 */
    @GetMapping("/documents/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        var file=catalog.download(id);return attachment(file.name(),file.bytes());
    }
    /** 明确命名为知识块导出；包含块边界，不能误称旧文档原件。 */
    @GetMapping("/documents/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id) {
        var detail=catalog.detail(id);
        String text=detail.chunks().stream().map(c->"--- 知识块 "+c.metadata().get("chunkIndex")+" ---\n"+c.content())
                .collect(java.util.stream.Collectors.joining("\n\n"));
        return attachment(detail.source().name()+"-知识块.txt",text.getBytes(StandardCharsets.UTF_8));
    }
    /** nullable 字段用于拒绝空 JSON，不能将缺省 false 当成恢复授权。 */
    public record ArchiveRequest(Boolean archived) { }
    /** 可恢复的下架，不物理删除知识、原件或向量。 */
    @PatchMapping("/documents/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable String id,@RequestBody ArchiveRequest request) {
        if(request.archived()==null) throw new IllegalArgumentException("必须指定是否移入回收站");
        catalog.archive(id,request.archived());
    }
    /** 文件名使用框架编码；禁止浏览器 MIME 嗅探，正文不缓存。 */
    private ResponseEntity<byte[]> attachment(String name,byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(name,StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.noStore()).body(bytes);
    }
}
