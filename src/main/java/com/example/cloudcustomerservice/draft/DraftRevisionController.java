package com.example.cloudcustomerservice.draft;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor;
import com.example.cloudcustomerservice.agent.DraftTaskModel.Access;
import com.example.cloudcustomerservice.handoff.HumanHandoffService;
import com.example.cloudcustomerservice.stream.StreamIdentity;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.example.cloudcustomerservice.draft.DraftModels.*;

/** 延续同一持久化任务入口、Cookie/CSRF 与客户权限；正文只在当前所有者通过授权后返回。 */
@RestController @RequestMapping("/internal/draft-tasks/tasks/{taskId}") @Profile("local & knowledge")
@PreAuthorize("hasAuthority('customer:chat')")
public class DraftRevisionController {
    private final DraftApplicationService service;
    private final HumanHandoffService handoff;
    public DraftRevisionController(DraftApplicationService service,HumanHandoffService handoff){this.service=service;this.handoff=handoff;}
    /** 包装类型区分漏填和 0，服务层显式验证；不允许从客户端指定草稿正文。 */
    public record Generate(Long expectedTaskVersion) { }
    /** accepted 必须显式 true；业务版本是页面实际展示的版本，不能后台换成“最新”。 */
    public record Confirm(Long draftVersion,Boolean accepted) { }
    @PostMapping("/drafts") @ResponseStatus(HttpStatus.CREATED)
    public View generate(@PathVariable UUID taskId,@RequestBody Generate body,HttpSession session,HttpServletResponse response){
        return service.generate(access(session,response),taskId,body.expectedTaskVersion());}
    @GetMapping("/drafts")
    public List<RevisionSummary> list(@PathVariable UUID taskId,HttpSession session,HttpServletResponse response){return service.list(access(session,response),taskId);}
    @GetMapping("/drafts/{version}")
    public View read(@PathVariable UUID taskId,@PathVariable Long version,HttpSession session,HttpServletResponse response){return service.read(access(session,response),taskId,version);}
    /** 不经过模型的真实用户确认；返回数据库中的确认人、时间与唯一回执编号。 */
    @PostMapping("/draft-confirmations")
    public Confirmation confirm(@PathVariable UUID taskId,@RequestBody Confirm body,HttpSession session,HttpServletResponse response){
        return service.confirm(access(session,response),taskId,body.draftVersion(),body.accepted());}
    private Access access(HttpSession session,HttpServletResponse response){response.setHeader("Cache-Control","no-store");var identity=StreamIdentity.capture(session);
        return new Access(new Actor(identity.actor().tenantId(),identity.actor().accountId()),id->{try{return identity.read(()->handoff.get(identity.actor(),id));}
            catch(IllegalStateException ex){throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED,"登录状态已失效，未继续草稿操作");}});}
}
