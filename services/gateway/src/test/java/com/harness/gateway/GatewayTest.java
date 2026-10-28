package com.harness.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.sun.net.httpserver.HttpServer;

@SpringBootTest
@AutoConfigureMockMvc
class GatewayTest {

    static HttpServer upstream = start();

    static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                byte[] in = ex.getRequestBody().readAllBytes();
                String echo = "{\"path\":\"" + ex.getRequestURI() + "\",\"rid\":\"" + ex.getRequestHeaders().getFirst("X-Request-Id")
                        + "\",\"body\":" + (in.length == 0 ? "null" : new String(in, StandardCharsets.UTF_8)) + "}";
                byte[] out = echo.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(201, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry r) {
        r.add("gateway.upstreams.user", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
        r.add("gateway.upstreams.order", () -> "http://127.0.0.1:1"); // 不可达端口
    }

    @AfterAll
    static void stop() {
        upstream.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void stripsPrefixForwardsBodyQueryAndRequestId() throws Exception {
        mvc.perform(post("/api/user/v1/users?x=1").header("X-Request-Id", "req_fe")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.path").value("/v1/users?x=1"))
                .andExpect(jsonPath("$.rid").value("req_fe"))
                .andExpect(jsonPath("$.body.name").value("a"));
    }

    @Test
    void unknownServiceAndDownUpstreamUseErrorBody() throws Exception {
        mvc.perform(get("/api/nope/v1/x")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("unknown_service"));
        mvc.perform(get("/api/order/v1/orders/o_1")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("upstream_unavailable"));
    }

    @Test
    void corsPreflight() throws Exception {
        mvc.perform(options("/api/user/v1/users").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/user/v1/users").header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(content().string("Invalid CORS request"));
    }
}
