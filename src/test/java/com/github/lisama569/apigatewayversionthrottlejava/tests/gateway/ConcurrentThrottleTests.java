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
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.GatewayResponse;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 场景二：并发下租户限流不超发、不漏限，失败归还额度，租户间隔离。 */
class ConcurrentThrottleTests {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentThrottleTests.class);

    private static final String TENANT = "tenant-burst";
    private static final int LIMIT = 5;
    private static final int TOTAL = 50;

    private GatewayService gatewayService;
    private TenantRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        HandlerRegistry registry = new HandlerRegistry();
        // 慢后端，确保请求真正并发重叠
        registry.register("slow", ApiVersion.V2, false, req -> {
            Thread.sleep(30);
            return "ok";
        });
        registry.register("fail", ApiVersion.V2, false, req -> {
            throw new BusinessRejectionException("order state invalid");
        });

        ThrottleProperties props = new ThrottleProperties();
        props.setDefaultLimit(LIMIT);
        props.setWindowMillis(60_000L);
        props.setTenantLimits(Map.of("tenant-premium", 50));
        this.rateLimiter = new TenantRateLimiter(props);

        this.gatewayService = new GatewayService(
                new VersionRouter(registry), rateLimiter, new ErrorNormalizer(),
                new BackendInvoker(),
                new TenantCircuitBreaker(new CircuitBreakerProperties()),
                new TimeoutProperties());
    }

    @Test
    void exactlyLimitAdmittedUnderConcurrency() throws InterruptedException {
        int threads = TOTAL;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        Map<Integer, ApiError> errors = new ConcurrentHashMap<>();

        log.info("[throttle] input: tenantId={}, limit={}/window, concurrentRequests={}",
                TENANT, LIMIT, threads);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                start.await();
                GatewayRequest req = new GatewayRequest(TENANT, "slow", "v2", "req-" + idx);
                try {
                    GatewayResponse resp = gatewayService.dispatch(req);
                    if (resp.isSuccess()) {
                        admitted.incrementAndGet();
                    }
                } catch (GatewayException ex) {
                    errors.put(idx, ex.getError());
                    if (ex.getError() == ApiError.RATE_LIMITED) {
                        limited.incrementAndGet();
                        assertTrue(ex.isRetryable(), "限流拒绝必须带可重试提示");
                    }
                }
                return null;
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("[throttle] decision: admitted={}, limited={}, remainingCapacity={}, 判定依据=固定窗口内计数与放行量{}比较",
                admitted.get(), limited.get(), rateLimiter.remainingCapacity(TENANT), LIMIT);

        assertEquals(LIMIT, admitted.get(), "并发下不得超发");
        assertEquals(threads - LIMIT, limited.get(), "并发下不得漏限");
        assertEquals(0, rateLimiter.remainingCapacity(TENANT));
        assertTrue(errors.values().stream().allMatch(e -> e == ApiError.RATE_LIMITED),
                "所有失败原因必须都可区分为 RATE_LIMITED");
    }

    @Test
    void failedBackendCallReleasesQuota() {
        log.info("[throttle] input: tenantId={}, limit={}, 先让 {} 个请求在业务层失败", TENANT, 1, 1);

        GatewayRequest failReq = new GatewayRequest(TENANT, "fail", "v2", "bad");
        GatewayException ex = assertThrows(GatewayException.class, () -> gatewayService.dispatch(failReq));
        assertEquals(ApiError.BUSINESS_REJECTED, ex.getError());

        log.info("[throttle] decision: 业务失败后 remainingCapacity={}（额度必须已归还）",
                rateLimiter.remainingCapacity(TENANT));
        assertEquals(LIMIT, rateLimiter.remainingCapacity(TENANT),
                "被业务拒绝的请求不得占用额度");

        AtomicInteger admitted = new AtomicInteger();
        for (int i = 0; i < LIMIT; i++) {
            gatewayService.dispatch(new GatewayRequest(TENANT, "slow", "v2", "retry-" + i));
            admitted.incrementAndGet();
        }
        GatewayException overflow = assertThrows(GatewayException.class,
                () -> gatewayService.dispatch(new GatewayRequest(TENANT, "slow", "v2", "overflow")));
        log.info("[throttle] decision: 额度归还后 {} 个请求全部放行，第 {} 个被限流 error={}",
                admitted.get(), LIMIT + 1, overflow.getError());
        assertEquals(LIMIT, admitted.get());
        assertEquals(ApiError.RATE_LIMITED, overflow.getError());
    }

    @Test
    void tenantsAreIsolated() {
        String a = "tenant-a";
        String b = "tenant-b";
        log.info("[throttle] input: {} 打满默认额度 {}，验证 {} 不受影响", a, LIMIT, b);
        for (int i = 0; i < LIMIT; i++) {
            assertTrue(gatewayService.dispatch(new GatewayRequest(a, "slow", "v2", i)).isSuccess());
        }
        GatewayException ex = assertThrows(GatewayException.class,
                () -> gatewayService.dispatch(new GatewayRequest(a, "slow", "v2", "overflow")));
        assertEquals(ApiError.RATE_LIMITED, ex.getError());

        GatewayResponse respB = gatewayService.dispatch(new GatewayRequest(b, "slow", "v2", "from-b"));
        log.info("[throttle] decision: {} 被限流时 {} 仍可放行 success={}", a, b, respB.isSuccess());
        assertTrue(respB.isSuccess(), "限流维度必须按租户隔离");
    }
}
