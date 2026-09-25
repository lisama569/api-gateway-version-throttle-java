package com.github.lisama569.apigatewayversionthrottlejava.tests;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.ErrorCategory;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.GatewayRequest;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.GatewayResponse;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.GatewayService;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.backend.BackendHandler;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.backend.BackendInternalException;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.backend.BusinessRejectedException;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.GatewayProperties;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle.TenantRateLimiter;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.version.VersionRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后端故障归一化：超时 / 内部错误 / 业务拒绝映射为可区分类别；
 * 原始异常不透出；失败请求归还限流额度。
 */
class BackendFailureNormalizationTest {

    private static final Logger log = LoggerFactory.getLogger(BackendFailureNormalizationTest.class);
    private static final String TENANT = "tenant-fail";

    private GatewayService gatewayService;

    private static BackendHandler stub(String route, ThrowingBody body) {
        return new BackendHandler() {
            @Override
            public String route() {
                return route;
            }

            @Override
            public String version() {
                return "v2";
            }

            @Override
            public String handle(GatewayRequest request) throws Exception {
                return body.run();
            }
        };
    }

    @FunctionalInterface
    interface ThrowingBody {
        String run() throws Exception;
    }

    @BeforeEach
    void setUp() {
        GatewayProperties properties = new GatewayProperties();
        properties.setVersions(Map.of("v2", "ACTIVE"));
        List<BackendHandler> handlers = List.of(
                stub("ok", () -> "fine"),
                stub("biz-reject", () -> {
                    throw new BusinessRejectedException("duplicate order id=12345");
                }),
                stub("internal", () -> {
                    throw new BackendInternalException("NullPointer at dao layer");
                }),
                stub("slow", () -> {
                    Thread.sleep(10_000);
                    return "too late";
                }),
                stub("raw", () -> {
                    throw new RuntimeException("secret-db-password-leak");
                }));
        VersionRouter router = new VersionRouter(properties, handlers);
        // 容量 1、几乎不补充：便于验证失败归还额度
        TenantRateLimiter limiter = new TenantRateLimiter(1, 0.0001);
        gatewayService = new GatewayService(router, limiter, 300,
                Executors.newCachedThreadPool());
    }

    private GatewayResponse call(String route) {
        GatewayRequest req = new GatewayRequest(TENANT, "v2", route, "payload");
        log.info("[test-input] {}", req);
        GatewayResponse resp = gatewayService.handle(req);
        log.info("[test-basis] route={} success={} category={} message={}",
                route, resp.success(), resp.errorCategory(), resp.errorMessage());
        return resp;
    }

    @Test
    void businessRejectionMapsToDistinctCategory() {
        GatewayResponse resp = call("biz-reject");
        assertFalse(resp.success());
        assertEquals(ErrorCategory.BUSINESS_REJECTED, resp.errorCategory());
    }

    @Test
    void internalErrorMapsToDistinctCategory() {
        GatewayResponse resp = call("internal");
        assertFalse(resp.success());
        assertEquals(ErrorCategory.BACKEND_INTERNAL_ERROR, resp.errorCategory());
    }

    @Test
    void slowBackendMapsToTimeout() {
        GatewayResponse resp = call("slow");
        assertFalse(resp.success());
        assertEquals(ErrorCategory.BACKEND_TIMEOUT, resp.errorCategory());
    }

    @Test
    void rawExceptionNeverLeaksToCaller() {
        GatewayResponse resp = call("raw");
        assertFalse(resp.success());
        assertEquals(ErrorCategory.BACKEND_INTERNAL_ERROR, resp.errorCategory());
        assertFalse(resp.errorMessage().contains("secret-db-password-leak"),
                "raw exception detail must not leak, got: " + resp.errorMessage());
    }

    @Test
    void failedRequestReleasesItsQuota() {
        // 容量为 1：第一次请求业务拒绝失败，额度必须被归还，
        // 因此第二次请求仍能放行而不是被限流。
        GatewayResponse first = call("biz-reject");
        assertEquals(ErrorCategory.BUSINESS_REJECTED, first.errorCategory());
        GatewayResponse second = call("ok");
        assertTrue(second.success(),
                "quota of the failed request must have been released, but got: " + second.errorCategory());
        log.info("[test-basis] failed request quota released -> subsequent request allowed");
    }
}
