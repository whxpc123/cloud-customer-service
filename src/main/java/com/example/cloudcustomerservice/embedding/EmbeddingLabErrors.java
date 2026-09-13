package com.example.cloudcustomerservice.embedding;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = EmbeddingLabController.class)
@Profile("local")
public class EmbeddingLabErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidInput(IllegalArgumentException ex) {
        return Map.of("code", "INVALID_INPUT", "message", ex.getMessage());
    }
    @ExceptionHandler(EmbeddingUnavailableException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> unavailable() {
        return Map.of("code", "EMBEDDING_UNAVAILABLE", "message", "向量服务暂不可用，请稍后重试");
    }
}
