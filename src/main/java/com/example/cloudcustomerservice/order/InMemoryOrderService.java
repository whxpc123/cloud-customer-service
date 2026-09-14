package com.example.cloudcustomerservice.order;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 第五章的只读订单仓库，用两个不同用户的固定订单演示归属隔离。
 * 查询不存在和无权访问都返回空，避免向当前用户泄露他人订单是否存在。
 */
@Service
public class InMemoryOrderService implements OrderService {
    private final Map<String, StoredOrder> orders = Map.of(
            "A10001", new StoredOrder(1001L, new OrderSnapshot("A10001", OrderStatus.SHIPPED, LocalDate.of(2026, 8, 20))),
            "A20002", new StoredOrder(2002L, new OrderSnapshot("A20002", OrderStatus.PACKING, LocalDate.of(2026, 8, 22))));

    /**
     * 先查样例表，再检查 ownerUserId；不会先向调用方暴露订单存在性。
     */
    @Override
    public Optional<OrderSnapshot> findOwnedOrder(long userId, String orderNo) {
        StoredOrder order = orderNo == null ? null : orders.get(orderNo);
        return order == null || order.ownerUserId() != userId ? Optional.empty() : Optional.of(order.snapshot());
    }

    /**
     * 仅仓库内部保存归属信息；对外返回 snapshot，避免把拥有者 ID 交给模型。
     */
    private record StoredOrder(long ownerUserId, OrderSnapshot snapshot) { }
}
