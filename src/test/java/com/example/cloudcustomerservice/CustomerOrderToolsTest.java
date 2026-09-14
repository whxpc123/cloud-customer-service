package com.example.cloudcustomerservice;

import java.util.Map;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.cloudcustomerservice.order.InMemoryOrderService;
import com.example.cloudcustomerservice.order.OrderService;
import com.example.cloudcustomerservice.order.OrderStatus;
import com.example.cloudcustomerservice.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 第五章工具边界测试，直接或通过真实 ToolCallback 执行 Java 订单工具。
 * 固定样例与模拟仓库分别验证归属结果、拒绝调用的边界和异常信息屏蔽。
 */

@ExtendWith(OutputCaptureExtension.class)
class CustomerOrderToolsTest {
    private final CustomerOrderTools tools = new CustomerOrderTools(new InMemoryOrderService(), false);
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 参数化组合用户与订单，比较成功和不可访问结果，同时确认响应不暴露订单拥有者 ID。
     */
    @ParameterizedTest
    @CsvSource({"1001,A10001,FOUND,SHIPPED", "2002,A20002,FOUND,PACKING",
            "2002,A10001,NOT_FOUND,", "1001,A20002,NOT_FOUND,", "1001,A99999,NOT_FOUND,"})
    void enforcesOwnershipAndReturnsMinimalSnapshot(long userId, String order, OrderLookupCode code, OrderStatus status) {
        var result = tools.queryOrder(order, context(userId));
        assertThat(result.code()).isEqualTo(code);
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.message()).doesNotContain("owner", "1001", "2002");
        if (code == OrderLookupCode.NOT_FOUND) assertThat(result.expectedDeliveryDate()).isNull();
    }

    /**
     * 用小写及带空格订单号和 Integer 身份测试规范化，确认最终可读取本人的样例订单。
     */
    @Test
    void normalizesOrderAndIntegerIdentity() {
        assertThat(tools.queryOrder(" a10001 ", context(1001)).code()).isEqualTo(OrderLookupCode.FOUND);
    }

    /**
     * 传入字符串、负数、浮点或缺失身份，检查认证提示以及仓库零交互，防止工具绕过应用身份。
     */
    @Test
    void absentOrInvalidIdentityCannotReachService() {
        OrderService service = mock(OrderService.class);
        var secured = new CustomerOrderTools(service, false);
        for (Object id : new Object[]{"1001", -1L, 0L, 1001.0}) {
            assertThat(secured.queryOrder("A10001", context(id)).code()).isEqualTo(OrderLookupCode.AUTHENTICATION_REQUIRED);
        }
        assertThat(secured.queryOrder("A10001", null).code()).isEqualTo(OrderLookupCode.AUTHENTICATION_REQUIRED);
        assertThat(secured.queryOrder("A10001", new ToolContext(Map.of())).code()).isEqualTo(OrderLookupCode.AUTHENTICATION_REQUIRED);
        verifyNoInteractions(service);
    }

    /**
     * 覆盖缺号、全角数字、错误位数和超长输入，验证格式拒绝先于仓库查询且返回内容有界。
     */
    @Test
    void missingInvalidAndOversizedOrdersNeverReachService() {
        OrderService service = mock(OrderService.class);
        var guarded = new CustomerOrderTools(service, false);
        assertThat(guarded.queryOrder(null, context(1001L)).code()).isEqualTo(OrderLookupCode.MISSING_ORDER_NO);
        assertThat(guarded.queryOrder("  ", context(1001L)).code()).isEqualTo(OrderLookupCode.MISSING_ORDER_NO);
        for (String input : new String[]{"123", "A123456", "A12-34", "A１２３４５", "a".repeat(4001)}) {
            var result = guarded.queryOrder(input, context(1001L));
            assertThat(result.code()).isEqualTo(OrderLookupCode.INVALID_ORDER_NO);
            assertThat(result.orderNo() == null || result.orderNo().length() <= 64).isTrue();
        }
        verifyNoInteractions(service);
    }

    /**
     * 仓库抛出包含内部地址标记的异常，验证转换为暂不可用，响应与日志都不泄露该标记。
     */
    @Test
    void exceptionsBecomeStableFailureWithoutLeakingDetails(CapturedOutput output) {
        OrderService service = mock(OrderService.class);
        when(service.findOwnedOrder(1001L, "A10001")).thenThrow(new IllegalStateException("SECRET_DATABASE_ADDRESS"));
        var result = new CustomerOrderTools(service, false).queryOrder("A10001", context(1001L));
        assertThat(result.code()).isEqualTo(OrderLookupCode.TEMPORARILY_UNAVAILABLE);
        assertThat(result.status()).isNull();
        assertThat(result.toString()).doesNotContain("SECRET_DATABASE_ADDRESS");
        assertThat(output.getAll()).doesNotContain("SECRET_DATABASE_ADDRESS", "A10001");
    }

    /**
     * 查看真实工具 Schema 并伪造模型参数中的身份字段，验证 Schema 仅暴露订单号且应用身份不能被覆盖。
     */
    @Test
    void actualSchemaExcludesIdentityAndExtraModelIdentityCannotOverrideContext() throws Exception {
        var callback = ToolCallbacks.from(tools)[0];
        var definition = callback.getToolDefinition();
        var schema = mapper.readTree(definition.inputSchema());
        assertThat(definition.name()).isEqualTo("queryCurrentUserOrder");
        assertThat(schema.get("properties").size()).isEqualTo(1);
        assertThat(schema.get("properties").has("orderNo")).isTrue();
        assertThat(schema.path("required").toString()).doesNotContain("orderNo");
        assertThat(definition.inputSchema()).doesNotContain("userId", "currentUserId", "toolContext");
        String raw = callback.call("{\"orderNo\":\"A20002\",\"userId\":2002,\"currentUserId\":2002}", context(1001L));
        assertThat(mapper.readTree(raw).get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(raw).doesNotContain("PACKING", "2002", "ownerUserId");
        assertThat(mapper.readTree(callback.call("{}", context(1001L))).get("code").asText()).isEqualTo("MISSING_ORDER_NO");
    }

    /**
     * 执行真实工具并开启日志，核对轨迹与请求 ID 一致，同时检查上下文私有字段不会被打印。
     */
    @Test
    void logsRealCallsAndCollectsOnlyMinimalResults(CapturedOutput output) {
        var trace = new OrderToolTrace();
        var context = new ToolContext(Map.of("currentUserId",1001L,OrderToolTrace.CONTEXT_KEY,trace,"privateMarker","DO_NOT_LOG_CONTEXT"));
        var result = new CustomerOrderTools(new InMemoryOrderService(),true).queryOrder("A10001", context);
        assertThat(trace.results()).containsExactly(result);
        assertThat(output.getAll()).contains("[TOOL REQUEST] id=" + trace.id(), "[TOOL RESULT] id=" + trace.id(),"FOUND","SHIPPED")
                .doesNotContain("DO_NOT_LOG_CONTEXT", "currentUserId");
    }

    /**
     * 创建应用侧 ToolContext 身份；参数接受 Object，以便验证错误身份类型被拒绝。
     */
    private ToolContext context(Object id) { return new ToolContext(Map.of("currentUserId", id)); }
}
