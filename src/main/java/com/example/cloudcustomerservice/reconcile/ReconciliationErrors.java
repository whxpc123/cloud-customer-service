package com.example.cloudcustomerservice.reconcile;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 网络观察与本地落库错误分开：本地异常后先刷新审计，不断言“远端未发生”或“本地肯定未保存”。 */
@RestControllerAdvice(assignableTypes=OutboxReconciliationController.class) @Profile("local & knowledge")
public class ReconciliationErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"无法完成核查":e.getReason()));}
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> input(Exception e){return ResponseEntity.badRequest().body(Map.of("message","核查只接受原事件编号和空对象，不接受身份、正文或强制状态。"));}
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionException.class,
            IllegalStateException.class,com.fasterxml.jackson.core.JsonProcessingException.class})
    ResponseEntity<?> uncertain(Exception e){return ResponseEntity.status(503).body(Map.of("message","暂时无法确认本地核查记录是否保存。请刷新原事件和审计记录；没有自动重新投递。"));}
}
