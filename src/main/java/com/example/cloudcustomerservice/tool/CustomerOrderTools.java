package com.example.cloudcustomerservice.tool;

import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.example.cloudcustomerservice.order.OrderService;

@Component
public class CustomerOrderTools {
    private static final Logger log = LoggerFactory.getLogger(CustomerOrderTools.class);
    private static final Pattern ORDER_NO_PATTERN = Pattern.compile("A[0-9]{5}");
    private final OrderService orderService;
    private final boolean logPayload;

    public CustomerOrderTools(OrderService orderService, @Value("${app.ai.log-payload:false}") boolean logPayload) {
        this.orderService = orderService;
        this.logPayload = logPayload;
    }

    @Tool(name = "queryCurrentUserOrder", description = """
            查询当前用户自己的订单状态（本地模拟订单服务）。
            用户询问是否创建或支付成功、是否发货、当前状态、预计送达日期时使用。
            如果使用“它”“刚才那个订单”等指代，可以从同一会话的客户历史消息中取得明确订单号。
            每次询问当前状态都要重新查，不得用旧回复代替本次查询。
            缺少明确订单号时不得编造。工具只读，不能退款、取消订单、修改地址。
            返回日期是固定的演示数据，不是今天的配送承诺；不提供运输轨迹或退款政策。
            """)
    public OrderLookupResult queryOrder(
            @ToolParam(description = "订单号：字母 A 加 5 位数字，例如 A10001；只来自客户当前消息或同会话客户历史，不能编造。", required = false)
            String orderNo, ToolContext toolContext) {
        var trace = trace(toolContext);
        String callId = trace == null ? "direct" : trace.id();
        // 仅打印有界业务输入和结果，不打印 ToolContext（含应用侧身份）或异常堆栈。
        if (logPayload) log.info("[TOOL REQUEST] id={} name=queryCurrentUserOrder orderNo={}", callId, bounded(orderNo));
        OrderLookupResult result = lookup(orderNo, toolContext);
        if (trace != null) trace.add(result);
        if (logPayload) log.info("[TOOL RESULT] id={} name=queryCurrentUserOrder result={}", callId, result);
        return result;
    }

    private OrderLookupResult lookup(String orderNo, ToolContext context) {
        Long userId = currentUserId(context);
        if (userId == null || userId <= 0) return OrderLookupResult.authenticationRequired();
        if (orderNo == null || orderNo.isBlank()) return OrderLookupResult.missingOrderNo();
        // 先限制长度，再规范化，避免把任意长的错误参数重新交给模型。
        if (orderNo.length() > 64) return OrderLookupResult.invalidOrderNo(null);
        String normalized = orderNo.strip().toUpperCase(Locale.ROOT);
        if (!ORDER_NO_PATTERN.matcher(normalized).matches()) return OrderLookupResult.invalidOrderNo(normalized);
        try {
            return orderService.findOwnedOrder(userId, normalized)
                    .map(OrderLookupResult::found).orElseGet(() -> OrderLookupResult.notFound(normalized));
        }
        catch (RuntimeException ex) {
            log.warn("Order lookup failed, errorType={}", ex.getClass().getSimpleName());
            return OrderLookupResult.temporarilyUnavailable(normalized);
        }
    }

    private Long currentUserId(ToolContext context) {
        Object value = context == null ? null : context.getContext().get("currentUserId");
        if (value instanceof Long id) return id;
        if (value instanceof Integer id) return id.longValue();
        return null;
    }
    private OrderToolTrace trace(ToolContext context) {
        Object value = context == null ? null : context.getContext().get(OrderToolTrace.CONTEXT_KEY);
        return value instanceof OrderToolTrace trace ? trace : null;
    }
    private String bounded(String text) {
        return text == null ? null : text.substring(0, Math.min(64, text.length())).replaceAll("[\\r\\n\\t]", " ");
    }
}
