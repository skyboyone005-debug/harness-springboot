package com.harness.gateway;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gateway.upstreams.user=http://localhost:8082（环境变量 GATEWAY_UPSTREAMS_USER 亦可）。
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(Map<String, URI> upstreams, List<String> corsOrigins) {

    public GatewayProperties {
        upstreams = upstreams == null ? Map.of() : Map.copyOf(upstreams);
        corsOrigins = corsOrigins == null ? List.of("http://localhost:5173") : List.copyOf(corsOrigins);
    }
}
