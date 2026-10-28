package com.harness.order;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Repository;

import com.harness.order.Models.Order;

@Repository
class OrderStore {

    private final AtomicLong seq = new AtomicLong();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    Order save(Order draft) {
        Order o = new Order("o_" + seq.incrementAndGet(), draft.userId(), draft.items(), draft.totalCents(),
                draft.status(), Instant.now());
        orders.put(o.id(), o);
        return o;
    }

    Optional<Order> find(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    List<Order> byUser(String userId) {
        return orders.values().stream().filter(o -> o.userId().equals(userId))
                .sorted((a, b) -> a.createdAt().compareTo(b.createdAt())).toList();
    }
}
