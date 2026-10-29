package com.harness.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import com.harness.contract.Contract;

/** 契约测试：所有请求经 {@link Contract}，自动校验路由声明、响应结构与 operation 覆盖率。 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class UserContractTest {

    @RegisterExtension
    static Contract contract = Contract.forSpec("src/main/resources/openapi.yaml");

    @Autowired
    MockMvc mvc;

    @Test
    void createGetAndList() {
        var api = contract.on(mvc);
        User u = api.post("/v1/users", Map.of("name", " Alice ", "email", "Alice@Example.com")).expect(201).as(User.class);
        assertThat(u.email()).isEqualTo("alice@example.com");
        assertThat(u.name()).isEqualTo("Alice");

        api.get("/v1/users/" + u.id()).expect(200);
        api.get("/v1/users/u_404").expect(404);
        api.get("/v1/users/not-an-id").expect(404); // 非法 id 视为不存在，而不是 500

        Map<?, ?> page = api.get("/v1/users?limit=10").expect(200).as(Map.class);
        assertThat((List<?>) page.get("items")).anySatisfy(item -> assertThat(((Map<?, ?>) item).get("id")).isEqualTo(u.id()));
        api.get("/v1/users?limit=0").expect(400);
    }

    @Test
    void duplicateEmailIsConflict() {
        var api = contract.on(mvc);
        api.post("/v1/users", Map.of("name", "Bob", "email", "bob@example.com")).expect(201);
        api.post("/v1/users", Map.of("name", "Bob2", "email", "BOB@example.com")).expect(409);
    }

    @Test
    void invalidInputIsBadRequest() {
        var api = contract.on(mvc);
        for (Object body : List.of(
                Map.of("name", "", "email", "x@y.z"),
                Map.of("name", "Carl", "email", "nope"),
                Map.of("name", "Carl", "email", "c@x.io", "role", "admin"), // 未知字段（契约 additionalProperties:false）
                "{not json")) {
            api.post("/v1/users", body).expect(400);
        }
    }
}
