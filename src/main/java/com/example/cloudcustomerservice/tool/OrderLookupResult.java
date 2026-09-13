package com.example.cloudcustomerservice.tool;

import java.time.LocalDate;
import com.example.cloudcustomerservice.order.OrderSnapshot;
import com.example.cloudcustomerservice.order.OrderStatus;

public record OrderLookupResult(OrderLookupCode code, String orderNo, OrderStatus status,
        LocalDate expectedDeliveryDate, String message) {
    public static OrderLookupResult found(OrderSnapshot order) {
        return new OrderLookupResult(OrderLookupCode.FOUND, order.orderNo(), order.status(),
                order.expectedDeliveryDate(), "订单查询成功（本地模拟数据，日期为固定样例）");
    }
    public static OrderLookupResult missingOrderNo() {
        return failure(OrderLookupCode.MISSING_ORDER_NO, null, "缺少订单号，请向用户询问订单号");
    }
    public static OrderLookupResult invalidOrderNo(String orderNo) {
        return failure(OrderLookupCode.INVALID_ORDER_NO, orderNo, "订单号格式不正确，需为字母 A 加 5 位数字");
    }
    public static OrderLookupResult notFound(String orderNo) {
        return failure(OrderLookupCode.NOT_FOUND, orderNo, "没有找到当前用户可以访问的订单");
    }
    public static OrderLookupResult authenticationRequired() {
        return failure(OrderLookupCode.AUTHENTICATION_REQUIRED, null, "当前用户尚未登录，请选择演示用户后新建会话");
    }
    public static OrderLookupResult temporarilyUnavailable(String orderNo) {
        return failure(OrderLookupCode.TEMPORARILY_UNAVAILABLE, orderNo, "订单系统暂时不可用，请稍后重试");
    }
    private static OrderLookupResult failure(OrderLookupCode code, String orderNo, String message) {
        return new OrderLookupResult(code, orderNo, null, null, message);
    }
}
