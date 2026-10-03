package com.example.cloudcustomerservice.workflow;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor;
import com.example.cloudcustomerservice.stream.StreamIdentity;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

/** 本机、客户登录和 CSRF 保护下的教学入口；不是售后提交接口。 */
@RestController @Profile("local & knowledge")
@RequestMapping("/internal/draft-tasks/flow")
public class SubmissionFlowController {
    private static final Logger log = LoggerFactory.getLogger(SubmissionFlowController.class);
    private final SubmissionFlowLab lab;
    public SubmissionFlowController(SubmissionFlowLab lab) { this.lab = lab; }

    /** 请求只允许选择服务器预设夹具，不能上传 State、身份、审批结果或业务对象。 */
    public record Input(SubmissionFlowLab.Scenario scenario) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("不接受额外图状态"); }
    }

    @GetMapping(produces="text/html;charset=UTF-8")
    public Resource page() { return new ClassPathResource("submission-flow/index.html"); }

    /** 图结构与场景目录只读，不执行图。与写入口保持相同客户权限。 */
    @GetMapping("/definition") @PreAuthorize("hasAuthority('customer:chat')")
    public SubmissionFlowLab.Catalog definition(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store"); return lab.catalog();
    }

    /** 当前登录身份来自 Session；实验前后都复核，不接受客户端自报用户编号。 */
    @PostMapping("/runs") @PreAuthorize("hasAuthority('customer:chat')")
    public SubmissionFlowLab.Run run(@RequestBody Input input, HttpSession session, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var identity = StreamIdentity.capture(session);
        Actor actor = authenticated(identity);
        var result = lab.run(actor, input.scenario());
        authenticated(identity);
        return result;
    }

    private static Actor authenticated(StreamIdentity identity) {
        try { return identity.read(() -> new Actor(identity.actor().tenantId(), identity.actor().accountId())); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录状态已失效，请重新登录"); }
    }

    /** 权限、输入和图执行错误分别处理。失败时没有正常 Result，更不能返回成功终点。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("message", "请选择有效场景；不能直接提交审批或图状态"));
    }
    @ExceptionHandler(SubmissionFlowLab.GraphFailed.class)
    public ResponseEntity<Map<String, String>> failed(SubmissionFlowLab.GraphFailed error) {
        log.warn("[SUBMISSION FLOW] failed errorType={}", error.getCause().getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("code", "GRAPH_FAILED", "message", "本次图运行失败，未生成正常结果，也未自动重跑。请查看服务端日志。"));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason()));
    }
}
