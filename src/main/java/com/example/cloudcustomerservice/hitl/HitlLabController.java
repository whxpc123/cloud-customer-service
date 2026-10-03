package com.example.cloudcustomerservice.hitl;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor;
import com.example.cloudcustomerservice.agent.DraftTaskModel.Access;
import com.example.cloudcustomerservice.handoff.HumanHandoffService;
import com.example.cloudcustomerservice.stream.StreamIdentity;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static com.example.cloudcustomerservice.hitl.HitlLabSession.*;

/** 同一任务命名空间下的受控教学入口；数据接口继续经过 Cookie、CSRF、客户角色和归属验证。 */
@RestController @RequestMapping("/internal/draft-tasks/hitl") @Profile("local & knowledge")
public class HitlLabController {
    private final HitlLabService service;
    private final HumanHandoffService handoff;
    public HitlLabController(HitlLabService service,HumanHandoffService handoff) { this.service=service; this.handoff=handoff; }
    public record Start(UUID taskId,Long draftVersion,Long expectedTaskVersion) {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void rejectUnknown(String key,Object ignored) { throw new IllegalArgumentException("不接受额外操作参数"); }
    }
    public record Decision(UUID approvalId,Long expectedVersion,Choice decision) {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void rejectUnknown(String key,Object ignored) { throw new IllegalArgumentException("不接受客户端图状态或审批身份"); }
    }
    @GetMapping(produces="text/html;charset=UTF-8") public Resource page() { return new ClassPathResource("hitl-lab/index.html"); }
    @PostMapping("/executions") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAuthority('customer:chat')")
    public View start(@RequestBody Start input,HttpSession session,HttpServletResponse response) {
        return service.start(access(session,response),input.taskId(),input.draftVersion(),input.expectedTaskVersion());
    }
    @GetMapping("/executions") @PreAuthorize("hasAuthority('customer:chat')")
    public List<HitlLabService.Summary> list(HttpSession session,HttpServletResponse response) { return service.list(access(session,response)); }
    @GetMapping("/executions/{id}") @PreAuthorize("hasAuthority('customer:chat')")
    public View get(@PathVariable UUID id,HttpSession session,HttpServletResponse response) { return service.get(access(session,response),id); }
    @PostMapping("/executions/{id}/decision") @PreAuthorize("hasAuthority('customer:chat')")
    public View decide(@PathVariable UUID id,@RequestBody Decision input,HttpSession session,HttpServletResponse response) {
        return service.decide(access(session,response),id,input.approvalId(),input.expectedVersion(),input.decision());
    }
    @DeleteMapping("/executions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasAuthority('customer:chat')")
    public void discard(@PathVariable UUID id,HttpSession session,HttpServletResponse response) { service.discard(access(session,response),id); }
    private Access access(HttpSession session,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store"); var identity=StreamIdentity.capture(session);
        return new Access(new Actor(identity.actor().tenantId(),identity.actor().accountId()),id->{
            try { return identity.read(()->handoff.get(identity.actor(),id)); }
            catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"登录状态失效，请重新登录"); }
        });
    }
}
