package com.github.lisama569.apigatewayversionthrottlejava.tests;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.ErrorCategory;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.GatewayRequest;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.GatewayResponse;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.GatewayService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本路由：不支持的版本被拒绝且原因可区分；弃用版本仍可用但响应须标记；
 * 未知路由不得静默落到最新版本。
 */
@SpringBootTest
class VersionRoutingTest {

    private static final Logger log = LoggerFactory.getLogger(VersionRoutingTest.class);

    @Autowired
    private GatewayService gatewayService;

    @Test
    void unsupportedVersionIsRejectedWithDistinctReason() {
        GatewayRequest req = new GatewayRequest("tenant-v", "v99", "echo", "hello");
        log.info("[test-input] {}", req);
        GatewayResponse resp = gatewayService.handle(req);
        log.info("[test-basis] success={} category={} message={}",
                resp.success(), resp.errorCategory(), resp.errorMessage());
        assertFalse(resp.success());
        assertEquals(ErrorCategory.VERSION_UNSUPPORTED, resp.errorCategory());
        assertTrue(resp.errorMessage().contains("v99"), "reason must identify the rejected version");
    }

    @Test
    void deprecatedVersionStillWorksButIsMarked() {
        GatewayRequest req = new GatewayRequest("tenant-v", "v1", "echo", "hello");
        log.info("[test-input] {}", req);
        GatewayResponse resp = gatewayService.handle(req);
        log.info("[test-basis] success={} deprecated={} body={}",
                resp.success(), resp.deprecatedVersion(), resp.body());
        assertTrue(resp.success(), "deprecated version must remain usable");
        assertTrue(resp.deprecatedVersion(), "response must mark deprecated version");
        assertTrue(resp.body().startsWith("v1 "), "request must be handled by the v1 implementation");
    }

    @Test
    void activeVersionRoutesToCurrentImplementation() {
        GatewayRequest req = new GatewayRequest("tenant-v", "v2", "echo", "hello");
        log.info("[test-input] {}", req);
        GatewayResponse resp = gatewayService.handle(req);
        log.info("[test-basis] success={} deprecated={} body={}",
                resp.success(), resp.deprecatedVersion(), resp.body());
        assertTrue(resp.success());
        assertFalse(resp.deprecatedVersion());
        assertTrue(resp.body().startsWith("v2 "), "request must be handled by the v2 implementation");
    }

    @Test
    void unknownRouteNeverFallsBackToLatestVersion() {
        GatewayRequest req = new GatewayRequest("tenant-v", "v2", "no-such-route", "hello");
        log.info("[test-input] {}", req);
        GatewayResponse resp = gatewayService.handle(req);
        log.info("[test-basis] success={} category={} message={}",
                resp.success(), resp.errorCategory(), resp.errorMessage());
        assertFalse(resp.success());
        assertEquals(ErrorCategory.ROUTE_UNKNOWN, resp.errorCategory());
        assertNull(resp.body(), "unknown route must not be silently served by any version");
    }
}
