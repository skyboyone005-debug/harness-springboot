package com.harness.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.slf4j.MDC;

import com.harness.common.ApiException;
import com.harness.common.RequestIdFilter;
import com.sun.net.httpserver.HttpServer;

/** 真实 HTTP 客户端行为：用 JDK 内置 HttpServer 模拟 user-service，无需额外依赖。 */
class HttpUserClientTest {

    HttpServer upstream;
    String lastRequestId;

    @BeforeEach
    void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/users/", ex -> {
            lastRequestId = ex.getRequestHeaders().getFirst("X-Request-Id");
            ex.sendResponseHeaders(ex.getRequestURI().getPath().endsWith("/u_1") ? 200 : 404, -1);
            ex.close();
        });
        upstream.start();
    }

    @AfterEach
    void stop() {
        upstream.stop(0);
    }

    @Test
    void mapsStatusCodesAndPropagatesRequestId() {
        UserClient c = new HttpUserClient("http://127.0.0.1:" + upstream.getAddress().getPort());
        MDC.put(RequestIdFilter.MDC_KEY, "req_test");
        try {
            assertThat(c.exists("u_1")).isTrue();
            assertThat(lastRequestId).isEqualTo("req_test");
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
        assertThat(c.exists("u_2")).isFalse();
    }

    @Test
    void unreachableUpstreamIsBadGateway() {
        int port = upstream.getAddress().getPort();
        upstream.stop(0);
        UserClient c = new HttpUserClient("http://127.0.0.1:" + port);
        assertThatThrownBy(() -> c.exists("u_1")).isInstanceOf(ApiException.class).hasMessageContaining("unavailable");
    }
}
