package com.example.cloudcustomerservice.order;

import java.time.LocalDate;

/**
 * 只读订单快照，省略收货地址、电话等客服查询不需要的完整订单字段。
 *
 * @param orderNo 可访问订单的编号
 * @param status 查询得到的业务状态
 * @param expectedDeliveryDate 固定模拟数据中的预计送达日期，不是今天的配送承诺
 */
public record OrderSnapshot(String orderNo, OrderStatus status, LocalDate expectedDeliveryDate) {
}
