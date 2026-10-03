package com.example.cloudcustomerservice.stream;

import com.example.cloudcustomerservice.handoff.HumanHandoffService;
import jakarta.servlet.http.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

/** 第十八章：SSE 只读接待快照；订阅、重连和 Last-Event-ID 均不发起模型或业务操作。 */
@RestController @Profile("local & knowledge") @PreAuthorize("hasAuthority('customer:chat')")
public class ConversationStateStreamController {
    private final HumanHandoffService handoff;
    private final Scheduler scheduler;
    private final SseSettings settings;
    private final SseConnections connections;
    public ConversationStateStreamController(HumanHandoffService handoff,@Qualifier("sseReadScheduler") Scheduler scheduler,
            SseSettings settings,SseConnections connections) {
        this.handoff=handoff;this.scheduler=scheduler;this.settings=settings;this.connections=connections;
    }
    @GetMapping(value="/api/handoff/conversations/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> events(@PathVariable UUID id,HttpServletRequest request,HttpServletResponse response) {
        var identity=StreamIdentity.capture(request.getSession(false));
        // 开始 SSE 前用正常请求线程完成认证与所有权检查；失败仍是普通 401/403/404。
        var initial=handoff.get(identity.actor(),id);
        headers(response);
        return connections.limit(identity.actor(),SseStreams.states(initial,
                () -> identity.read(() -> handoff.get(identity.actor(),id)),scheduler,settings));
    }
    static void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Accel-Buffering","no");
    }
}
