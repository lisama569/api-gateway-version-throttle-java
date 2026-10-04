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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 场景五：超时与正常请求混杂的并发下，窗口内额度账必须自洽——该还的还、不多还、不漏还。 */
class TimeoutQuotaAccountingTests {

    private static final Logger log = LoggerFactory.getLogger(TimeoutQuotaAccountingTests.class);

    private static final String TENANT = "tenant-quota";
    private static final int LIMIT = 8;
    private static final long TIMEOUT_MS = 200L;

    private GatewayService gatewayService;
    private TenantRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        HandlerRegistry registry = new HandlerRegistry();
        // 正常后端：短暂驻留确保并发重叠后成功返回
        registry.register("ok", ApiVersion.V2, false, req -> {
            Thread.sleep(30);
            return "ok";
        });
        // 真实卡住的后端：永不返回，由网关按上限超时
        registry.register("stuck", ApiVersion.V2, false, req -> {
            new CountDownLatch(1).await();
            return "never";
        });

        ThrottleProperties throttle = new ThrottleProperties();
        throttle.setDefaultLimit(LIMIT);
        throttle.setWindowMillis(60_000L);
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
    void mixedSuccessAndTimeoutKeepQuotaExact() throws InterruptedException {
        int okCount = 4;
        int stuckCount = 4;
        int total = okCount + stuckCount;
        ExecutorService pool = Executors.newFixedThreadPool(total);
        CountDownLatch ready = new CountDownLatch(total);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger timedOut = new AtomicInteger();
        Map<Integer, ApiError> errors = new ConcurrentHashMap<>();

        log.info("[quota] input: tenantId={}, limit={}, 并发={} (正常{} + 卡住{}), 超时上限={}ms",
                TENANT, LIMIT, total, okCount, stuckCount, TIMEOUT_MS);

        for (int i = 0; i < total; i++) {
            final int idx = i;
            String route = idx % 2 == 0 ? "ok" : "stuck";
            pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    gatewayService.dispatch(new GatewayRequest(TENANT, route, "v2", "req-" + idx));
                    succeeded.incrementAndGet();
                } catch (GatewayException ex) {
                    errors.put(idx, ex.getError());
                    if (ex.getError() == ApiError.BACKEND_TIMEOUT) {
                        timedOut.incrementAndGet();
                    }
                }
                return null;
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        int remaining = rateLimiter.remainingCapacity(TENANT);
        log.info("[quota] decision: succeeded={}, timedOut={}, remainingCapacity={}（判定依据=额度只被成功请求占用, 超时额度必须恰好归还）",
                succeeded.get(), timedOut.get(), remaining);

        assertEquals(okCount, succeeded.get(), "正常请求应全部成功");
        assertEquals(stuckCount, timedOut.get(), "卡住请求应全部超时");
        assertEquals(LIMIT - okCount, remaining,
                "剩余额度必须等于 上限-成功数：超时额度不多还也不少还");

        // 后续放行量必须与实际剩余额度严丝合缝：还回多少就能再放多少，既不误限也不超发
        AtomicInteger admittedAfter = new AtomicInteger();
        for (int i = 0; i < LIMIT; i++) {
            try {
                gatewayService.dispatch(new GatewayRequest(TENANT, "ok", "v2", "after-" + i));
                admittedAfter.incrementAndGet();
            } catch (GatewayException ex) {
                assertEquals(ApiError.RATE_LIMITED, ex.getError());
            }
        }
        log.info("[quota] decision: 后续放行={}（应等于已归还的 {}），再往后被限流",
                admittedAfter.get(), stuckCount);
        assertEquals(stuckCount, admittedAfter.get(),
                "后续放行量必须与归还量一致：不得误限流，也不得突破上限");
        assertEquals(0, rateLimiter.remainingCapacity(TENANT));
    }

    @Test
    void allTimeoutBurstReturnsFullQuota() throws InterruptedException {
        int total = 20;
        ExecutorService pool = Executors.newFixedThreadPool(total);
        CountDownLatch ready = new CountDownLatch(total);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger timedOut = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();

        log.info("[quota] input: tenantId={}, limit={}, {} 个并发请求全部卡住, 超时上限={}ms",
                TENANT, LIMIT, total, TIMEOUT_MS);

        for (int i = 0; i < total; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    gatewayService.dispatch(new GatewayRequest(TENANT, "stuck", "v2", "req-" + idx));
                } catch (GatewayException ex) {
                    if (ex.getError() == ApiError.BACKEND_TIMEOUT) {
                        timedOut.incrementAndGet();
                    } else if (ex.getError() == ApiError.RATE_LIMITED) {
                        limited.incrementAndGet();
                    }
                }
                return null;
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        int remaining = rateLimiter.remainingCapacity(TENANT);
        log.info("[quota] decision: timedOut={}, limited={}, remainingCapacity={}（判定依据=超时全部归还后额度应恢复满额）",
                timedOut.get(), limited.get(), remaining);

        assertEquals(LIMIT, timedOut.get(), "卡住请求不得超发放行");
        assertEquals(total - LIMIT, limited.get(), "超出额度的请求必须被限流");
        assertEquals(LIMIT, remaining, "全部超时后额度必须完整归还，不得漏还");

        // 归还后窗口内仍可放行完整额度，不得因超时集中出现而误限流
        AtomicInteger admittedAfter = new AtomicInteger();
        for (int i = 0; i < LIMIT; i++) {
            gatewayService.dispatch(new GatewayRequest(TENANT, "ok", "v2", "after-" + i));
            admittedAfter.incrementAndGet();
        }
        GatewayException overflow = assertThrows(GatewayException.class,
                () -> gatewayService.dispatch(new GatewayRequest(TENANT, "ok", "v2", "overflow")));
        log.info("[quota] decision: 归还后放行={}，第 {} 个被限流 error={}",
                admittedAfter.get(), LIMIT + 1, overflow.getError());
        assertEquals(LIMIT, admittedAfter.get());
        assertEquals(ApiError.RATE_LIMITED, overflow.getError());
    }
}
