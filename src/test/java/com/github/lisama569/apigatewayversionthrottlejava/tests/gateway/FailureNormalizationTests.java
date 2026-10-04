package com.github.lisama569.apigatewayversionthrottlejava.tests.gateway;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.GatewayException;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.GatewayService;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.ThrottleProperties;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.error.BusinessRejectionException;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.error.ErrorNormalizer;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.handler.HandlerRegistry;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.ApiError;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.ApiVersion;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.GatewayRequest;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.route.VersionRouter;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.circuit.TenantCircuitBreaker;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.CircuitBreakerProperties;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.TimeoutProperties;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle.TenantRateLimiter;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.timeout.BackendInvoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/** 场景三：后端故障归一化——超时/内部错误/业务拒绝映射为可区分类别，原始异常不透出。 */
class FailureNormalizationTests {

    private static final Logger log = LoggerFactory.getLogger(FailureNormalizationTests.class);

    private static final String SECRET = "jdbc:secret-host:5432/prod-db";

    private GatewayService gatewayService;

    @BeforeEach
    void setUp() {
        HandlerRegistry registry = new HandlerRegistry();
        registry.register("timeout", ApiVersion.V2, false, req -> {
            throw new TimeoutException("backend call exceeded 2000ms deadline");
        });
        registry.register("boom", ApiVersion.V2, false, req -> {
            throw new IllegalStateException("connection refused to " + SECRET);
        });
        registry.register("reject", ApiVersion.V2, false, req -> {
            throw new BusinessRejectionException("insufficient balance");
        });

        ThrottleProperties props = new ThrottleProperties();
        props.setDefaultLimit(100);
        props.setWindowMillis(60_000L);
        props.setTenantLimits(Map.of());

        this.gatewayService = new GatewayService(
                new VersionRouter(registry), new TenantRateLimiter(props), new ErrorNormalizer(),
                new BackendInvoker(),
                new TenantCircuitBreaker(new CircuitBreakerProperties()),
                new TimeoutProperties());
    }

    private GatewayException dispatch(String route, String tenant) {
        GatewayRequest req = new GatewayRequest(tenant, route, "v2", "payload-of-" + route);
        log.info("[failure] input: tenantId={}, route={}, version={}", req.tenantId(), req.route(), req.version());
        GatewayException ex = assertThrows(GatewayException.class, () -> gatewayService.dispatch(req));
        log.info("[failure] decision: error={}, reason={}, retryable={}",
                ex.getError(), ex.getMessage(), ex.isRetryable());
        return ex;
    }

    @Test
    void timeoutMapsToBackendTimeout() {
        GatewayException ex = dispatch("timeout", "t-fail-1");
        assertEquals(ApiError.BACKEND_TIMEOUT, ex.getError());
        assertTrue(ex.isRetryable(), "超时类故障应提示可重试");
    }

    @Test
    void unknownThrowableMapsToInternalErrorAndRawMessageNotLeaked() {
        GatewayException ex = dispatch("boom", "t-fail-2");
        assertEquals(ApiError.BACKEND_INTERNAL_ERROR, ex.getError());
        assertFalse(ex.getMessage().contains(SECRET), "原始异常内容不得透出给客户端");
        assertEquals("backend internal error", ex.getMessage());
    }

    @Test
    void businessRejectionMapsToBusinessRejected() {
        GatewayException ex = dispatch("reject", "t-fail-3");
        assertEquals(ApiError.BUSINESS_REJECTED, ex.getError());
        assertFalse(ex.isRetryable(), "业务拒绝不应提示重试");
        assertEquals("insufficient balance", ex.getMessage());
    }

    @Test
    void categoriesAreDistinct() {
        GatewayException t = dispatch("timeout", "t-fail-4");
        GatewayException i = dispatch("boom", "t-fail-4");
        GatewayException b = dispatch("reject", "t-fail-4");
        log.info("[failure] decision: 三类故障类别分别为 {}, {}, {}",
                t.getError(), i.getError(), b.getError());
        assertNotEquals(t.getError(), i.getError());
        assertNotEquals(i.getError(), b.getError());
        assertNotEquals(t.getError(), b.getError());
    }
}
