package com.harness.order;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.harness.common.ApiException;
import com.harness.common.RequestIdFilter;

@Component
class HttpUserClient implements UserClient {

    private final RestClient http;

    HttpUserClient(@Value("${user-svc.url:http://localhost:8082}") String baseUrl) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(1));
        f.setReadTimeout(Duration.ofSeconds(2));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }

    @Override
    public boolean exists(String userId) {
        try {
            return http.get().uri("/v1/users/{id}", userId)
                    .header(RequestIdFilter.HEADER, RequestIdFilter.current()) // 链路透传
                    .exchange((req, res) -> switch (res.getStatusCode().value()) {
                        case 200 -> true;
                        case 404 -> false;
                        default -> throw new IllegalStateException("user-svc unexpected status " + res.getStatusCode());
                    });
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "upstream_unavailable", "user-svc unavailable");
        }
    }
}
