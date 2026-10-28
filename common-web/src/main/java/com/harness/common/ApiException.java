package com.harness.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常。{@code code} 与 openapi.yaml 中 Error.code 对应，是前端分支依据 —— 改名即破坏性变更。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", what + " not found");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
