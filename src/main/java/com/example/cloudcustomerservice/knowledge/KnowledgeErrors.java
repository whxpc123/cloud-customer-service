package com.example.cloudcustomerservice.knowledge;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 知识导入、预览、任务查询和检索接口共用的 HTTP 错误映射。
 * 区分输入错误、文件过大、预览过期、队列饱和与依赖故障，便于页面给出可操作提示。
 */
@RestControllerAdvice(assignableTypes={LocalKnowledgeSearchController.class,LocalKnowledgeImportController.class,CustomKnowledgeImportController.class,com.example.cloudcustomerservice.knowledge.ingestion.KnowledgeEtlController.class})
@Profile("local & knowledge")
public class KnowledgeErrors {
    /**
     * 保留预览服务的 HTTP 状态，如过期 404、资源饱和 429，并返回统一状态错误结构。
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String,String>> state(org.springframework.web.server.ResponseStatusException ex) {
        return org.springframework.http.ResponseEntity.status(ex.getStatusCode()).body(Map.of("code","PREVIEW_STATE","message",ex.getReason()==null?"预览或任务不可用":ex.getReason()));
    }
    /**
     * 上传在 Web 容器层被拒绝时也返回可读的文件大小提示，状态为 413。
     */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Map<String,String> tooLarge() { return Map.of("code","FILE_TOO_LARGE","message","文件不能超过 5 MB"); }
    /**
     * 将受控的本地参数或文件解析错误映射为 400。
     */
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException ex) { return Map.of("code","INVALID_INPUT","message",ex.getMessage()); }
    /**
     * 隐藏数据库和向量服务异常，统一返回知识库暂不可用的 502。
     */
    @ExceptionHandler(KnowledgeUnavailableException.class) @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String,String> unavailable() { return Map.of("code","KNOWLEDGE_UNAVAILABLE","message","知识库暂不可用，请检查数据库和向量服务后重试"); }
}
