package com.example.cloudcustomerservice.order;

import java.util.Optional;

/**
 * 订单查询的业务抽象，将模型工具与具体存储解耦。
 * 未来接入数据库或订单 API 时仍须在实现中同时检查用户身份与订单归属。
 */
public interface OrderService {
    /**
     * 按用户和订单号联合查询；无记录和非本人订单都返回 Optional.empty()。
     * @param userId 应用提供的当前用户 ID
     * @param orderNo 已规范化的订单号
     * @return 当前用户可访问的最小订单快照
     */
    Optional<OrderSnapshot> findOwnedOrder(long userId, String orderNo);
}
