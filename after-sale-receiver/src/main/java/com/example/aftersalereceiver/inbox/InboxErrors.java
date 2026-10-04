package com.example.aftersalereceiver.inbox;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 只输出稳定错误码；SQL、令牌、正文及异常堆栈均不进入 HTTP 错误响应。 */
@RestControllerAdvice
public class InboxErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> protocol(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("code",
                error.getReason() == null ? "INVALID_REQUEST" : error.getReason()));
    }

    /** 锁等待、连接中断、提交失败都没有成功回执；发送方可保留原事件重试。 */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<?> database(Exception error) {
        return ResponseEntity.status(503).body(Map.of("code", "DATABASE_RESULT_UNCONFIRMED"));
    }

    @ExceptionHandler({ServletRequestBindingException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> header(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_EVENT_HEADER"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception error) {
        // 保留框架的 400/405/415 等协议状态；不能把永久输入错误变成可重试 500。
        if (error instanceof org.springframework.web.ErrorResponse response) {
            return ResponseEntity.status(response.getStatusCode()).body(Map.of("code", "INVALID_HTTP_REQUEST"));
        }
        return ResponseEntity.status(500).body(Map.of("code", "INBOX_RESULT_UNCONFIRMED"));
    }
}
