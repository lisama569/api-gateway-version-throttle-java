package com.github.lisama569.apigatewayversionthrottlejava.gateway.web;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.GatewayException;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.ApiError;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.GatewayResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将归一化异常映射为稳定的 HTTP 状态码与统一错误信封。
 */
@RestControllerAdvice
public class GatewayExceptionAdvice {

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<GatewayResponse> handle(GatewayException ex) {
        HttpStatus status = switch (ex.getError()) {
            case UNSUPPORTED_VERSION -> HttpStatus.BAD_REQUEST;
            case UNKNOWN_ROUTE -> HttpStatus.NOT_FOUND;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case BUSINESS_REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case BACKEND_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case BACKEND_INTERNAL_ERROR -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(GatewayResponse.rejected(
                ex.getError(), ex.getMessage(), ex.isRetryable(), ex.getVersionUsed(), ex.getDetail()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<GatewayResponse> handleUnexpected(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(GatewayResponse.rejected(
                ApiError.BACKEND_INTERNAL_ERROR, "backend internal error", true, null, null));
    }
}
