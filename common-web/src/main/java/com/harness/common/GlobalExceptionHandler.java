package com.harness.common;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * 所有异常 -> 统一错误体。服务代码只需抛 {@link ApiException}，不要自己拼错误响应。
 * 框架层异常（参数校验、JSON 解析、404/405）也在这里归一，保证任何错误都符合契约中的 Error schema。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> api(ApiException e) {
        return body(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> invalidBody(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, "bad_request", msg);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorBody> invalidParams(HandlerMethodValidationException e) {
        String msg = e.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(err -> r.getMethodParameter().getParameterName() + " " + err.getDefaultMessage()))
                .sorted()
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, "bad_request", msg);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ErrorBody> badRequest(Exception e) {
        String msg = e instanceof HttpMessageNotReadableException ? jsonError(e) : e.getMessage();
        return body(HttpStatus.BAD_REQUEST, "bad_request", msg);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorBody> mediaType(HttpMediaTypeNotSupportedException e) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorBody> noRoute(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "not_found", "no route " + e.getResourcePath());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorBody> method(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> internal(Exception e) {
        log.error("unhandled exception", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "internal error"); // 不向客户端泄露内部信息
    }

    private static ResponseEntity<ErrorBody> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorBody(code, message, MDC.get(RequestIdFilter.MDC_KEY)));
    }

    /** 只返回对前端有用的信息，不泄露内部类名。 */
    private static String jsonError(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof UnrecognizedPropertyException u) {
                return "unknown field '" + u.getPropertyName() + "'";
            }
            if (c instanceof InvalidFormatException f) {
                return "invalid value for field '" + f.getPath().stream().map(r -> r.getFieldName() == null ? "[" + r.getIndex() + "]" : r.getFieldName())
                        .collect(Collectors.joining(".")) + "'";
            }
        }
        return "malformed json";
    }
}
