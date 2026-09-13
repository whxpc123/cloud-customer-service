package com.example.cloudcustomerservice.order;

import java.time.LocalDate;

/** 仅返回客服需要的字段，不暴露完整订单实体。 */
public record OrderSnapshot(String orderNo, OrderStatus status, LocalDate expectedDeliveryDate) {
}
