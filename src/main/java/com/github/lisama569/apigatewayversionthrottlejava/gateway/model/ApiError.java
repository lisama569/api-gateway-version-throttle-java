package com.github.lisama569.apigatewayversionthrottlejava.gateway.model;

/** 归一化后的错误类别。 */
public enum ApiError {
    UNSUPPORTED_VERSION,
    UNKNOWN_ROUTE,
    RATE_LIMITED,
    BACKEND_TIMEOUT,
    BACKEND_INTERNAL_ERROR,
    BUSINESS_REJECTED,
    CIRCUIT_OPEN
}
