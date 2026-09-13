package com.example.cloudcustomerservice.order;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 章节固定模拟数据；日期保持文章样例，不能当成今天的真实物流承诺。 */
@Service
public class InMemoryOrderService implements OrderService {
    private final Map<String, StoredOrder> orders = Map.of(
            "A10001", new StoredOrder(1001L, new OrderSnapshot("A10001", OrderStatus.SHIPPED, LocalDate.of(2026, 8, 20))),
            "A20002", new StoredOrder(2002L, new OrderSnapshot("A20002", OrderStatus.PACKING, LocalDate.of(2026, 8, 22))));

    @Override
    public Optional<OrderSnapshot> findOwnedOrder(long userId, String orderNo) {
        StoredOrder order = orderNo == null ? null : orders.get(orderNo);
        return order == null || order.ownerUserId() != userId ? Optional.empty() : Optional.of(order.snapshot());
    }

    private record StoredOrder(long ownerUserId, OrderSnapshot snapshot) { }
}
