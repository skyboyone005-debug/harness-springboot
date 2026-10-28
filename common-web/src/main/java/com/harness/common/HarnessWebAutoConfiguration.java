package com.harness.common;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

import com.fasterxml.jackson.databind.DeserializationFeature;

/**
 * 引入 common-web 即生效（Spring Boot 自动配置），服务无需任何额外代码：
 * 统一错误体、request-id、严格 JSON（拒绝未知字段，对齐契约中的 additionalProperties: false）。
 */
@AutoConfiguration
@Import({GlobalExceptionHandler.class, HealthController.class})
public class HarnessWebAutoConfiguration {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> bean = new FilterRegistrationBean<>(new RequestIdFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictJson() {
        return b -> b.featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
