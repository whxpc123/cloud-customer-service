package com.example.cloudcustomerservice.embedding;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 仅处理向量实验控制器的异常：输入错误为 400，上游向量服务故障为 502。
 * 返回稳定的错误码和提示，不把供应商原始响应传给浏览器。
 */
@RestControllerAdvice(assignableTypes = EmbeddingLabController.class)
@Profile("local")
public class EmbeddingLabErrors {
    /**
     * 将本地输入校验说明返回为 400，方便页面提示用户调整文本。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidInput(IllegalArgumentException ex) {
        return Map.of("code", "INVALID_INPUT", "message", ex.getMessage());
    }
    /**
     * 将模型异常映射为稳定的 502，不返回供应商正文或调用栈。
     */
    @ExceptionHandler(EmbeddingUnavailableException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> unavailable() {
        return Map.of("code", "EMBEDDING_UNAVAILABLE", "message", "向量服务暂不可用，请稍后重试");
    }
}
