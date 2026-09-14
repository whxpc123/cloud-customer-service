package com.example.cloudcustomerservice.order;

/**
 * 订单业务状态的展示字典；这些值只描述查询时的快照，不触发状态流转。
 * 例如 REFUNDED 是读到的状态，返回该枚举不会执行退款。
 */
public enum OrderStatus {
    /**
     * 订单已创建。
     */
    CREATED,
    /**
     * 已支付。
     */
    PAID,
    /**
     * 正在打包。
     */
    PACKING,
    /**
     * 已发货。
     */
    SHIPPED,
    /**
     * 已送达。
     */
    DELIVERED,
    /**
     * 订单已取消。
     */
    CANCELLED,
    /**
     * 退款处理中。
     */
    REFUNDING,
    /**
     * 已退款。
     */
    REFUNDED
}
