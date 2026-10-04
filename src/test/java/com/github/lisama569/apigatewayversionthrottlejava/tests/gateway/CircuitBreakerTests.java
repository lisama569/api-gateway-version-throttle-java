package com.github.lisama569.apigatewayversionthrottlejava.tests.gateway;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.GatewayException;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.GatewayService;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.circuit.TenantCircuitBreaker;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.CircuitBreakerProperties;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.ThrottleProperties;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.TimeoutProperties;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.error.ErrorNormalizer;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.handler.HandlerRegistry;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.ApiError;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.ApiVersion;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.GatewayRequest;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.GatewayResponse;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.route.VersionRouter;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle.TenantRateLimiter;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.timeout.BackendInvoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 场景六：连续超时触发快速失败，保护期内直接挡流并提示重试时间，保护期结束自动恢复。 */
class CircuitBreakerTests {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerTests.class);

    private static final String TENANT = "tenant-circuit";
    private static final int THRESHOLD = 3;
    private static final long OPEN_MS = 400L;
    private static final long TIMEOUT_MS = 100L;

    private GatewayService gatewayService;
    private TenantRateLimiter rateLimiter;
    private final AtomicBoolean backendStuck = new AtomicBoolean(true);
    private final AtomicInteger backendCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        HandlerRegistry registry = new HandlerRegistry();
        // 可切换的后端：卡住时永不返回，恢复后立即成功
        registry.register("flaky", ApiVersion.V2, false, req -> {
            backendCalls.incrementAndGet();
            if (backendStuck.get()) {
                new CountDownLatch(1).await();
                return "never";
            }
            return "recovered";
        });

        ThrottleProperties throttle = new ThrottleProperties();
        throttle.setDefaultLimit(1000);
        throttle.setWindowMillis(60_000L);
        throttle.setTenantLimits(Map.of());
        this.rateLimiter = new TenantRateLimiter(throttle);

        TimeoutProperties timeout = new TimeoutProperties();
        timeout.setDefaultMillis(TIMEOUT_MS);
        timeout.setRouteOverrides(Map.of());

        CircuitBreakerProperties circuit = new CircuitBreakerProperties();
        circuit.setFailureThreshold(THRESHOLD);
        circuit.setOpenMillis(OPEN_MS);

        this.gatewayService = new GatewayService(
                new VersionRouter(registry), rateLimiter, new ErrorNormalizer(),
                new BackendInvoker(),
                new TenantCircuitBreaker(circuit),
                timeout);
    }

    private GatewayException expectError(String tenant, ApiError expected, String tag) {
        GatewayRequest req = new GatewayRequest(tenant, "flaky", "v2", tag);
        log.info("[circuit] input: tenantId={}, route={}, tag={}", tenant, req.route(), tag);
        GatewayException ex = assertThrows(GatewayException.class, () -> gatewayService.dispatch(req));
        log.info("[circuit] decision: error={}, retryable={}, detail={}",
                ex.getError(), ex.isRetryable(), ex.getDetail());
        assertEquals(expected, ex.getError());
        return ex;
    }

    @Test
    void consecutiveTimeoutsOpenCircuitWithDistinctReasonAndRetryHint() {
        int callsBefore = backendCalls.get();
        for (int i = 0; i < THRESHOLD; i++) {
            expectError(TENANT, ApiError.BACKEND_TIMEOUT, "timeout-" + i);
        }
        int callsAfterThreshold = backendCalls.get();
        assertEquals(callsBefore + THRESHOLD, callsAfterThreshold, "阈值内的超时都应真正打到后端");

        GatewayException open = expectError(TENANT, ApiError.CIRCUIT_OPEN, "fast-fail");
        assertTrue(open.isRetryable(), "快速失败必须可重试");
        assertNotEquals(ApiError.BACKEND_TIMEOUT, open.getError(), "快速失败原因必须与超时可区分");
        Object retryAfter = open.getDetail().get("retryAfterMillis");
        assertNotNull(retryAfter, "必须提示多久之后可以再试");
        long retryAfterMs = ((Number) retryAfter).longValue();
        log.info("[circuit] decision: retryAfterMillis={}（应在 (0, {}] 区间内）", retryAfterMs, OPEN_MS);
        assertTrue(retryAfterMs > 0 && retryAfterMs <= OPEN_MS);

        // 保护期内持续挡流，不再打到已卡住的后端
        expectError(TENANT, ApiError.CIRCUIT_OPEN, "fast-fail-2");
        expectError(TENANT, ApiError.CIRCUIT_OPEN, "fast-fail-3");
        assertEquals(callsAfterThreshold, backendCalls.get(),
                "保护期内后端调用次数不得再增加");

        // 快速失败不消耗限流额度
        log.info("[circuit] decision: 保护期内 remainingCapacity={}（快速失败不得占用额度）",
                rateLimiter.remainingCapacity(TENANT));
        assertEquals(1000, rateLimiter.remainingCapacity(TENANT));
    }

    @Test
    void circuitRecoversAutomaticallyAfterOpenPeriod() throws InterruptedException {
        for (int i = 0; i < THRESHOLD; i++) {
            expectError(TENANT, ApiError.BACKEND_TIMEOUT, "timeout-" + i);
        }
        expectError(TENANT, ApiError.CIRCUIT_OPEN, "fast-fail");

        backendStuck.set(false); // 后端恢复
        Thread.sleep(OPEN_MS + 200L); // 等待保护期结束

        GatewayRequest req = new GatewayRequest(TENANT, "flaky", "v2", "after-recovery");
        log.info("[circuit] input: 保护期 {}ms 已过后重试, backendStuck={}", OPEN_MS, backendStuck.get());
        GatewayResponse resp = gatewayService.dispatch(req);
        log.info("[circuit] decision: 自动恢复后 success={}, data={}", resp.isSuccess(), resp.getData());
        assertTrue(resp.isSuccess(), "保护期结束后必须自动恢复放行");
        assertEquals("recovered", resp.getData());
    }

    @Test
    void circuitIsIsolatedPerTenant() {
        for (int i = 0; i < THRESHOLD; i++) {
            expectError("tenant-x", ApiError.BACKEND_TIMEOUT, "timeout-" + i);
        }
        expectError("tenant-x", ApiError.CIRCUIT_OPEN, "fast-fail");

        backendStuck.set(false);
        GatewayRequest req = new GatewayRequest("tenant-y", "flaky", "v2", "from-y");
        log.info("[circuit] input: tenant-x 熔断中, tenant-y 正常请求");
        GatewayResponse resp = gatewayService.dispatch(req);
        log.info("[circuit] decision: tenant-y success={}（熔断维度必须按租户隔离）", resp.isSuccess());
        assertTrue(resp.isSuccess());
    }
}
