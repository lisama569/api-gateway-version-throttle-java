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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 半开试探：保护期结束后只放极少量请求试探；后端仍卡住则立即重新进入保护期，
 * 不再把成批流量压过去；后端真正恢复后照常放行。
 */
class HalfOpenRecoveryTests {

    private static final Logger log = LoggerFactory.getLogger(HalfOpenRecoveryTests.class);

    private static final String TENANT = "tenant-half-open";
    private static final int THRESHOLD = 2;
    private static final long OPEN_MS = 400L;
    private static final long TIMEOUT_MS = 150L;

    private GatewayService gatewayService;
    private final AtomicBoolean backendStuck = new AtomicBoolean(true);
    private final AtomicInteger backendCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        HandlerRegistry registry = new HandlerRegistry();
        // 可切换的后端：卡住时不抛异常、永不返回；恢复后立即成功
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

        TimeoutProperties timeout = new TimeoutProperties();
        timeout.setDefaultMillis(TIMEOUT_MS);
        timeout.setRouteOverrides(Map.of());

        CircuitBreakerProperties circuit = new CircuitBreakerProperties();
        circuit.setFailureThreshold(THRESHOLD);
        circuit.setOpenMillis(OPEN_MS);
        circuit.setHalfOpenProbeCount(1);

        this.gatewayService = new GatewayService(
                new VersionRouter(registry), new TenantRateLimiter(throttle), new ErrorNormalizer(),
                new BackendInvoker(),
                new TenantCircuitBreaker(circuit),
                timeout);
    }

    private GatewayException expectError(ApiError expected, String tag) {
        GatewayRequest req = new GatewayRequest(TENANT, "flaky", "v2", tag);
        log.info("[half-open] input: tenantId={}, route={}, tag={}", TENANT, req.route(), tag);
        GatewayException ex = assertThrows(GatewayException.class, () -> gatewayService.dispatch(req));
        log.info("[half-open] decision: error={}, retryable={}, backendCalls={}",
                ex.getError(), ex.isRetryable(), backendCalls.get());
        assertEquals(expected, ex.getError());
        return ex;
    }

    @Test
    void probeTimeoutReopensCircuitImmediately() throws Exception {
        // 连续超时达到阈值，进入保护期
        for (int i = 0; i < THRESHOLD; i++) {
            expectError(ApiError.BACKEND_TIMEOUT, "timeout-" + i);
        }
        expectError(ApiError.CIRCUIT_OPEN, "fast-fail");
        int callsWhenOpen = backendCalls.get();
        assertEquals(THRESHOLD, callsWhenOpen, "阈值内的超时都应真正打到后端");

        // 保护期结束，但后端仍卡住
        Thread.sleep(OPEN_MS + 150L);
        log.info("[half-open] input: 保护期 {}ms 已过, backendStuck={}（后端未恢复）", OPEN_MS, backendStuck.get());

        // 只放行一个试探请求（异步，它会在后端卡住约 TIMEOUT_MS 后超时）
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<GatewayException> probeError = new AtomicReference<>();
        pool.submit(() -> {
            try {
                gatewayService.dispatch(new GatewayRequest(TENANT, "flaky", "v2", "probe"));
            } catch (GatewayException ex) {
                probeError.set(ex);
            }
            return null;
        });
        // 等试探请求真正打到后端
        awaitTrue(() -> backendCalls.get() == callsWhenOpen + 1, 5_000);
        log.info("[half-open] decision: 试探请求已打到后端 backendCalls={}（只放行了 1 个试探）",
                backendCalls.get());

        // 试探仍在进行时，其余请求继续快速失败，不得打到后端
        expectError(ApiError.CIRCUIT_OPEN, "during-probe-1");
        expectError(ApiError.CIRCUIT_OPEN, "during-probe-2");
        assertEquals(callsWhenOpen + 1, backendCalls.get(),
                "试探期间不得再有流量打到后端");

        // 试探超时结束：立即重新进入保护期，无需重新攒够阈值
        awaitTrue(() -> probeError.get() != null, TIMEOUT_MS + 5_000L);
        assertEquals(ApiError.BACKEND_TIMEOUT, probeError.get().getError(), "试探请求应以后端超时结束");
        log.info("[half-open] decision: 试探超时, 熔断器应立即重新进入保护期");

        expectError(ApiError.CIRCUIT_OPEN, "reopened-1");
        expectError(ApiError.CIRCUIT_OPEN, "reopened-2");
        expectError(ApiError.CIRCUIT_OPEN, "reopened-3");
        assertEquals(callsWhenOpen + 1, backendCalls.get(),
                "重新进入保护期后不得再有流量打到后端（无需重新攒阈值）");

        // 后端真正恢复后，下一轮试探成功，照常放行
        backendStuck.set(false);
        Thread.sleep(OPEN_MS + 150L);
        GatewayRequest req = new GatewayRequest(TENANT, "flaky", "v2", "after-real-recovery");
        log.info("[half-open] input: 第二个保护期已过, backendStuck={}", backendStuck.get());
        GatewayResponse resp = gatewayService.dispatch(req);
        log.info("[half-open] decision: 恢复后 success={}, data={}", resp.isSuccess(), resp.getData());
        assertTrue(resp.isSuccess(), "后端真正恢复后试探成功，必须恢复放行");
        assertEquals("recovered", resp.getData());

        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
    }

    @Test
    void probeSuccessClosesCircuitAndFlowsNormally() throws Exception {
        for (int i = 0; i < THRESHOLD; i++) {
            expectError(ApiError.BACKEND_TIMEOUT, "timeout-" + i);
        }
        expectError(ApiError.CIRCUIT_OPEN, "fast-fail");

        backendStuck.set(false); // 后端在保护期内恢复
        Thread.sleep(OPEN_MS + 150L);
        log.info("[half-open] input: 保护期 {}ms 已过, backendStuck={}", OPEN_MS, backendStuck.get());

        // 试探成功 -> 真正闭合
        GatewayResponse probe = gatewayService.dispatch(new GatewayRequest(TENANT, "flaky", "v2", "probe"));
        log.info("[half-open] decision: 试探 success={}, data={}", probe.isSuccess(), probe.getData());
        assertTrue(probe.isSuccess());
        assertEquals("recovered", probe.getData());

        // 闭合后成批请求照常放行，不再受试探名额限制
        int callsBefore = backendCalls.get();
        for (int i = 0; i < 5; i++) {
            GatewayResponse resp = gatewayService.dispatch(
                    new GatewayRequest(TENANT, "flaky", "v2", "after-close-" + i));
            assertTrue(resp.isSuccess(), "闭合后该放行的请求必须照常放行");
        }
        log.info("[half-open] decision: 闭合后连续放行 5 个请求, backendCalls {} -> {}",
                callsBefore, backendCalls.get());
        assertEquals(callsBefore + 5, backendCalls.get(), "闭合后请求必须真正到达后端");
    }

    private static void awaitTrue(Check check, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (check.pass()) {
                return;
            }
            Thread.sleep(10L);
        }
        fail("condition not met within " + timeoutMillis + "ms");
    }

    @FunctionalInterface
    private interface Check {
        boolean pass();
    }
}
