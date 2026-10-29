package com.harness.order;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.harness.order.Models.LineItem;
import com.harness.order.Models.Order;
import com.harness.order.Models.Status;

/**
 * 订单头 + 明细两张表。save 在一个事务里写入，任何一行失败整体回滚，不会出现"有订单没明细"。
 * 查询用两条 SQL（头 + 明细 IN）避免 N+1。
 */
@Repository
class JdbcOrderStore implements OrderStore {

    private static final String PREFIX = "o_";

    private final JdbcClient jdbc;

    JdbcOrderStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Order save(Order draft) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO orders (user_id, total_cents, status, created_at) VALUES (:userId, :total, :status, :createdAt)")
                .param("userId", draft.userId())
                .param("total", draft.totalCents())
                .param("status", draft.status().name())
                .param("createdAt", Timestamp.from(now))
                .update(key, "id");
        long id = key.getKeyAs(Number.class).longValue();
        int line = 0;
        for (LineItem it : draft.items()) {
            jdbc.sql("INSERT INTO order_items (order_id, line_no, sku, qty, price_cents) VALUES (:orderId, :lineNo, :sku, :qty, :price)")
                    .param("orderId", id)
                    .param("lineNo", ++line)
                    .param("sku", it.sku())
                    .param("qty", it.qty())
                    .param("price", it.priceCents())
                    .update();
        }
        return new Order(PREFIX + id, draft.userId(), List.copyOf(draft.items()), draft.totalCents(), draft.status(), now);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Order> find(String id) {
        Long dbId = parse(id);
        if (dbId == null) {
            return Optional.empty();
        }
        return load("WHERE id = :p", dbId).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> byUser(String userId) {
        return load("WHERE user_id = :p", userId);
    }

    private List<Order> load(String where, Object param) {
        record Head(long id, String userId, long total, String status, Instant createdAt) {
        }
        List<Head> heads = jdbc.sql("SELECT id, user_id, total_cents, status, created_at FROM orders " + where + " ORDER BY id")
                .param("p", param)
                .query((rs, n) -> new Head(rs.getLong("id"), rs.getString("user_id"), rs.getLong("total_cents"),
                        rs.getString("status"), rs.getTimestamp("created_at").toInstant()))
                .list();
        if (heads.isEmpty()) {
            return List.of();
        }
        Map<Long, List<LineItem>> items = new LinkedHashMap<>();
        jdbc.sql("SELECT order_id, sku, qty, price_cents FROM order_items WHERE order_id IN (:ids) ORDER BY order_id, line_no")
                .param("ids", heads.stream().map(Head::id).toList())
                .query(rs -> {
                    items.computeIfAbsent(rs.getLong("order_id"), k -> new ArrayList<>())
                            .add(new LineItem(rs.getString("sku"), rs.getInt("qty"), rs.getInt("price_cents")));
                });
        return heads.stream()
                .map(h -> new Order(PREFIX + h.id(), h.userId(), items.getOrDefault(h.id(), List.of()), h.total(),
                        Status.valueOf(h.status()), h.createdAt()))
                .toList();
    }

    private static Long parse(String id) {
        if (id == null || !id.startsWith(PREFIX)) {
            return null;
        }
        try {
            return Long.parseLong(id.substring(PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
