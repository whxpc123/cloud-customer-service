package com.example.cloudcustomerservice.knowledge.management;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 管理 API 统一错误体，保留 400/404/409/429 等可操作状态，不回显 SQL 或异常详情。 */
@RestControllerAdvice(basePackageClasses=KnowledgeAdminController.class)
@Profile("local & knowledge")
public class KnowledgeAdminErrors {
    /** 业务状态用于页面展示明确的重试/刷新提示。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> state(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message",ex.getReason()==null?"请求不可用":ex.getReason()));
    }
    /** 输入校验失败时无需重试远程模型。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException ex) { return Map.of("message",ex.getMessage()); }
    /** 数据库不可达与生成中的业务拒答区分开。 */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String,String> database() { return Map.of("message","知识数据库暂不可用，请检查数据库后重试"); }
}
