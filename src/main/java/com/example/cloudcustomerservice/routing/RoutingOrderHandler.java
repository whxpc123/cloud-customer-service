package com.example.cloudcustomerservice.routing;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor;
import com.example.cloudcustomerservice.tool.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 复用第五章只读订单工具，由 Java 从客户原文提取编号；不再调用第二个意图模型或自由生成订单状态。 */
@Component
@Profile("local & knowledge")
public class RoutingOrderHandler {
    private static final Pattern ORDER = Pattern.compile("(?<![A-Za-z0-9])A[0-9]{5}(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    private final CustomerOrderTools tools;
    public RoutingOrderHandler(CustomerOrderTools tools) { this.tools = tools; }
    public record Result(String status, String answer, OrderLookupResult lookup) { }
    /** 先当前消息，后客户历史；历史目标超过一个就澄清，不接受助手生成的编号作为客户授权。 */
    public Result answer(Actor actor, String message, List<Message> history) {
        if (!"tenant-yunshan".equals(actor.tenantId())) throw new IllegalArgumentException("本地订单仅支持演示租户");
        Set<String> candidates = extract(message);
        if (candidates.isEmpty()) history.stream().filter(m -> m.getMessageType() == MessageType.USER)
                .forEach(m -> candidates.addAll(extract(m.getText())));
        if (candidates.size() > 1) return new Result("NEED_ORDER_SELECTION", "出现多笔订单，请明确本次要查询哪一个订单号。", null);
        String orderNo = candidates.isEmpty() ? null : candidates.iterator().next();
        var result = tools.queryOrder(orderNo, new ToolContext(Map.of("currentUserId", actor.userId())));
        String answer = result.message();
        if (result.code() == OrderLookupCode.FOUND) {
            String state = switch (result.status()) {
                case CREATED -> "已创建";
                case PAID -> "已支付";
                case PACKING -> "正在打包";
                case SHIPPED -> "已发货";
                case DELIVERED -> "已送达";
                case CANCELLED -> "已取消";
                case REFUNDING -> "退款处理中";
                case REFUNDED -> "已退款";
            };
            answer = "订单 " + result.orderNo() + " 的本地样例状态：" + state + "。"
                    + (result.expectedDeliveryDate() == null ? "" : "样例预计送达日期：" + result.expectedDeliveryDate() + "。")
                    + "这是固定教学数据，不是当前物流承诺。";
        } else if (result.code() == OrderLookupCode.MISSING_ORDER_NO) answer = "请提供要查询的订单号，本轮还没有查询订单。";
        return new Result(result.code().name(), answer, result);
    }
    private Set<String> extract(String text) {
        var result = new LinkedHashSet<String>(); var matcher = ORDER.matcher(text);
        while (matcher.find()) result.add(matcher.group().toUpperCase(Locale.ROOT));
        return result;
    }
}
