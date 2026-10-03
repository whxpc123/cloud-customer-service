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
import static com.example.cloudcustomerservice.agent.PersistentDraftTaskService.*;

/** 任务主入口切换到数据库版本；旧内存实验保留独立 URL，不迁移或混用旧 taskId。 */
@RestController @RequestMapping("/internal/draft-tasks") @Profile("local & knowledge")
public class PersistentDraftTaskController {
    private final PersistentDraftTaskService tasks;
    private final HumanHandoffService handoff;
    public PersistentDraftTaskController(PersistentDraftTaskService tasks,HumanHandoffService handoff){this.tasks=tasks;this.handoff=handoff;}
    /** 创建时只接收会话及固定业务范围；账户和租户始终取当前登录身份。 */
    public record Create(UUID conversationId,String orderNo,ReturnReason reason) { }
    /** 只提交本轮输入及最近读取的业务版本，不接受客户端传入内部检查点。 */
    public record Continue(Long expectedVersion,String message) { }
    /** 结束同样需要版本，避免陈旧页面误关闭已经发生变化的任务。 */
    public record Close(Long expectedVersion) { }
    /** 页面壳可匿名加载，所有数据和操作仍由下方接口独立鉴权。 */
    @GetMapping(produces="text/html;charset=UTF-8") public Resource page(){return new ClassPathResource("persistent-draft-tasks/index.html");}
    /** 列出当前所有者元数据；不在列表中传回候选正文。 */
    @GetMapping("/tasks") @PreAuthorize("hasAuthority('customer:chat')")
    public List<Summary> list(HttpSession session,HttpServletResponse response){return tasks.list(access(session,response).actor());}
    /** 创建 READY 记录，不执行图或模型。 */
    @PostMapping("/tasks") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAuthority('customer:chat')")
    public Summary create(@RequestBody Create input,HttpSession session,HttpServletResponse response){return tasks.create(access(session,response),input.conversationId(),input.orderNo(),input.reason());}
    /** 读取已存业务快照；刷新页面不会再次运行 Agent。 */
    @GetMapping("/tasks/{id}") @PreAuthorize("hasAuthority('customer:chat')")
    public View get(@PathVariable UUID id,HttpSession session,HttpServletResponse response){return tasks.get(access(session,response),id);}
    /** 一次显式请求执行一轮；状态冲突返回 409，不在控制器中重试。 */
    @PostMapping("/tasks/{id}/turns") @PreAuthorize("hasAuthority('customer:chat')")
    public View next(@PathVariable UUID id,@RequestBody Continue input,HttpSession session,HttpServletResponse response){return tasks.continueTask(access(session,response),id,input.expectedVersion(),input.message());}
    /** 结束后保留历史，与旧内存实验的 DELETE 清理不同。 */
    @PostMapping("/tasks/{id}/close") @PreAuthorize("hasAuthority('customer:chat')")
    public View close(@PathVariable UUID id,@RequestBody Close input,HttpSession session,HttpServletResponse response){return tasks.close(access(session,response),id,input.expectedVersion());}
    /** 捕获身份而非信任请求头；延迟回调每次核对 Session 是否仍有效及当前接待状态。 */
    private Access access(HttpSession session,HttpServletResponse response){response.setHeader("Cache-Control","no-store");var identity=StreamIdentity.capture(session);
        return new Access(new Actor(identity.actor().tenantId(),identity.actor().accountId()),id->identity.read(()->handoff.get(identity.actor(),id)));}
}
