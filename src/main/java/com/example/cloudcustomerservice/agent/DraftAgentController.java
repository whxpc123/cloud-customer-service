package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel;
import com.example.cloudcustomerservice.handoff.*;
import com.example.cloudcustomerservice.stream.StreamIdentity;
import jakarta.servlet.http.*;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 独立审阅实验入口，复用真实登录与会话所有权；不调用 begin/finish，不写正式消息或持久化草稿。 */
@RestController @RequestMapping("/internal/draft-agent") @Profile("local & knowledge")
public class DraftAgentController {
    private final HumanHandoffService handoff;
    private final AfterSaleDraftAgentService agent;
    public DraftAgentController(HumanHandoffService handoff,AfterSaleDraftAgentService agent) {this.handoff=handoff;this.agent=agent;}
    public record Request(UUID conversationId,String orderNo,AfterSaleModel.ReturnReason reason,String task,boolean inspectionOnly) { }
    @GetMapping(produces="text/html;charset=UTF-8") public Resource page() {return new ClassPathResource("draft-agent/index.html");}
    @PostMapping("/runs") @PreAuthorize("hasAuthority('customer:chat')")
    public DraftRun run(@RequestBody Request input,HttpSession session,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        if(input.conversationId()==null)throw new IllegalArgumentException("需要会话编号");
        var identity=StreamIdentity.capture(session);
        var before=identity.read(()->handoff.get(identity.actor(),input.conversationId()));
        if(before.mode()!=HandoffModel.Mode.BOT)throw new ResponseStatusException(HttpStatus.CONFLICT,"只有机器人接待中的会话可以运行草稿实验");
        var result=agent.prepare(new AfterSaleModel.Actor(identity.actor().tenantId(),identity.actor().accountId()),
                input.orderNo(),input.reason(),input.task(),input.inspectionOnly());
        // 长任务结束后重读 Session 和数据库；注销、转人工、结束或版本变动都不能暴露迟到结果。
        try {
            var after=identity.read(()->handoff.get(identity.actor(),input.conversationId()));
            return after.mode()==HandoffModel.Mode.BOT&&after.version()==before.version()?result:result.hidden();
        } catch(RuntimeException ex) {return result.hidden();}
    }
}
