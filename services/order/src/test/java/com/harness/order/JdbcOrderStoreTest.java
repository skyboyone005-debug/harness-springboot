package com.harness.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.harness.order.Models.LineItem;
import com.harness.order.Models.Order;
import com.harness.order.Models.Status;

/** 存储层：明细与订单头同事务，任一明细写入失败整体回滚。 */
@SpringBootTest
class JdbcOrderStoreTest {

    @Autowired
    OrderStore store;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    UserClient users;

    @Test
    void savesHeadAndItemsAndReadsThemBack() {
        Order o = store.save(new Order(null, "u_tx1", List.of(new LineItem("A", 2, 150), new LineItem("B", 1, 99)), 399, Status.pending, null));
        Order back = store.find(o.id()).orElseThrow();
        assertThat(back.items()).containsExactly(new LineItem("A", 2, 150), new LineItem("B", 1, 99));
        assertThat(back.createdAt()).isEqualTo(o.createdAt());
        assertThat(store.find("garbage")).isEmpty();
    }

    @Test
    void failingItemRollsBackWholeOrder() {
        String tooLong = "X".repeat(65); // 超过 order_items.sku VARCHAR(64)
        assertThatThrownBy(() -> store.save(new Order(null, "u_tx2",
                List.of(new LineItem("OK", 1, 1), new LineItem(tooLong, 1, 1)), 2, Status.pending, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        Long rows = jdbc.sql("SELECT COUNT(*) FROM orders WHERE user_id = 'u_tx2'").query(Long.class).single();
        assertThat(rows).as("order head must be rolled back with its items").isZero();
    }
}
