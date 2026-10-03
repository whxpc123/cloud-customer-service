package com.example.cloudcustomerservice.handoff;

import java.util.Map;
import com.example.cloudcustomerservice.routing.LocalRoutingController;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 数据库或提交确认失败时只说无法确认；客户端通过 GET 状态或同会话重试恢复，不把超时当作失败凭证。 */
@RestControllerAdvice(assignableTypes={HumanHandoffController.class,LocalRoutingController.class,com.example.cloudcustomerservice.stream.ConversationStateStreamController.class,com.example.cloudcustomerservice.stream.LocalModelStreamController.class})
@Profile("local & knowledge")
public class HandoffErrors {
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<Map<String,String>> status(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(Map.of("message",e.getReason()==null?"请求无法完成":e.getReason()));}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Map.of("message","输入不合法，请检查问题、消息编号或游标"));}
    @ExceptionHandler({DataAccessException.class,org.springframework.transaction.TransactionException.class})
    public ResponseEntity<Map<String,String>> unavailable(RuntimeException e){return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON).body(Map.of("message","暂时无法确认接待或消息状态，请稍后查询；不要据此认定申请未受理。"));}
}
