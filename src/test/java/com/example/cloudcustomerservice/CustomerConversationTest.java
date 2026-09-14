package com.example.cloudcustomerservice;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import com.example.cloudcustomerservice.intent.CustomerIntent;
import com.example.cloudcustomerservice.intent.CustomerIntentRecognizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 第四章会话记忆集成测试，使用真实窗口 Advisor 与模拟模型观察两条客户端调用链。
 * 每例使用独立 UUID，避免共享 Spring 容器中的内存历史串到其他测试。
 */

@SpringBootTest(properties = "spring.ai.dashscope.api-key=offline-test-placeholder")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class CustomerConversationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired @Qualifier("customerChatMemory") private ChatMemory memory;
    @Autowired private CustomerIntentRecognizer recognizer;
    @MockitoBean private ChatModel model;

    /**
     * 新建两次会话应返回不同 UUID；创建会话只分配标识，不应产生模型调用。
     */
    @Test
    void createsUniqueConversationIdsWithoutCallingModel() throws Exception {
        String first = mapper.readTree(mvc.perform(post("/api/conversations"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("conversationId").asText();
        String second = mapper.readTree(mvc.perform(post("/api/conversations"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("conversationId").asText();
        assertThat(UUID.fromString(first)).isNotEqualTo(UUID.fromString(second));
        verify(model, never()).call(any(Prompt.class));
    }

    /**
     * 第二轮使用“它”指代前一订单，捕获分类与客服两份提示词，并核对记忆中只存真实对话而无分类 JSON。
     */
    @Test
    void secondTurnReadsHistoryForBothClientsAndOnlyStoresRealDialogue() throws Exception {
        String id = id();
        when(model.call(any(Prompt.class))).thenReturn(
                response(intent("ORDER_QUERY", "A10001")), response("已了解您提到的订单。"),
                response(intent("REFUND_REQUEST", "A10001")), response("您是想退掉 A10001 吗？目前无法办理退款。"));
        send(id, "我的订单是 A10001。").andExpect(status().isOk());
        send(id, "我想把它退掉。").andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(id))
                .andExpect(jsonPath("$.intent.intent").value("REFUND_REQUEST"))
                .andExpect(jsonPath("$.intent.orderNo").value("A10001"))
                .andExpect(jsonPath("$.intent.missingFields").isEmpty());
        var prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(4)).call(prompts.capture());
        var classifier = prompts.getAllValues().get(2);
        assertThat(classifier.getUserMessage().getText()).contains("[USER] 我的订单是 A10001。",
                "[ASSISTANT] 已了解您提到的订单。", "我想把它退掉。", "Your response should be in JSON format.");
        var chat = prompts.getAllValues().get(3);
        assertThat(chat.getInstructions()).filteredOn(m -> m.getMessageType() == MessageType.USER)
                .extracting(m -> m.getText()).containsExactly("我的订单是 A10001。", "我想把它退掉。");
        assertThat(memory.get(id)).extracting(m -> m.getMessageType())
                .containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.USER, MessageType.ASSISTANT);
        assertThat(memory.get(id)).extracting(m -> m.getText())
                .containsExactly("我的订单是 A10001。", "已了解您提到的订单。", "我想把它退掉。", "您是想退掉 A10001 吗？目前无法办理退款。");
    }

    /**
     * 为会话 A 预置订单，再访问 B 及清空 A，验证两个客户端都不能看到不属于当前窗口的上下文。
     */
    @Test
    void separatesSessionsAndClearRemovesContextForBothClients() throws Exception {
        String a = id(), b = id();
        memory.add(a, List.of(new UserMessage("我的订单 A10001"), new AssistantMessage("请继续")));
        when(model.call(any(Prompt.class))).thenReturn(response(intent("ORDER_QUERY", "A10001")), response("请提供订单号"));
        send(b, "我刚才的订单是什么？").andExpect(status().isOk())
                .andExpect(jsonPath("$.intent.intent").value("UNKNOWN"));
        mvc.perform(delete("/api/conversations/{id}/memory", a)).andExpect(status().isNoContent());
        assertThat(memory.get(a)).isEmpty();
        send(a, "我刚才的订单是什么？").andExpect(status().isOk());
        var prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(4)).call(prompts.capture());
        for (var prompt : prompts.getAllValues()) {
            assertThat(prompt.getContents()).doesNotContain("A10001");
        }
        assertThat(memory.get(b)).hasSize(2);
    }

    /**
     * 只在助手历史放入虚构订单，模拟模型提取它；应降级 UNKNOWN，并保持调用前后的历史完全一致。
     */
    @Test
    void classifierIsReadOnlyAndRejectsOrderOnlyInventedByAssistant() {
        String id = id();
        memory.add(id, List.of(new UserMessage("我想退款"), new AssistantMessage("虚构的订单 Z99999")));
        var before = List.copyOf(memory.get(id));
        when(model.call(any(Prompt.class))).thenReturn(response(intent("REFUND_REQUEST", "Z99999")));
        assertThat(recognizer.recognize(id, "把它退了").intent()).isEqualTo(CustomerIntent.UNKNOWN);
        assertThat(memory.get(id)).isEqualTo(before);
        assertThat(recognizer.recognize("把它退了").intent()).isEqualTo(CustomerIntent.UNKNOWN);
        assertThat(memory.get(id)).isEqualTo(before);
    }

    /**
     * 发满窗口触发旧消息淘汰，再让模型返回已淘汰订单；验证窗口上限和原文来源校验共同生效。
     */
    @Test
    void windowEvictsOldMessagesAndDoesNotAcceptEvictedOrder() throws Exception {
        String id = id();
        memory.add(id, List.of(new UserMessage("订单 OLD100"), new AssistantMessage("第一轮")));
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt p = invocation.getArgument(0);
            return response(p.getSystemMessage().getText().contains("意图识别器")
                    ? intent("OTHER", null) : "本轮回复");
        });
        for (int i = 0; i < 10; i++) send(id, "后续话题 " + i).andExpect(status().isOk());
        assertThat(memory.get(id)).hasSize(20);
        assertThat(memory.get(id)).extracting(m -> m.getText()).doesNotContain("订单 OLD100", "第一轮");
        when(model.call(any(Prompt.class))).thenReturn(response(intent("ORDER_QUERY", "OLD100")));
        assertThat(recognizer.recognize(id, "之前的订单呢").intent()).isEqualTo(CustomerIntent.UNKNOWN);
    }

    /**
     * 覆盖空消息、超长消息、非法 JSON 和会话 ID，确认请求被拒绝且没有写记忆或调用模型。
     */
    @Test
    void invalidRequestsAre400AndNeverCallModel() throws Exception {
        String id = id();
        for (String message : List.of("", " \n", "长".repeat(4001))) {
            send(id, message).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/conversations/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/conversations/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
                .content("{")).andExpect(status().isBadRequest());
        send("id with spaces", "你好").andExpect(status().isBadRequest());
        send("x".repeat(101), "你好").andExpect(status().isBadRequest());
        mvc.perform(delete("/api/conversations/{id}/memory", "bad id")).andExpect(status().isBadRequest());
        assertThat(memory.get(id)).isEmpty();
        verify(model, never()).call(any(Prompt.class));
    }

    /**
     * 区分分类失败降级与回复失败 502；同时记录 Advisor 可能已保存用户消息的边界，检查日志不泄露原始错误。
     */
    @Test
    void classifierFailureStillAllowsChatButReplyFailureIs502WithoutRawError(CapturedOutput output) throws Exception {
        String id = id();
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("PRIVATE_CLASSIFIER_ERROR"))
                .thenReturn(response("仍然可以聊天"));
        send(id, "PRIVATE_CURRENT_MESSAGE").andExpect(status().isOk())
                .andExpect(jsonPath("$.intent.intent").value("UNKNOWN"))
                .andExpect(jsonPath("$.answer").value("仍然可以聊天"));
        when(model.call(any(Prompt.class))).thenReturn(response(intent("OTHER", null)))
                .thenThrow(new IllegalStateException("PRIVATE_CHAT_ERROR"));
        send(id, "第二条").andExpect(status().isBadGateway());
        // 1.1.2 Advisor 在模型调用前保存用户输入，失败时这一条仍留在窗口里。
        assertThat(memory.get(id)).extracting(m -> m.getText())
                .containsExactly("PRIVATE_CURRENT_MESSAGE", "仍然可以聊天", "第二条");
        assertThat(output.getAll()).doesNotContain("PRIVATE_CLASSIFIER_ERROR", "PRIVATE_CHAT_ERROR", "PRIVATE_CURRENT_MESSAGE");
    }

    /**
     * 预置已有会话再调用旧单次接口，确认临时聊天不写共享默认记忆，也不清理其他会话。
     */
    @Test
    void legacyChatCleansTemporaryMemoryAndDoesNotTouchOtherSessions() throws Exception {
        String id = id();
        memory.add(id, new UserMessage("保留这个会话"));
        when(model.call(any(Prompt.class))).thenReturn(response("普通回答"));
        mvc.perform(get("/api/chat").param("message", "你好")).andExpect(status().isOk());
        assertThat(memory.get(id)).extracting(m -> m.getText()).containsExactly("保留这个会话");
        assertThat(memory.get(ChatMemory.DEFAULT_CONVERSATION_ID)).isEmpty();
    }

    /**
     * 用 ObjectMapper 序列化请求后经 MockMvc 走会话 API，复用统一请求格式；带用户参数的重载同时设置演示身份头。
     */
    private ResultActions send(String id, String message) throws Exception {
        return mvc.perform(post("/api/conversations/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("message", message))));
    }
    /**
     * 每例生成独立会话 UUID，避免内存窗口在共享测试容器中相互干扰。
     */
    private String id() { return UUID.randomUUID().toString(); }
    /**
     * 构造或配置只有一条助手输出的模型响应，让测试精确控制本次生成文本。
     */
    private ChatResponse response(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    /**
     * 构造分类器 JSON 样例，与客服自然语言回复分开，便于观察两次模型调用。
     */
    private String intent(String type, String order) {
        return "{\"intent\":\"" + type + "\",\"orderNo\":" + (order == null ? "null" : "\"" + order + "\"")
                + ",\"confidence\":0.95,\"missingFields\":[]}";
    }
}
