package com.example.cloudcustomerservice;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import com.example.cloudcustomerservice.config.PayloadLoggingChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观察模型日志装饰器的集成与单元测试，CapturedOutput 捕获日志，模型返回由测试控制。
 * 检验最终提示词、原始输出和关闭日志时的透传语义；流式分支验证按订阅延迟调用。
 */

@SpringBootTest(properties = {
        "spring.ai.dashscope.api-key=offline-test-placeholder",
        "app.ai.log-payload=true"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ModelPayloadLoggingTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ChatModel chatModel;

    /**
     * 返回需要后续校验处理的模型 JSON，确认日志能看到最终 Schema 提示和转换前原始文本。
     */
    @Test
    void logsFinalSchemaPromptAndRawReplyBeforeValidation(CapturedOutput output) throws Exception {
        String raw = "{\"intent\":\"REFUND_REQUEST\",\"orderNo\":null,\"confidence\":1.7,\"missingFields\":[]}";
        when(chatModel.call(any(Prompt.class))).thenReturn(response(raw));
        mvc.perform(post("/api/intents/recognize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"TRACE_CUSTOMER_MESSAGE 我要退款\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("UNKNOWN"));
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(output.getAll()).contains("[LLM REQUEST] client=intentChatClient", "--- SYSTEM ---",
                        "--- USER ---", prompt.getValue().getSystemMessage().getText(),
                        prompt.getValue().getUserMessage().getText(), "Your response should be in JSON format.",
                        "[LLM RESPONSE] client=intentChatClient", raw)
                .doesNotContain("offline-test-placeholder", "Authorization:");
        var matcher = java.util.regex.Pattern.compile("\\[LLM REQUEST] client=intentChatClient id=([a-f0-9-]+)")
                .matcher(output.getAll());
        assertThat(matcher.find()).isTrue();
        assertThat(output.getAll()).contains("[LLM RESPONSE] client=intentChatClient id=" + matcher.group(1));
    }

    /**
     * 经过聊天 HTTP 入口开启正文日志，核对请求和模型返回两侧都被记录。
     */
    @Test
    void logsBothSidesOfCustomerChat(CapturedOutput output) throws Exception {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("TRACE_CHAT_REPLY"));
        mvc.perform(get("/api/chat").param("message", "TRACE_CHAT_INPUT"))
                .andExpect(status().isOk()).andExpect(content().string("TRACE_CHAT_REPLY"));
        assertThat(output.getAll()).contains("[LLM REQUEST] client=customerServiceChatClient", "TRACE_CHAT_INPUT",
                "[LLM RESPONSE] client=customerServiceChatClient", "TRACE_CHAT_REPLY", "云杉商城");
    }

    /**
     * 关闭日志装饰器后，比较委托调用结果与日志，确认没有额外正文输出或结果改写。
     */
    @Test
    void disabledWrapperPassesThroughWithoutPayloadLogs(CapturedOutput output) {
        ChatModel delegate = mock(ChatModel.class);
        var wrapper = new PayloadLoggingChatModel(delegate, false, "disabled-client");
        var prompt = new Prompt("UNLOGGED_INPUT");
        var result = response("UNLOGGED_OUTPUT");
        when(delegate.call(prompt)).thenReturn(result);
        assertThat(wrapper.call(prompt)).isSameAs(result);
        verify(delegate).call(prompt);
        assertThat(output.getAll()).doesNotContain("[LLM REQUEST]", "[LLM RESPONSE]", "UNLOGGED_INPUT", "UNLOGGED_OUTPUT");
    }

    /**
     * 构造延迟流并订阅，验证订阅前不调用模型，订阅后的分片顺序和错误语义保持不变。
     */
    @Test
    void streamsLazilyWithoutChangingChunks(CapturedOutput output) {
        ChatModel delegate = mock(ChatModel.class);
        var wrapper = new PayloadLoggingChatModel(delegate, true, "stream-client");
        var prompt = new Prompt("STREAM_INPUT");
        var first = response("chunk-one");
        var second = response("chunk-two");
        when(delegate.stream(prompt)).thenReturn(Flux.just(first, second));
        var stream = wrapper.stream(prompt);
        assertThat(output.getAll()).doesNotContain("STREAM_INPUT");
        assertThat(stream.collectList().block()).containsExactly(first, second);
        assertThat(output.getAll()).contains("STREAM_INPUT", "[LLM RESPONSE CHUNK]", "chunk-one", "chunk-two");
    }

    /**
     * 构造或配置只有一条助手输出的模型响应，让测试精确控制本次生成文本。
     */
    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
