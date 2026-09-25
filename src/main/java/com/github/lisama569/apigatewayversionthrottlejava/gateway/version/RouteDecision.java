package com.github.lisama569.apigatewayversionthrottlejava.gateway.version;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.backend.BackendHandler;

/** 版本路由判定结果 */
public record RouteDecision(Resolution resolution, BackendHandler handler, boolean deprecated, String reason) {

    public enum Resolution {
        OK,
        UNSUPPORTED_VERSION,
        UNKNOWN_ROUTE
    }

    public static RouteDecision ok(BackendHandler handler, boolean deprecated) {
        return new RouteDecision(Resolution.OK, handler, deprecated, null);
    }

    public static RouteDecision unsupportedVersion(String reason) {
        return new RouteDecision(Resolution.UNSUPPORTED_VERSION, null, false, reason);
    }

    public static RouteDecision unknownRoute(String reason) {
        return new RouteDecision(Resolution.UNKNOWN_ROUTE, null, false, reason);
    }
}
