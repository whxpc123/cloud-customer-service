package com.example.cloudcustomerservice.routing;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.cloudcustomerservice.routing.CustomerRouter.*;

/** 使用真实 ChatClient 和结构化转换器，验证无工具/记忆配置以及 JSON 类型边界。 */
class RoutingClassifierTest {
    private CustomerRouter withOutput(String output) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(output)))));
        return new RoutingConfiguration().customerRouter(model,false);
    }
    @Test void classifierSeesReadOnlyDataAndSchemaButNoBusinessTools() {
        var captured = new AtomicReference<Prompt>(); ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(i->{captured.set(i.getArgument(0));return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"route\":\"ORDER_QUERY\",\"ambiguous\":false,\"multipleIndependentTasks\":false}"))));});
        var router = new RoutingConfiguration().customerRouter(model,false);
        assertThat(router.route(new Input("A10001", "[USER] 我要查订单\n[ASSISTANT] 请提供订单号")).route()).isEqualTo(Route.ORDER_QUERY);
        Prompt p = captured.get();
        assertThat(p.getInstructions()).hasSize(2);
        assertThat(p.getUserMessage().getText()).contains("A10001", "请提供订单号", "multipleIndependentTasks", "ORDER_QUERY");
        if (p.getOptions() instanceof ToolCallingChatOptions options) {
            assertThat(options.getToolCallbacks()).isNullOrEmpty(); assertThat(options.getToolNames()).isNullOrEmpty();
        }
        verify(model,times(1)).call(any(Prompt.class));
    }
    @ParameterizedTest @ValueSource(strings={"not-json", "{}", "null", "{\"route\":\"EXECUTE_REFUND\",\"ambiguous\":false,\"multipleIndependentTasks\":false}", "{\"route\":\"ORDER_QUERY\",\"ambiguous\":\"false\",\"multipleIndependentTasks\":false}", "{\"route\":\"ORDER_QUERY\",\"ambiguous\":false,\"multipleIndependentTasks\":false,\"service\":\"refund\"}"})
    void invalidOutputIsDistinctFromProviderFailure(String output) {
        assertThat(withOutput(output).route(new Input("查订单", "")).reasonCode()).isEqualTo("INVALID_MODEL_RESULT");
    }
    @Test void providerFailureDoesNotBecomeInvalidJson() {
        ChatModel model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("offline"));
        assertThat(new RoutingConfiguration().customerRouter(model,false).route(new Input("查订单", "")).reasonCode()).isEqualTo("MODEL_UNAVAILABLE");
    }
}
