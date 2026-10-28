package com.harness.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.harness.common.ApiException;
import com.harness.contract.Contract;
import com.harness.order.Models.Order;

@SpringBootTest
@AutoConfigureMockMvc
class OrderContractTest {

    @RegisterExtension
    static Contract contract = Contract.forSpec("src/main/resources/openapi.yaml");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UserClient users; // 不启动 user-service：依赖边界用 Mock 替换

    @BeforeEach
    void knownUsers() {
        given(users.exists(anyString())).willReturn(false);
        given(users.exists("u_1")).willReturn(true);
    }

    static Map<String, Object> order(String userId, int qty) {
        return Map.of("userId", userId, "items", List.of(Map.of("sku", "SKU-1", "qty", qty, "priceCents", 150)));
    }

    @Test
    void createGetAndList() {
        var api = contract.on(mvc);
        Order o = api.post("/v1/orders", Map.of("userId", "u_1", "items", List.of(
                Map.of("sku", "A", "qty", 2, "priceCents", 150),
                Map.of("sku", "B", "qty", 1, "priceCents", 99)))).expect(201).as(Order.class);
        assertThat(o.totalCents()).isEqualTo(399);
        assertThat(o.status()).isEqualTo(Models.Status.pending);

        api.get("/v1/orders/" + o.id()).expect(200);
        api.get("/v1/orders/o_404").expect(404);
        Map<?, ?> page = api.get("/v1/orders?userId=u_1").expect(200).as(Map.class);
        assertThat((List<?>) page.get("items")).isNotEmpty();
        api.get("/v1/orders?userId=nobody").expect(200);
        api.get("/v1/orders").expect(400); // userId 必填
    }

    @Test
    void unknownUserIsUnprocessable() {
        contract.on(mvc).post("/v1/orders", order("u_x", 1)).expect(422);
    }

    @Test
    void invalidOrderIsBadRequest() {
        var api = contract.on(mvc);
        api.post("/v1/orders", Map.of("userId", "u_1", "items", List.of())).expect(400);
        api.post("/v1/orders", order("u_1", 0)).expect(400);
    }

    @Test
    void userServiceDownIsBadGateway() {
        given(users.exists("u_1")).willThrow(new ApiException(HttpStatus.BAD_GATEWAY, "upstream_unavailable", "down"));
        contract.on(mvc).post("/v1/orders", order("u_1", 1)).expect(502);
    }
}
