package com.github.lisama569.apigatewayversionthrottlejava.tests.gateway;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.GatewayException;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.GatewayService;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.ThrottleProperties;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.error.ErrorNormalizer;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.handler.HandlerRegistry;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.handler.impl.EchoV1Handler;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.handler.impl.EchoV2Handler;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.ApiError;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.ApiVersion;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.GatewayRequest;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.GatewayResponse;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.route.VersionRouter;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle.TenantRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 场景一：版本不支持、版本弃用、未知路由不回落。 */
class VersionRoutingTests {

    private static final Logger log = LoggerFactory.getLogger(VersionRoutingTests.class);

    private GatewayService gatewayService;

    @BeforeEach
    void setUp() {
        HandlerRegistry registry = new HandlerRegistry();
        registry.register("echo", ApiVersion.V1, true, new EchoV1Handler());
        registry.register("echo", ApiVersion.V2, false, new EchoV2Handler());
        // 仅提供 v2 的路由，用来区分"版本字符串无法识别"与"该路由不支持此版本"
        registry.register("v2only", ApiVersion.V2, false, new EchoV2Handler());

        ThrottleProperties props = new ThrottleProperties();
        props.setDefaultLimit(100);
        props.setWindowMillis(60_000L);
        props.setTenantLimits(Map.of());
        TenantRateLimiter limiter = new TenantRateLimiter(props);

        this.gatewayService = new GatewayService(
                new VersionRouter(registry), limiter, new ErrorNormalizer());
    }

    @Test
    void deprecatedVersionStillServedAndFlagged() {
        GatewayRequest req = new GatewayRequest("t-route-1", "echo", "v1", "hello-v1");
        log.info("[version] input: tenantId={}, route={}, version={}, payload={}",
                req.tenantId(), req.route(), req.version(), req.payload());

        GatewayResponse resp = gatewayService.dispatch(req);

        log.info("[version] decision: success={}, versionUsed={}, deprecatedVersion={}, data={}",
                resp.isSuccess(), resp.getVersionUsed(), resp.isDeprecatedVersion(), resp.getData());
        assertTrue(resp.isSuccess());
        assertEquals("v1", resp.getVersionUsed());
        assertTrue(resp.isDeprecatedVersion(), "弃用版本必须在响应中明确标记");
    }

    @Test
    void currentVersionNotFlaggedDeprecated() {
        GatewayRequest req = new GatewayRequest("t-route-2", "echo", "v2", "hello-v2");
        log.info("[version] input: tenantId={}, route={}, version={}", req.tenantId(), req.route(), req.version());

        GatewayResponse resp = gatewayService.dispatch(req);

        log.info("[version] decision: success={}, versionUsed={}, deprecatedVersion={}",
                resp.isSuccess(), resp.getVersionUsed(), resp.isDeprecatedVersion());
        assertTrue(resp.isSuccess());
        assertFalse(resp.isDeprecatedVersion());
    }

    @Test
    void unknownVersionStringRejected() {
        GatewayRequest req = new GatewayRequest("t-route-3", "echo", "v9", null);
        log.info("[version] input: route={}, version={}（无法识别的版本字符串）", req.route(), req.version());

        GatewayException ex = assertThrows(GatewayException.class, () -> gatewayService.dispatch(req));
        log.info("[version] decision: error={}, reason={}, detail={}",
                ex.getError(), ex.getMessage(), ex.getDetail());

        assertEquals(ApiError.UNSUPPORTED_VERSION, ex.getError());
        assertTrue(ex.getDetail().get("supportedVersions").toString().contains("v2"));
    }

    @Test
    void routeDoesNotSupportVersionRejected() {
        GatewayRequest req = new GatewayRequest("t-route-4", "v2only", "v1", null);
        log.info("[version] input: route={}, version={}（该路由仅注册 v2）", req.route(), req.version());

        GatewayException ex = assertThrows(GatewayException.class, () -> gatewayService.dispatch(req));
        log.info("[version] decision: error={}, reason={}, detail={}",
                ex.getError(), ex.getMessage(), ex.getDetail());

        assertEquals(ApiError.UNSUPPORTED_VERSION, ex.getError());
        assertEquals("[v2]", ex.getDetail().get("supportedVersions").toString());
    }

    @Test
    void unknownRouteRejectedAndNeverFallsBackToLatest() {
        GatewayRequest req = new GatewayRequest("t-route-5", "does-not-exist", "v2", null);
        log.info("[version] input: route={}, version={}（未知路由，不得回落到最新版本）", req.route(), req.version());

        GatewayException ex = assertThrows(GatewayException.class, () -> gatewayService.dispatch(req));
        log.info("[version] decision: error={}, reason={}", ex.getError(), ex.getMessage());

        assertEquals(ApiError.UNKNOWN_ROUTE, ex.getError());
    }
}
