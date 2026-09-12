package com.example.cloudcustomerservice;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实启动 Spring 上下文，替换模型以避免测试消耗 API 额度。 */
@SpringBootTest(properties = "spring.ai.dashscope.api-key=offline-test-placeholder")
@AutoConfigureMockMvc
class CloudCustomerServiceApplicationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatModel chatModel;

    @Test
    void chatPassesChineseMessageThroughChatClientAndReturnsPlainText() throws Exception {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("你好！有什么我可以帮助你的吗？")))));

        mockMvc.perform(get("/api/chat").param("message", "你好"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(content().string("你好！有什么我可以帮助你的吗？"));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getUserMessage().getText()).isEqualTo("你好");
    }

    @Test
    void missingMessageReturnsBadRequestWithoutCallingModel() throws Exception {
        mockMvc.perform(get("/api/chat")).andExpect(status().isBadRequest());
        // ChatClient 初始化时会读取默认配置；此处只验证没有发起模型请求。
        verify(chatModel, never()).call(any(Prompt.class));
    }
}
