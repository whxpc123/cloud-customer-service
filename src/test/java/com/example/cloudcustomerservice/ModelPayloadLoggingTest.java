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

@SpringBootTest(properties = {
        "spring.ai.dashscope.api-key=offline-test-placeholder",
        "app.ai.log-payload=true"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ModelPayloadLoggingTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ChatModel chatModel;

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

    @Test
    void logsBothSidesOfCustomerChat(CapturedOutput output) throws Exception {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("TRACE_CHAT_REPLY"));
        mvc.perform(get("/api/chat").param("message", "TRACE_CHAT_INPUT"))
                .andExpect(status().isOk()).andExpect(content().string("TRACE_CHAT_REPLY"));
        assertThat(output.getAll()).contains("[LLM REQUEST] client=customerServiceChatClient", "TRACE_CHAT_INPUT",
                "[LLM RESPONSE] client=customerServiceChatClient", "TRACE_CHAT_REPLY", "云杉商城");
    }

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

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
