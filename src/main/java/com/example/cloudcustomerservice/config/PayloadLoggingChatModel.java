package com.example.cloudcustomerservice.config;

import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** 在 ChatModel 边界观察最终消息及转换前的返回文本，不打印 HTTP 请求头或模型配置。 */
public final class PayloadLoggingChatModel implements ChatModel {
    private static final Logger log = LoggerFactory.getLogger(PayloadLoggingChatModel.class);
    private final ChatModel delegate;
    private final boolean enabled;
    private final String clientName;

    public PayloadLoggingChatModel(ChatModel delegate, boolean enabled, String clientName) {
        this.delegate = Objects.requireNonNull(delegate);
        this.enabled = enabled;
        this.clientName = clientName;
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

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

    private void logRequest(String id, Prompt prompt) {
        StringBuilder messages = new StringBuilder();
        for (var message : prompt.getInstructions()) {
            messages.append("\n--- ").append(message.getMessageType().name()).append(" ---\n")
                    .append(message.getText()).append('\n');
        }
        log.info("[LLM REQUEST] client={} id={}\n{}", clientName, id, messages);
    }

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
