package com.example.cloudcustomerservice.knowledge;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes={LocalKnowledgeSearchController.class,LocalKnowledgeImportController.class,CustomKnowledgeImportController.class})
@Profile("local & knowledge")
public class KnowledgeErrors {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Map<String,String> tooLarge() { return Map.of("code","FILE_TOO_LARGE","message","文件不能超过 5 MB"); }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException ex) { return Map.of("code","INVALID_INPUT","message",ex.getMessage()); }
    @ExceptionHandler(KnowledgeUnavailableException.class) @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String,String> unavailable() { return Map.of("code","KNOWLEDGE_UNAVAILABLE","message","知识库暂不可用，请检查数据库和向量服务后重试"); }
}
