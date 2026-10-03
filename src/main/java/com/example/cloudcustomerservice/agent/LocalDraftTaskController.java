package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import com.example.cloudcustomerservice.handoff.HumanHandoffService;
import com.example.cloudcustomerservice.stream.StreamIdentity;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.example.cloudcustomerservice.agent.DraftTaskModel.*;

/** 身份、会话和接待状态沿用已验证入口；只允许传业务 taskId，不收 threadId 或 checkpointId。 */
@RestController @RequestMapping("/internal/local-draft-tasks") @Profile("local & knowledge")
public class LocalDraftTaskController {
    private final LocalDraftTaskService tasks;
    private final HumanHandoffService handoff;
    public LocalDraftTaskController(LocalDraftTaskService tasks,HumanHandoffService handoff){this.tasks=tasks;this.handoff=handoff;}
    public record Create(UUID conversationId,String orderNo,ReturnReason reason) { }
    public record Continue(String message) { }
    @GetMapping(produces="text/html;charset=UTF-8") public Resource page(){return new ClassPathResource("draft-tasks/index.html");}
    @GetMapping("/tasks") @PreAuthorize("hasAuthority('customer:chat')")
    public List<TaskSummary> list(HttpSession session,HttpServletResponse response){return tasks.list(access(session,response).actor());}
    @PostMapping("/tasks") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAuthority('customer:chat')")
    public TaskSummary create(@RequestBody Create input,HttpSession session,HttpServletResponse response){return tasks.create(access(session,response),input.conversationId(),input.orderNo(),input.reason());}
    @GetMapping("/tasks/{id}") @PreAuthorize("hasAuthority('customer:chat')")
    public TaskView get(@PathVariable UUID id,HttpSession session,HttpServletResponse response){return tasks.get(access(session,response),id);}
    @PostMapping("/tasks/{id}/turns") @PreAuthorize("hasAuthority('customer:chat')")
    public TaskView next(@PathVariable UUID id,@RequestBody Continue input,HttpSession session,HttpServletResponse response){return tasks.continueTask(access(session,response),id,input.message());}
    @DeleteMapping("/tasks/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasAuthority('customer:chat')")
    public void discard(@PathVariable UUID id,HttpSession session,HttpServletResponse response){tasks.discard(access(session,response),id);}
    private Access access(HttpSession session,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");var identity=StreamIdentity.capture(session);
        return new Access(new Actor(identity.actor().tenantId(),identity.actor().accountId()),id->identity.read(()->handoff.get(identity.actor(),id)));
    }
}
