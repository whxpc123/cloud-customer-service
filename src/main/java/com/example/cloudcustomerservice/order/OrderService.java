package com.example.cloudcustomerservice.order;

import java.util.Optional;

public interface OrderService {
    Optional<OrderSnapshot> findOwnedOrder(long userId, String orderNo);
}
