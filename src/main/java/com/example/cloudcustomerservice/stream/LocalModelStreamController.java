package com.example.cloudcustomerservice.stream;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.example.cloudcustomerservice.config.PayloadLoggingChatModel;
import com.example.cloudcustomerservice.security.HandoffIdentity;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/** 独立传输实验，不装配记忆、RAG 或业务工具；不调用正式消息发布层，不宣称业务执行成功。 */
@RestController @RequestMapping("/internal/stream-lab") @Profile("local & knowledge")
public class LocalModelStreamController {
    private final ChatClient client;
    private final ChatModel model;
    private final SseSettings settings;
    private final SseConnections connections;
    public LocalModelStreamController(ChatModel model,SseSettings settings,SseConnections connections,
            @Value("${app.ai.log-payload:false}") boolean logPayload) {
        this.model=model;this.settings=settings;this.connections=connections;
        // 显式启用供应商增量输出并禁止内部工具执行；不继承任何业务 ChatClient 的 Advisor。
        client=ChatClient.builder(new PayloadLoggingChatModel(model,logPayload,"streamLabChatClient"))
                .defaultOptions(DashScopeChatOptions.builder().maxToken(512).incrementalOutput(true)
                        .internalToolExecutionEnabled(false).toolNames(Set.of()).toolCallbacks(List.of()).tools(List.of()).build())
                .defaultSystem("""
                    你是本地流式传输实验助手，使用简洁中文回答一般性问题。
                    没有查询订单、检索商城制度、办理售后或执行退款的能力。
                    不得声称已经查询真实订单、批准退货、执行退款或转人工。
                    不输出个人敏感资料。遇到业务操作要求，说明本页面只做文字传输实验。
                    """).build();
    }
    public record Request(String question) { }
    @GetMapping(produces="text/html;charset=UTF-8") public Resource page() { return new ClassPathResource("stream-lab/index.html"); }
    @PostMapping(value="/answer",produces=MediaType.TEXT_EVENT_STREAM_VALUE) @PreAuthorize("isAuthenticated()")
    public Flux<ServerSentEvent<SseStreams.AnswerEvent>> answer(@RequestBody Request request,HttpServletResponse response) {
        if(request.question()==null||request.question().isBlank()||request.question().length()>2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"问题须为 1～2000 字符");
        // 若未来有人在共享 ChatModel 配置全局工具，实验入口直接拒绝，不能靠 Prompt 假装工具不可用。
        var options=model.getDefaultOptions();
        if(options instanceof ToolCallingChatOptions tools && (!tools.getToolNames().isEmpty()||!tools.getToolCallbacks().isEmpty())
                ||options instanceof DashScopeChatOptions dash && dash.getTools()!=null&&!dash.getTools().isEmpty())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"模型配置含全局工具，不能用于本地流式实验");
        ConversationStateStreamController.headers(response);
        return connections.limit(HandoffIdentity.actor(),SseStreams.answer(
                () -> client.prompt().user(request.question()).stream().content(),settings));
    }
}
