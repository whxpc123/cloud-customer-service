package com.example.cloudcustomerservice.submission;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor;
import com.example.cloudcustomerservice.stream.StreamIdentity;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.servlet.http.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 本机 Session / 客户角色 / CSRF 入口；客户端不提供身份、正文、审批人或图状态。 */
@RestController @Profile("local & knowledge") @RequestMapping("/internal/draft-tasks/submission")
public class SubmissionController {
    private final IdempotentSubmissionService service;
    private final SubmissionGraph graph;
    public SubmissionController(IdempotentSubmissionService service,SubmissionGraph graph){this.service=service;this.graph=graph;}
    public record Prepare(UUID taskId,Long draftVersion){@JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("多余字段");}}
    public record Decide(IdempotentSubmissionService.Decision decision,Boolean accepted){@JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("多余字段");}}
    public record Execute(){@JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("执行不接受业务参数");}}
    @GetMapping(produces="text/html;charset=UTF-8") public Resource page(){return new ClassPathResource("submission/index.html");}
    @GetMapping("/operations") @PreAuthorize("hasAuthority('customer:chat')")
    public List<IdempotentSubmissionService.Operation> list(HttpSession s,HttpServletResponse r){return authorized(s,r,service::list);}
    @GetMapping("/operations/{id}") @PreAuthorize("hasAuthority('customer:chat')")
    public IdempotentSubmissionService.Detail detail(@PathVariable UUID id,HttpSession s,HttpServletResponse r){return authorized(s,r,a->service.detail(a,id));}
    @PostMapping("/operations") @PreAuthorize("hasAuthority('customer:chat')")
    public IdempotentSubmissionService.Operation prepare(@RequestBody Prepare input,HttpSession s,HttpServletResponse r){
        if(input.taskId()==null||input.draftVersion()==null)throw new IllegalArgumentException("需要任务和具体草稿版本");
        return authorized(s,r,a->service.prepare(a,input.taskId(),input.draftVersion()));
    }
    @PostMapping("/operations/{id}/decision") @PreAuthorize("hasAuthority('customer:chat')")
    public IdempotentSubmissionService.Operation decide(@PathVariable UUID id,@RequestBody Decide input,HttpSession s,HttpServletResponse r){
        if(input.decision()==IdempotentSubmissionService.Decision.APPROVE&&!Boolean.TRUE.equals(input.accepted()))throw new IllegalArgumentException("批准需明确接受本次操作");
        return authorized(s,r,a->service.decide(a,id,input.decision()));
    }
    /** 执行只绑定路径中的稳定编号。所有返回都须看业务状态，HTTP 200 不代表申请一定已创建。 */
    @PostMapping("/operations/{id}/execute") @PreAuthorize("hasAuthority('customer:chat')")
    public SubmissionGraph.Execution execute(@PathVariable UUID id,@RequestBody Execute ignored,HttpSession s,HttpServletResponse r){return authorized(s,r,a->graph.execute(a,id));}
    @GetMapping("/operations/{id}/result") @PreAuthorize("hasAuthority('customer:chat')")
    public IdempotentSubmissionService.ResultQuery result(@PathVariable UUID id,HttpSession s,HttpServletResponse r){return authorized(s,r,a->service.result(a,id));}

    /** 前后复核当前 Session；即使响应阶段失效，客户端也只能用原 operationId 查回执。 */
    private <T>T authorized(HttpSession s,HttpServletResponse r,Function<Actor,T> work){
        r.setHeader("Cache-Control","no-store");var identity=StreamIdentity.capture(s);
        check(identity);T value=work.apply(new Actor(identity.actor().tenantId(),identity.actor().accountId()));check(identity);return value;
    }
    private void check(StreamIdentity identity){try{identity.read(()->true);}catch(IllegalStateException e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"登录已失效，请重新登录后用原操作编号查询");}}
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class}) public ResponseEntity<Map<String,String>> invalid(){return ResponseEntity.badRequest().body(Map.of("message","请求不完整或包含不允许的字段；批准需明确勾选接受"));}
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<Map<String,String>> status(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",Objects.requireNonNullElse(e.getReason(),"请求不可用")));}
    /** SQL/提交确认异常不暴露数据库细节，也不许 UI 宣称没有创建。 */
    @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,String>> failure(Exception e){return ResponseEntity.status(503).body(Map.of("message","本次请求未确认完成，请保留原操作编号并查询结果；未自动重试"));}
}
