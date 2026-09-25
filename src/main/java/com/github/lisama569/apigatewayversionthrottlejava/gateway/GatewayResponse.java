package com.github.lisama569.apigatewayversionthrottlejava.gateway;

/**
 * 网关统一响应模型。
 */
public record GatewayResponse(
        boolean success,
        String route,
        String apiVersion,
        boolean deprecatedVersion,
        String body,
        ErrorCategory errorCategory,
        String errorMessage,
        Long retryAfterMillis) {

    public static GatewayResponse ok(String route, String apiVersion, boolean deprecated, String body) {
        return new GatewayResponse(true, route, apiVersion, deprecated, body, null, null, null);
    }

    public static GatewayResponse rejected(String route, String apiVersion, boolean deprecated,
                                           ErrorCategory category, String message, Long retryAfterMillis) {
        return new GatewayResponse(false, route, apiVersion, deprecated, null, category, message, retryAfterMillis);
    }
}
