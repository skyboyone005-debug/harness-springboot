package com.harness.order;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.harness.common.ApiException;
import com.harness.order.Models.CreateOrderRequest;
import com.harness.order.Models.Order;
import com.harness.order.Models.Status;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/orders")
class OrderController {

    private final OrderStore store;
    private final UserClient users;

    OrderController(OrderStore store, UserClient users) {
        this.store = store;
        this.users = users;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Order create(@Valid @RequestBody CreateOrderRequest in) {
        long total = in.items().stream().mapToLong(i -> (long) i.qty() * i.priceCents()).sum();
        if (!users.exists(in.userId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "user_not_found", "user " + in.userId() + " does not exist");
        }
        return store.save(new Order(null, in.userId(), in.items(), total, Status.pending, null));
    }

    @GetMapping
    Map<String, List<Order>> list(@RequestParam String userId) {
        return Map.of("items", store.byUser(userId));
    }

    @GetMapping("/{id}")
    Order get(@PathVariable String id) {
        return store.find(id).orElseThrow(() -> ApiException.notFound("order"));
    }
}
