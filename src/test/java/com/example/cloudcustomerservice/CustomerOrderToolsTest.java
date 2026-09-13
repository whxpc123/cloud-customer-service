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

@ExtendWith(OutputCaptureExtension.class)
class CustomerOrderToolsTest {
    private final CustomerOrderTools tools = new CustomerOrderTools(new InMemoryOrderService(), false);
    private final ObjectMapper mapper = new ObjectMapper();

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

    @Test
    void normalizesOrderAndIntegerIdentity() {
        assertThat(tools.queryOrder(" a10001 ", context(1001)).code()).isEqualTo(OrderLookupCode.FOUND);
    }

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

    @Test
    void logsRealCallsAndCollectsOnlyMinimalResults(CapturedOutput output) {
        var trace = new OrderToolTrace();
        var context = new ToolContext(Map.of("currentUserId",1001L,OrderToolTrace.CONTEXT_KEY,trace,"privateMarker","DO_NOT_LOG_CONTEXT"));
        var result = new CustomerOrderTools(new InMemoryOrderService(),true).queryOrder("A10001", context);
        assertThat(trace.results()).containsExactly(result);
        assertThat(output.getAll()).contains("[TOOL REQUEST] id=" + trace.id(), "[TOOL RESULT] id=" + trace.id(),"FOUND","SHIPPED")
                .doesNotContain("DO_NOT_LOG_CONTEXT", "currentUserId");
    }

    private ToolContext context(Object id) { return new ToolContext(Map.of("currentUserId", id)); }
}
