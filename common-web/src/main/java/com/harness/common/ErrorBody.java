package com.harness.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 统一错误体，对应各服务 openapi.yaml 的 components.schemas.Error。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(String code, String message, String requestId) {
}
