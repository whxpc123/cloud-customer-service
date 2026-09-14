package com.example.cloudcustomerservice.config;

import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 模型调用的日志装饰器：委托原模型完成请求，只在开关开启时观察提示词和返回值。
 * 记录的是 Spring AI 的模型调用边界，不等同于供应商 SDK 内部每一次 HTTP 往返。
 * 请求关联 ID 用来对应并发日志；正文可能含客户信息，默认不开启完整正文日志。
 */
public final class PayloadLoggingChatModel implements ChatModel {
    private static final Logger log = LoggerFactory.getLogger(PayloadLoggingChatModel.class);
    private final ChatModel delegate;
    private final boolean enabled;
    private final String clientName;

    /**
     * 保存被装饰模型和日志开关；不修改模型配置，也不额外注册 Spring Bean。
     */
    public PayloadLoggingChatModel(ChatModel delegate, boolean enabled, String clientName) {
        this.delegate = Objects.requireNonNull(delegate);
        this.enabled = enabled;
        this.clientName = clientName;
    }

    /**
     * 透传默认模型选项，让 ChatClient 合并配置时保持原模型行为。
     */
    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    /**
     * 同步调用：开关关闭直接透传，开启后用同一 ID 关联请求、响应或异常类型。
     * 异常原样向上抛出，由业务层决定降级，日志不改变调用成功与失败的语义。
     */
    @Override
    public ChatResponse call(Prompt prompt) {
        if (!enabled) {
            return delegate.call(prompt);
        }
        String id = UUID.randomUUID().toString();
        logRequest(id, prompt);
        try {
            ChatResponse response = delegate.call(prompt);
            logResponse(id, "LLM RESPONSE", response);
            return response;
        }
        catch (RuntimeException ex) {
            log.error("[LLM ERROR] client={} id={} errorType={}", clientName, id, ex.getClass().getSimpleName());
            throw ex;
        }
    }

    /**
     * 为每次订阅创建独立关联 ID，在订阅发生时才调用底层流。
     * 逐个观察分片，不拼接、不提前消费，也不吞掉响应式错误。
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // 当前接口使用同步 call；保留 ChatModel 的流式语义，按订阅记录请求与返回分片。
        return Flux.defer(() -> {
            if (!enabled) {
                return delegate.stream(prompt);
            }
            String id = UUID.randomUUID().toString();
            logRequest(id, prompt);
            return Flux.defer(() -> delegate.stream(prompt))
                    .doOnNext(response -> logResponse(id, "LLM RESPONSE CHUNK", response))
                    .doOnError(ex -> log.error("[LLM ERROR] client={} id={} errorType={}",
                            clientName, id, ex.getClass().getSimpleName()));
        });
    }

    /**
     * 输出本次工具名称、描述、输入 Schema 以及按角色分组的消息正文。
     * 只挑选可观察字段，不遍历可能包含身份和密钥的 ToolContext 或模型选项。
     */
    private void logRequest(String id, Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
            for (var callback : options.getToolCallbacks()) {
                var definition = callback.getToolDefinition();
                log.info("[TOOL DEFINITION] client={} id={} name={}\n{}\n{}", clientName, id,
                        definition.name(), definition.description(), definition.inputSchema());
            }
        }
        StringBuilder messages = new StringBuilder();
        for (var message : prompt.getInstructions()) {
            messages.append("\n--- ").append(message.getMessageType().name()).append(" ---\n")
                    .append(message.getText()).append('\n');
        }
        log.info("[LLM REQUEST] client={} id={}\n{}", clientName, id, messages);
    }

    /**
     * 逐候选记录转换前文本；允许空响应并明确标记，避免日志代码反而触发空指针。
     */
    private void logResponse(String id, String label, ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            log.info("[{}] client={} id={} <empty response>", label, clientName, id);
            return;
        }
        for (int i = 0; i < response.getResults().size(); i++) {
            var output = response.getResults().get(i).getOutput();
            log.info("[{}] client={} id={} candidate={}\n{}", label, clientName, id, i,
                    output == null ? "<empty output>" : output.getText());
        }
    }
}
