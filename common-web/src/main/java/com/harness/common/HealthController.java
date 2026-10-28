package com.harness.common;

import java.io.IOException;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 健康检查 + 暴露服务自身契约（前端 / 网关 / 文档工具可直接拉取）。 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping(value = "/openapi.yaml", produces = "application/yaml")
    ResponseEntity<byte[]> spec() throws IOException {
        ClassPathResource r = new ClassPathResource("openapi.yaml");
        if (!r.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/yaml"))
                .body(r.getContentAsByteArray());
    }
}
