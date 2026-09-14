package com.example.cloudcustomerservice.tool;

import java.time.LocalDate;
import com.example.cloudcustomerservice.order.OrderSnapshot;
import com.example.cloudcustomerservice.order.OrderStatus;

/**
 * 工具传回给模型并展示到前端的结构化查询结果。
 * 失败结果不携带订单状态或日期，防止模型把旧数据误认为本次查到的事实。
 *
 * @param code 查询是否成功及失败原因
 * @param orderNo 规范化后的订单号，缺少或超长输入时可能为 null
 * @param status 仅 FOUND 返回订单状态
 * @param expectedDeliveryDate 仅成功结果可携带的样例日期
 * @param message 稳定的中文业务说明，不包含上游异常正文
 */
public record OrderLookupResult(OrderLookupCode code, String orderNo, OrderStatus status,
        LocalDate expectedDeliveryDate, String message) {
    /**
     * 成功结果从业务快照取值，并在说明中标记本地模拟数据。
     */
    public static OrderLookupResult found(OrderSnapshot order) {
        return new OrderLookupResult(OrderLookupCode.FOUND, order.orderNo(), order.status(),
                order.expectedDeliveryDate(), "订单查询成功（本地模拟数据，日期为固定样例）");
    }
    /**
     * 缺少订单号时提示追问，不用示例订单号代替客户目标。
     */
    public static OrderLookupResult missingOrderNo() {
        return failure(OrderLookupCode.MISSING_ORDER_NO, null, "缺少订单号，请向用户询问订单号");
    }
    /**
     * 格式不合法时仅回显有界输入，不携带任何订单状态。
     */
    public static OrderLookupResult invalidOrderNo(String orderNo) {
        return failure(OrderLookupCode.INVALID_ORDER_NO, orderNo, "订单号格式不正确，需为字母 A 加 5 位数字");
    }
    /**
     * 将不存在和无权访问合并成同一个业务结果，不泄露其他用户的订单。
     */
    public static OrderLookupResult notFound(String orderNo) {
        return failure(OrderLookupCode.NOT_FOUND, orderNo, "没有找到当前用户可以访问的订单");
    }
    /**
     * 缺少应用身份时请求先选择演示用户，不接受模型自行补写身份。
     */
    public static OrderLookupResult authenticationRequired() {
        return failure(OrderLookupCode.AUTHENTICATION_REQUIRED, null, "当前用户尚未登录，请选择演示用户后新建会话");
    }
    /**
     * 依赖故障时明确暂时无法确认，避免将系统错误解释为订单不存在。
     */
    public static OrderLookupResult temporarilyUnavailable(String orderNo) {
        return failure(OrderLookupCode.TEMPORARILY_UNAVAILABLE, orderNo, "订单系统暂时不可用，请稍后重试");
    }
    /**
     * 集中构造失败结果，确保所有失败分支的 status 与预计日期均为 null。
     */
    private static OrderLookupResult failure(OrderLookupCode code, String orderNo, String message) {
        return new OrderLookupResult(code, orderNo, null, null, message);
    }
}
