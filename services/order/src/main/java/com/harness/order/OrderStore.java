package com.harness.order;

import java.util.List;
import java.util.Optional;

import com.harness.order.Models.Order;

/** 持久化边界：Controller 只依赖这个接口。 */
interface OrderStore {

    /** 保存订单及其明细（同一事务），返回带 id 与创建时间的订单。 */
    Order save(Order draft);

    Optional<Order> find(String id);

    List<Order> byUser(String userId);
}
