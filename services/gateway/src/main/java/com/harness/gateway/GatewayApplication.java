package com.harness.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 前端唯一入口：/api/{svc}/** -> {svc} 上游（剥离前缀）。上游可以是真实服务，也可以是 Prism mock。
 * 生产环境建议换成 Spring Cloud Gateway；这里保持零额外依赖，便于演示与本地联调。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
