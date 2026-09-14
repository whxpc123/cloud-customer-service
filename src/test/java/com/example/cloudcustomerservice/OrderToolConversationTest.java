package com.example.cloudcustomerservice;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.cloudcustomerservice.conversation.CustomerConversationService;
import com.example.cloudcustomerservice.order.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 第五章工具与会话链路集成测试：模型决策是模拟的，注解工具的解析与执行由真实 ToolCallingManager 完成。
 * 检查应用身份传递、实际调用轨迹和跨用户记忆隔离，不测试供应商的自然语言决策能力。
 */

@SpringBootTest(properties = "spring.ai.dashscope.api-key=offline-test-placeholder")
@AutoConfigureMockMvc
class OrderToolConversationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired @Qualifier("customerChatMemory") private ChatMemory memory;
    @MockitoBean private ChatModel model;
    @MockitoBean private OrderService orders;

    /**
     * 让模拟模型给出工具调用，交真实管理器执行，验证服务端身份、订单状态和前端轨迹贯通。
     */
    @Test
    void frameworkExecutesAnnotatedToolWithServerContextAndReturnsTrace() throws Exception {
        when(orders.findOwnedOrder(1001L,"A10001")).thenReturn(new InMemoryOrderService().findOwnedOrder(1001L,"A10001"));
        stubToolDecision("{\"orderNo\":\"A10001\"}");
        String id = id();
        send(id,1001L,"A10001 发货了吗？").andExpect(status().isOk())
                .andExpect(jsonPath("$.orderLookups[0].code").value("FOUND"))
                .andExpect(jsonPath("$.orderLookups[0].status").value("SHIPPED"))
                .andExpect(jsonPath("$.orderLookups[0].expectedDeliveryDate").value("2026-08-20"))
                .andExpect(jsonPath("$.answer").value("已根据工具结果生成回复"));
        verify(orders).findOwnedOrder(1001L,"A10001");
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model,times(2)).call(prompt.capture());
        assertThat(prompt.getAllValues().get(0).getOptions() instanceof ToolCallingChatOptions options
                && !options.getToolCallbacks().isEmpty()).isFalse();
        var options = (ToolCallingChatOptions) prompt.getAllValues().get(1).getOptions();
        assertThat(options.getToolContext().get("currentUserId")).isEqualTo(1001L);
        assertThat(options.getToolCallbacks()).hasSize(1);
        assertThat(memory.get(CustomerConversationService.memoryId(id,1001L))).extracting(Message::getMessageType)
                .containsExactly(MessageType.USER,MessageType.ASSISTANT);
    }

    /**
     * 两个用户使用相同外部会话 ID，验证一个用户既不能读取也不能清空另一个用户的记忆。
     */
    @Test
    void sameExternalIdCannotShareReadOrClearMemoryAcrossIdentities() throws Exception {
        String id = id();
        String a = CustomerConversationService.memoryId(id,1001L);
        String b = CustomerConversationService.memoryId(id,2002L);
        memory.add(a,List.of(new UserMessage("我的订单是 A10001"),new AssistantMessage("历史秘密状态")));
        stubToolDecision(null);
        send(id,2002L,"它到哪了？").andExpect(status().isOk()).andExpect(jsonPath("$.orderLookups").isEmpty());
        var prompts=ArgumentCaptor.forClass(Prompt.class);verify(model,times(2)).call(prompts.capture());
        prompts.getAllValues().forEach(p -> assertThat(p.getContents()).doesNotContain("A10001","历史秘密状态"));
        mvc.perform(delete("/api/conversations/{id}/memory",id).header("X-Demo-User-Id",2002)).andExpect(status().isNoContent());
        assertThat(memory.get(b)).isEmpty(); assertThat(memory.get(a)).hasSize(2);
        verifyNoInteractions(orders);
    }

    /**
     * 将仓库连续两次响应设为打包中和已发货，确认第二次追问重新查库而不沿用历史状态。
     */
    @Test
    void repeatedStateQueriesReadServiceAgainEvenWithHistory() throws Exception {
        String id = id();
        when(orders.findOwnedOrder(1001L,"A10001")).thenReturn(
                Optional.of(new OrderSnapshot("A10001",OrderStatus.PACKING,null)),
                Optional.of(new OrderSnapshot("A10001",OrderStatus.SHIPPED,null)));
        stubToolDecision("{\"orderNo\":\"A10001\"}");
        send(id,1001L,"A10001 什么状态？").andExpect(jsonPath("$.orderLookups[0].status").value("PACKING"));
        send(id,1001L,"它现在呢？").andExpect(jsonPath("$.orderLookups[0].status").value("SHIPPED"));
        verify(orders,times(2)).findOwnedOrder(1001L,"A10001");
    }

    /**
     * 模拟订单依赖异常，验证模型和前端收到稳定业务结果，没有 SQL 错误原文。
     */
    @Test
    void toolFailureReachesModelAsBusinessResultAndNoRawException() throws Exception {
        when(orders.findOwnedOrder(1001L,"A10001")).thenThrow(new IllegalStateException("PRIVATE_SQL"));
        stubToolDecision("{\"orderNo\":\"A10001\"}");
        send(id(),1001L,"查 A10001").andExpect(status().isOk())
                .andExpect(jsonPath("$.orderLookups[0].code").value("TEMPORARILY_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE_SQL"))));
    }

    /**
     * 访客工具请求须返回身份要求，旧单次接口没有工具回调，两条路径都不应读取订单仓库。
     */
    @Test
    void visitorCannotQueryAndLegacyChatHasNoTools() throws Exception {
        stubToolDecision("{\"orderNo\":\"A10001\"}");
        send(id(),null,"查询 A10001").andExpect(status().isOk())
                .andExpect(jsonPath("$.orderLookups[0].code").value("AUTHENTICATION_REQUIRED"));
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt p=inv.getArgument(0);
            assertThat(p.getOptions() instanceof ToolCallingChatOptions o && !o.getToolCallbacks().isEmpty()).isFalse();
            return reply("旧接口无工具");
        });
        mvc.perform(get("/api/chat").param("message","你好")).andExpect(status().isOk());
        verifyNoInteractions(orders);
    }

    /**
     * 演示身份头出现零、负数、非数字或溢出时返回 400，并阻止模型和订单依赖调用。
     */
    @Test
    void invalidDemoHeaderIs400BeforeAnyModelRequest() throws Exception {
        for (String header : List.of("0","-1","hello","9223372036854775808")) {
            mvc.perform(post("/api/conversations/{id}/messages",id()).header("X-Demo-User-Id",header)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"你好\"}")).andExpect(status().isBadRequest());
        }
        verify(model,never()).call(any(Prompt.class)); verifyNoInteractions(orders);
    }

    /**
     * 用模拟模型区分分类与客服请求；当配置了工具参数时交给真实 ToolCallingManager 执行。
     * 它只模拟模型是否选择工具，不跳过 Java 工具的身份和参数校验。
     */
    private void stubToolDecision(String arguments) {
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt p=inv.getArgument(0);
            if (p.getSystemMessage().getText().contains("意图识别器")) {
                return reply("{\"intent\":\"ORDER_QUERY\",\"orderNo\":null,\"confidence\":0.9,\"missingFields\":[\"orderNo\"]}");
            }
            if (arguments != null) {
                var call = AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("call-1","function","queryCurrentUserOrder",arguments))).build();
                // 真正的 Spring AI 管理器解析模型参数、执行注解方法并序列化结果。
                var execution=ToolCallingManager.builder().build().executeToolCalls(p,new ChatResponse(List.of(new Generation(call))));
                assertThat(execution.conversationHistory().get(execution.conversationHistory().size()-1))
                        .isInstanceOf(ToolResponseMessage.class);
                var response=(ToolResponseMessage) execution.conversationHistory().get(execution.conversationHistory().size()-1);
                assertThat(response.getResponses().get(0).responseData()).contains("code").doesNotContain("PRIVATE_SQL");
            }
            return reply("已根据工具结果生成回复");
        });
    }
    /**
     * 用 ObjectMapper 序列化请求后经 MockMvc 走会话 API，复用统一请求格式；带用户参数的重载同时设置演示身份头。
     */
    private ResultActions send(String id,Long userId,String message) throws Exception {
        var request=post("/api/conversations/{id}/messages",id).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("message",message)));
        if(userId!=null) request.header("X-Demo-User-Id",userId);
        return mvc.perform(request);
    }
    /**
     * 每例生成独立会话 UUID，避免内存窗口在共享测试容器中相互干扰。
     */
    private String id(){return UUID.randomUUID().toString();}
    /**
     * 将给定文本包装成单候选 ChatResponse，模拟供应商的一次正常回复。
     */
    private ChatResponse reply(String text){return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));}
}
