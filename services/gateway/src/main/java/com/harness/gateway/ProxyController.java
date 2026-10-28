package com.harness.gateway;

import java.io.IOException;
import java.net.URI;
import java.util.Enumeration;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.harness.common.ApiException;
import com.harness.common.RequestIdFilter;

import jakarta.servlet.http.HttpServletRequest;

@RestController
class ProxyController {

    /** 逐跳头不转发（RFC 7230 §6.1）。 */
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length");

    private final GatewayProperties props;
    private final RestClient http = RestClient.create();

    ProxyController(GatewayProperties props) {
        this.props = props;
    }

    @RequestMapping("/api/{svc}/**")
    ResponseEntity<byte[]> proxy(@PathVariable String svc, HttpServletRequest req) throws IOException {
        URI base = props.upstreams().get(svc);
        if (base == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "unknown_service", "no upstream for service '" + svc + "'");
        }
        String rest = req.getRequestURI().substring(("/api/" + svc).length());
        URI target = URI.create(base.toString().replaceAll("/$", "") + rest
                + (req.getQueryString() == null ? "" : "?" + req.getQueryString()));
        byte[] body = req.getInputStream().readAllBytes();
        try {
            return http.method(HttpMethod.valueOf(req.getMethod()))
                    .uri(target)
                    .headers(h -> copyRequestHeaders(req, h))
                    .body(body)
                    .exchange((rq, rs) -> {
                        HttpHeaders out = new HttpHeaders();
                        rs.getHeaders().forEach((k, v) -> {
                            if (!HOP_BY_HOP.contains(k.toLowerCase())) {
                                out.put(k, v);
                            }
                        });
                        return ResponseEntity.status(rs.getStatusCode()).headers(out).body(rs.getBody().readAllBytes());
                    });
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "upstream_unavailable", svc + " unavailable");
        }
    }

    private static void copyRequestHeaders(HttpServletRequest req, HttpHeaders h) {
        for (Enumeration<String> names = req.getHeaderNames(); names.hasMoreElements(); ) {
            String n = names.nextElement();
            if (!HOP_BY_HOP.contains(n.toLowerCase())) {
                h.put(n, java.util.Collections.list(req.getHeaders(n)));
            }
        }
        h.set(RequestIdFilter.HEADER, RequestIdFilter.current()); // 网关生成的 request-id 贯穿下游
    }
}
