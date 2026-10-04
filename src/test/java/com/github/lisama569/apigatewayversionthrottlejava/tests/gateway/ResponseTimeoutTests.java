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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 场景四：响应时间上限——后端真实卡住时按约定时限结束，路由可单独覆盖，并发卡住时每个请求都按时结束。 */
class ResponseTimeoutTests {

    private static final Logger log = LoggerFactory.getLogger(ResponseTimeoutTests.class);

    private static final long DEFAULT_TIMEOUT_MS = 300L;
    private static final long OVERRIDE_TIMEOUT_MS = 120L;

    private GatewayService gatewayService;
    private TimeoutProperties timeoutProperties;

    @BeforeEach
    void setUp() {
        HandlerRegistry registry = new HandlerRegistry();
        // 真实卡住的后端：不抛异常，永不返回（直到测试结束被中断）
        registry.register("stuck", ApiVersion.V2, false, req -> {
            new CountDownLatch(1).await();
            return "never";
        });
        registry.register("stuck-override", ApiVersion.V2, false, req -> {
            new CountDownLatch(1).await();
            return "never";
        });
        registry.register("fast", ApiVersion.V2, false, req -> "ok-" + req.payload());

        ThrottleProperties throttle = new ThrottleProperties();
        throttle.setDefaultLimit(1000);
        throttle.setWindowMillis(60_000L);
        throttle.setTenantLimits(Map.of());

        timeoutProperties = new TimeoutProperties();
        timeoutProperties.setDefaultMillis(DEFAULT_TIMEOUT_MS);
        timeoutProperties.setRouteOverrides(Map.of("stuck-override", OVERRIDE_TIMEOUT_MS));

        // 本组用例专注超时行为，熔断阈值抬高避免干扰
        CircuitBreakerProperties circuit = new CircuitBreakerProperties();
        circuit.setFailureThreshold(10_000);

        this.gatewayService = new GatewayService(
                new VersionRouter(registry), new TenantRateLimiter(throttle), new ErrorNormalizer(),
                new BackendInvoker(),
                new TenantCircuitBreaker(circuit),
                timeoutProperties);
    }

    @Test
    void stuckBackendEndsWithinGlobalDefaultTimeout() {
        GatewayRequest req = new GatewayRequest("t-timeout-1", "stuck", "v2", "payload");
        log.info("[timeout] input: tenantId={}, route={}, version={}, 全局默认上限={}ms",
                req.tenantId(), req.route(), req.version(), DEFAULT_TIMEOUT_MS);

        long start = System.nanoTime();
        GatewayException ex = assertThrows(GatewayException.class, () -> gatewayService.dispatch(req));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        log.info("[timeout] decision: error={}, retryable={}, elapsedMs={}（判定依据=全局默认上限{}ms）",
                ex.getError(), ex.isRetryable(), elapsedMs, DEFAULT_TIMEOUT_MS);
        assertEquals(ApiError.BACKEND_TIMEOUT, ex.getError(), "卡住的后端必须给出可区分的超时结果");
        assertTrue(ex.isRetryable());
        assertTrue(elapsedMs >= DEFAULT_TIMEOUT_MS, "不应早于约定时限结束: " + elapsedMs);
        assertTrue(elapsedMs < DEFAULT_TIMEOUT_MS * 4, "不得无限期挂起: " + elapsedMs);
    }

    @Test
    void routeOverrideTakesPrecedenceOverGlobalDefault() {
        GatewayRequest req = new GatewayRequest("t-timeout-2", "stuck-override", "v2", "payload");
        log.info("[timeout] input: route={}, 路由覆盖={}ms, 全局默认={}ms",
                req.route(), OVERRIDE_TIMEOUT_MS, DEFAULT_TIMEOUT_MS);

        long start = System.nanoTime();
        GatewayException ex = assertThrows(GatewayException.class, () -> gatewayService.dispatch(req));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        log.info("[timeout] decision: error={}, elapsedMs={}（判定依据=路由覆盖{}ms 优先于全局默认{}ms）",
                ex.getError(), elapsedMs, OVERRIDE_TIMEOUT_MS, DEFAULT_TIMEOUT_MS);
        assertEquals(ApiError.BACKEND_TIMEOUT, ex.getError());
        assertTrue(elapsedMs < DEFAULT_TIMEOUT_MS,
                "覆盖值 " + OVERRIDE_TIMEOUT_MS + "ms 应先生效，实际 " + elapsedMs + "ms");
    }

    @Test
    void fastBackendWithinDeadlineSucceeds() {
        GatewayRequest req = new GatewayRequest("t-timeout-3", "fast", "v2", "hello");
        log.info("[timeout] input: route={}, 后端在时限内返回", req.route());

        GatewayResponse resp = gatewayService.dispatch(req);

        log.info("[timeout] decision: success={}, data={}", resp.isSuccess(), resp.getData());
        assertTrue(resp.isSuccess());
        assertEquals("ok-hello", resp.getData());
    }

    @Test
    void manyConcurrentStuckRequestsEachEndOnTime() throws Exception {
        int total = 30;
        ExecutorService pool = Executors.newFixedThreadPool(total);
        CountDownLatch ready = new CountDownLatch(total);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger timedOut = new AtomicInteger();
        List<Future<Long>> futures = new ArrayList<>();

        log.info("[timeout] input: {} 个并发请求同时打到卡住的后端, 上限={}ms", total, DEFAULT_TIMEOUT_MS);

        for (int i = 0; i < total; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                long t0 = System.nanoTime();
                try {
                    gatewayService.dispatch(new GatewayRequest("t-burst", "stuck", "v2", "req-" + idx));
                    return -1L;
                } catch (GatewayException ex) {
                    assertEquals(ApiError.BACKEND_TIMEOUT, ex.getError());
                    timedOut.incrementAndGet();
                    return (System.nanoTime() - t0) / 1_000_000;
                }
            }));
        }
        ready.await();
        long wallStart = System.nanoTime();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long maxElapsed = 0;
        for (Future<Long> f : futures) {
            maxElapsed = Math.max(maxElapsed, f.get());
        }
        long wallMs = (System.nanoTime() - wallStart) / 1_000_000;
        log.info("[timeout] decision: {} 个请求全部按时结束, timedOut={}, 单请求最大耗时={}ms, 整体墙钟={}ms",
                total, timedOut.get(), maxElapsed, wallMs);

        assertEquals(total, timedOut.get(), "每个卡住的请求都必须以超时结束");
        assertTrue(maxElapsed < DEFAULT_TIMEOUT_MS * 4, "单个请求不得无限期挂起: " + maxElapsed);
        assertTrue(wallMs < DEFAULT_TIMEOUT_MS * 10, "并发卡住时整体也必须在有界时间内结束: " + wallMs);
    }
}
