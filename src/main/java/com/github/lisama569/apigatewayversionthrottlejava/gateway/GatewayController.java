package com.github.lisama569.apigatewayversionthrottlejava.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网关 REST 入口：POST /gateway/{route}
 * 请求头：X-Tenant-Id（租户）、X-Api-Version（接口版本）。
 */
@RestController
public class GatewayController {

    private final GatewayService gatewayService;

    public GatewayController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @PostMapping("/gateway/{route}")
    public ResponseEntity<GatewayResponse> handle(
            @PathVariable String route,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-Api-Version") String apiVersion,
            @RequestBody(required = false) String payload) {
        GatewayResponse response = gatewayService.handle(
                new GatewayRequest(tenantId, apiVersion, route, payload == null ? "" : payload));
        return ResponseEntity.status(httpStatus(response)).body(response);
    }

    private HttpStatus httpStatus(GatewayResponse response) {
        if (response.success()) {
            return HttpStatus.OK;
        }
        return switch (response.errorCategory()) {
            case VERSION_UNSUPPORTED, ROUTE_UNKNOWN -> HttpStatus.BAD_REQUEST;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case BACKEND_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case BUSINESS_REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case BACKEND_INTERNAL_ERROR -> HttpStatus.BAD_GATEWAY;
        };
    }
}
