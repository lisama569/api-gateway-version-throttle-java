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
import com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle.TenantRateLimiter;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.route.VersionRouter;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨窗口额度账：在窗口末尾才超时结束的请求，归还的额度不得算到新窗口头上；
 * 任何一段窗口内实际放行量都不得超过租户上限。
 */
class CrossWindowQuotaTests {

    private static final Logger log = LoggerFactory.getLogger(CrossWindowQuotaTests.class);

    private static final String TENANT = "tenant-cross-window";
    private static final int LIMIT = 3;
    private static final long WINDOW_MS = 800L;
    private static final long TIMEOUT_MS = 1500L; // 比窗口长，保证超时结束落在下一个窗口

    private GatewayService gatewayService;
    private TenantRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        HandlerRegistry registry = new HandlerRegistry();
        registry.register("ok", ApiVersion.V2, false, req -> "ok");
        // 真实卡住的后端：不抛异常、永不返回，由网关按上限超时
        registry.register("stuck", ApiVersion.V2, false, req -> {
            new CountDownLatch(1).await();
            return "never";
        });

        ThrottleProperties throttle = new ThrottleProperties();
        throttle.setDefaultLimit(LIMIT);
        throttle.setWindowMillis(WINDOW_MS);
        throttle.setTenantLimits(Map.of());
        this.rateLimiter = new TenantRateLimiter(throttle);

        TimeoutProperties timeout = new TimeoutProperties();
        timeout.setDefaultMillis(TIMEOUT_MS);
        timeout.setRouteOverrides(Map.of());

        // 本组用例专注额度账，熔断阈值抬高避免干扰
        CircuitBreakerProperties circuit = new CircuitBreakerProperties();
        circuit.setFailureThreshold(10_000);

        this.gatewayService = new GatewayService(
                new VersionRouter(registry), rateLimiter, new ErrorNormalizer(),
                new BackendInvoker(),
                new TenantCircuitBreaker(circuit),
                timeout);
    }

    @Test
    void releaseAfterWindowRolloverDoesNotInflateNextWindow() throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        AtomicInteger timedOut = new AtomicInteger();

        log.info("[cross-window] input: tenantId={}, limit={}, window={}ms, timeout={}ms（超时结束必落在下一窗口）",
                TENANT, LIMIT, WINDOW_MS, TIMEOUT_MS);

        // 窗口1：用卡住请求占满全部额度，它们会在窗口2才超时结束
        for (int i = 0; i < LIMIT; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    gatewayService.dispatch(new GatewayRequest(TENANT, "stuck", "v2", "stuck-" + idx));
                } catch (GatewayException ex) {
                    if (ex.getError() == ApiError.BACKEND_TIMEOUT) {
                        timedOut.incrementAndGet();
                    }
                }
                return null;
            });
        }
        // 等窗口1的额度都被占用
        awaitTrue(() -> rateLimiter.remainingCapacity(TENANT) == 0, 5_000);
        log.info("[cross-window] decision: 窗口1额度已占满 remainingCapacity=0");

        // 睡到窗口2（此时卡住请求仍在后端挂着，尚未超时）
        Thread.sleep(WINDOW_MS + 200L);
        log.info("[cross-window] decision: 进入窗口2, remainingCapacity={}（新窗口应恢复满额）",
                rateLimiter.remainingCapacity(TENANT));
        assertEquals(LIMIT, rateLimiter.remainingCapacity(TENANT), "新窗口额度应恢复满额");

        // 窗口2：正常请求占满本窗口额度
        for (int i = 0; i < LIMIT; i++) {
            gatewayService.dispatch(new GatewayRequest(TENANT, "ok", "v2", "ok-" + i));
        }
        log.info("[cross-window] decision: 窗口2已放行 {} 个正常请求, remainingCapacity=0", LIMIT);

        // 等窗口1的卡住请求在窗口2内超时结束、触发归还
        awaitTrue(() -> timedOut.get() == LIMIT, TIMEOUT_MS + 5_000L);
        Thread.sleep(100L); // 归还路径已全部走完

        int remaining = rateLimiter.remainingCapacity(TENANT);
        log.info("[cross-window] decision: 窗口1的 {} 个超时请求在窗口2归还后, remainingCapacity={}"
                        + "（判定依据=跨窗口归还必须被忽略, 不得抬高窗口2的放行量）",
                timedOut.get(), remaining);
        assertEquals(0, remaining, "跨窗口归还不得扣减新窗口计数");

        // 窗口2内不得再放进任何请求：实际放行量不能超过上限
        GatewayException overflow = assertThrows(GatewayException.class,
                () -> gatewayService.dispatch(new GatewayRequest(TENANT, "ok", "v2", "overflow")));
        log.info("[cross-window] decision: 窗口2第 {} 个请求被限流 error={}（窗口2实际放行量={}，未超上限）",
                LIMIT + 1, overflow.getError(), LIMIT);
        assertEquals(ApiError.RATE_LIMITED, overflow.getError());

        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
    }

    @Test
    void mixedSuccessAndTimeoutAcrossWindowBoundary() throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        int okCount = 2;
        int stuckCount = LIMIT - okCount;
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger timedOut = new AtomicInteger();

        log.info("[cross-window] input: tenantId={}, limit={}, 窗口1混入 正常{} + 卡住{}（乱序结束, 超时落在窗口2）",
                TENANT, LIMIT, okCount, stuckCount);

        // 窗口1：正常与卡住混杂并发，结束顺序天然乱序（正常先回、卡住跨窗口超时）
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < LIMIT; i++) {
            final int idx = i;
            String route = idx < okCount ? "ok" : "stuck";
            pool.submit(() -> {
                start.await();
                try {
                    gatewayService.dispatch(new GatewayRequest(TENANT, route, "v2", "req-" + idx));
                    succeeded.incrementAndGet();
                } catch (GatewayException ex) {
                    if (ex.getError() == ApiError.BACKEND_TIMEOUT) {
                        timedOut.incrementAndGet();
                    }
                }
                return null;
            });
        }
        start.countDown();

        // 正常请求先结束：成功请求占用额度，卡住请求的额度仍被持有，窗口1剩余为 0
        awaitTrue(() -> succeeded.get() == okCount, 5_000);
        int remainingInWindow1 = rateLimiter.remainingCapacity(TENANT);
        log.info("[cross-window] decision: 窗口1内正常请求结束后 remainingCapacity={}"
                        + "（判定依据=成功{}个占用额度 + 卡住{}个额度仍持有, 剩余=0）",
                remainingInWindow1, okCount, stuckCount);
        assertEquals(0, remainingInWindow1, "成功请求与在途卡住请求的额度都必须记账");

        // 卡住请求跨窗口超时结束
        awaitTrue(() -> timedOut.get() == stuckCount, TIMEOUT_MS + 5_000L);
        Thread.sleep(100L);
        log.info("[cross-window] decision: {} 个卡住请求已在窗口2超时归还, 当前 remainingCapacity={}",
                timedOut.get(), rateLimiter.remainingCapacity(TENANT));

        // 窗口2：旧窗口的归还不影响新窗口，新窗口可完整放行上限个请求、且仅上限个
        AtomicInteger admittedInWindow2 = new AtomicInteger();
        for (int i = 0; i < LIMIT + 2; i++) {
            try {
                gatewayService.dispatch(new GatewayRequest(TENANT, "ok", "v2", "w2-" + i));
                admittedInWindow2.incrementAndGet();
            } catch (GatewayException ex) {
                assertEquals(ApiError.RATE_LIMITED, ex.getError());
            }
        }
        log.info("[cross-window] decision: 窗口2实际放行={}（判定依据=恰好等于上限{}, 既不被旧归还抬高也不被误限流）",
                admittedInWindow2.get(), LIMIT);
        assertEquals(LIMIT, admittedInWindow2.get(), "窗口2放行量必须恰好等于上限");
        assertEquals(0, rateLimiter.remainingCapacity(TENANT));

        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
    }

    private static void awaitTrue(Check check, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (check.pass()) {
                return;
            }
            Thread.sleep(20L);
        }
        fail("condition not met within " + timeoutMillis + "ms");
    }

    @FunctionalInterface
    private interface Check {
        boolean pass();
    }
}
