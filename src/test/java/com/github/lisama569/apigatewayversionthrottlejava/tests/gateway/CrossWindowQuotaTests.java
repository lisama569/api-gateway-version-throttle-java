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
import com.github.lisama569.apigatewayversionthrottlejava.gateway.route.VersionRouter;
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

/**
 * 场景七（跨窗口额度）：用「一直不返回的后端 + 短窗口」压测复现——
 * 先在窗口一占满额度并真正打到卡住后端的请求，会在窗口翻到下一段之后才超时结束。
 * 这些迟到归还绝不能计入新窗口；任何一段窗口内实际放行量都不得超过租户上限。
 */
class CrossWindowQuotaTests {

    private static final Logger log = LoggerFactory.getLogger(CrossWindowQuotaTests.class);

    private static final String TENANT = "tenant-cross-window";
    private static final int LIMIT = 3;
    private static final long WINDOW_MS = 200L;
    private static final long STUCK_TIMEOUT_MS = 350L; // > WINDOW_MS：超时必然落到下一段窗口
    private static final long OK_BACKEND_MS = 20L;

    private GatewayService gatewayService;
    private TenantRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        HandlerRegistry registry = new HandlerRegistry();
        registry.register("ok", ApiVersion.V2, false, req -> {
            Thread.sleep(OK_BACKEND_MS);
            return "ok";
        });
        // 真实卡住：不抛异常、永不返回，只能由网关超时结束
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
        timeout.setDefaultMillis(STUCK_TIMEOUT_MS);
        timeout.setRouteOverrides(Map.of());

        CircuitBreakerProperties circuit = new CircuitBreakerProperties();
        circuit.setFailureThreshold(10_000); // 本用例专注额度账，不被熔断干扰

        this.gatewayService = new GatewayService(
                new VersionRouter(registry), rateLimiter, new ErrorNormalizer(),
                new BackendInvoker(), new TenantCircuitBreaker(circuit), timeout);
    }

    /**
     * 窗口一：LIMIT 个卡住请求占满额度；
     * 窗口滚动后：新窗口只允许 LIMIT 个请求，旧请求恰在此时超时结束；
     * 旧窗口的迟到归还若错误计入新窗口，会把新窗口实际放行量推到 LIMIT 以上（本用例必须拦住）。
     */
    @Test
    void lateTimeoutsFromPreviousWindowDoNotRaiseNewWindowAdmissions() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        // 记录各请求获得放行（进入后端）时距离基准的毫秒数
        Map<Integer, Long> admittedAt = new ConcurrentHashMap<>();
        Map<Integer, Long> limitedAt = new ConcurrentHashMap<>();
        AtomicInteger stuckTimedOut = new AtomicInteger();
        AtomicInteger okSucceeded = new AtomicInteger();
        long t0 = System.currentTimeMillis();

        log.info("[cross-window] input: tenantId={}, limit={}, window={}ms, stuckTimeout={}ms（超时必跨窗口）",
                TENANT, LIMIT, WINDOW_MS, STUCK_TIMEOUT_MS);

        // 第一批：LIMIT 个卡住请求在窗口一占满额度
        for (int i = 0; i < LIMIT; i++) {
            final int idx = i;
            pool.submit(() -> {
                start.await();
                long admitted = System.currentTimeMillis() - t0;
                admittedAt.put(idx, admitted);
                log.info("[cross-window] request#{} admitted at +{}ms -> stuck", idx, admitted);
                try {
                    gatewayService.dispatch(new GatewayRequest(TENANT, "stuck", "v2", "stuck-" + idx));
                } catch (GatewayException ex) {
                    long ended = System.currentTimeMillis() - t0;
                    assertEquals(ApiError.BACKEND_TIMEOUT, ex.getError());
                    stuckTimedOut.incrementAndGet();
                    log.info("[cross-window] request#{} timeout ended at +{}ms（已跨窗口）, remaining={}",
                            idx, ended, rateLimiter.remainingCapacity(TENANT));
                }
                return null;
            });
        }

        start.countDown();

        // 等待窗口翻到第二段；此刻第一批请求仍挂在卡住的后端上
        Thread.sleep(WINDOW_MS + 50L);
        assertEquals(LIMIT, rateLimiter.remainingCapacity(TENANT),
                "窗口滚动后应是一段全新的满额窗口（旧窗口在途量不结转）");

        // 第二批：LIMIT 个正常请求在新窗口申请；它们的执行与第一批迟到超时交错
        CountDownLatch wave2Done = new CountDownLatch(LIMIT);
        for (int i = 0; i < LIMIT; i++) {
            final int idx = 100 + i;
            pool.submit(() -> {
                try {
                    long admitted = System.currentTimeMillis() - t0;
                    admittedAt.put(idx, admitted);
                    log.info("[cross-window] request#{} admitted at +{}ms -> ok", idx, admitted);
                    gatewayService.dispatch(new GatewayRequest(TENANT, "ok", "v2", "ok-" + idx));
                    okSucceeded.incrementAndGet();
                } catch (GatewayException ex) {
                    long limited = System.currentTimeMillis() - t0;
                    limitedAt.put(idx, limited);
                    log.info("[cross-window] request#{} limited at +{}ms error={}",
                            idx, limited, ex.getError());
                    assertEquals(ApiError.RATE_LIMITED, ex.getError());
                } finally {
                    wave2Done.countDown();
                }
                return null;
            });
        }
        assertTrue(wave2Done.await(10, TimeUnit.SECONDS));

        // 再等待第一批迟到超时全部结束
        long deadline = System.currentTimeMillis() + 5_000L;
        while (stuckTimedOut.get() < LIMIT && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        pool.shutdown();

        long firstAdmitted = admittedAt.values().stream().mapToLong(Long::longValue).min().orElseThrow();
        long boundary = firstAdmitted + WINDOW_MS;
        long admittedWindow1 = admittedAt.values().stream().filter(ts -> ts < boundary).count();
        long admittedWindow2 = admittedAt.values().stream().filter(ts -> ts >= boundary).count();

        log.info("[cross-window] decision: 窗口边界≈+{}ms, 窗口一放行={}, 窗口二放行={}, 窗口二限流拒绝={}, stuckTimeout={}, okSuccess={}",
                boundary, admittedWindow1, admittedWindow2, limitedAt.size(),
                stuckTimedOut.get(), okSucceeded.get());

        assertEquals(LIMIT, stuckTimedOut.get(), "卡住请求必须全部以超时结束");
        assertEquals(LIMIT, admittedWindow1, "窗口一放行量必须恰好等于上限");
        assertEquals(LIMIT, admittedWindow2,
                "新窗口放行量必须恰好等于上限：迟到归还既不能多放，也不能误伤本该放行的请求");
        assertTrue(limitedAt.isEmpty(),
                "新窗口内 LIMIT 个请求都应放行；出现限流说明迟到归还/计数把账算错");
        assertEquals(LIMIT, okSucceeded.get());

        // 旧请求的超时在新窗口结束后，新窗口剩余额度应为 0，绝不能被迟到归还抬高
        assertEquals(0, rateLimiter.remainingCapacity(TENANT),
                "跨窗口结束的请求归还额度不得影响新窗口");
    }

    /**
     * 单请求级复现：一个跨窗口才超时的请求，其归还不得让新窗口多放任何一份额度。
     */
    @Test
    void singleStaleLeaseCannotInflateNextWindow() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch timeoutReturned = new CountDownLatch(1);
        long t0 = System.currentTimeMillis();

        log.info("[cross-window] input: 单个卡住请求占满窗口一 1/{} 额度, timeout={}ms > window={}ms",
                LIMIT, STUCK_TIMEOUT_MS, WINDOW_MS);
        pool.submit(() -> {
            try {
                gatewayService.dispatch(new GatewayRequest(TENANT, "stuck", "v2", "stale-stuck"));
            } catch (GatewayException ex) {
                assertEquals(ApiError.BACKEND_TIMEOUT, ex.getError());
                timeoutReturned.countDown();
            }
            return null;
        });

        Thread.sleep(WINDOW_MS + 50L); // 进入窗口二，卡住请求仍未结束

        // 窗口二放满 LIMIT 份（正常请求）
        for (int i = 0; i < LIMIT; i++) {
            gatewayService.dispatch(new GatewayRequest(TENANT, "ok", "v2", "w2-ok-" + i));
        }
        GatewayException overflow = assertThrows(GatewayException.class,
                () -> gatewayService.dispatch(new GatewayRequest(TENANT, "ok", "v2", "w2-overflow")));
        log.info("[cross-window] decision: 窗口二放满 {} 份后第 {} 份被限流 error={}（旧请求此时仍挂起）",
                LIMIT, LIMIT + 1, overflow.getError());
        assertEquals(ApiError.RATE_LIMITED, overflow.getError());

        assertTrue(timeoutReturned.await(5, TimeUnit.SECONDS),
                "卡住请求必须在 " + STUCK_TIMEOUT_MS + "ms 左右超时结束（落在窗口二内）");
        long endedAt = System.currentTimeMillis() - t0;
        assertTrue(endedAt >= WINDOW_MS, "测试前提：该请求必须在窗口滚动后才结束, endedAt=" + endedAt);
        log.info("[cross-window] decision: 旧请求于 +{}ms（窗口二）超时归还后 remaining={}（必须仍为 0，不得多放）",
                endedAt, rateLimiter.remainingCapacity(TENANT));
        assertEquals(0, rateLimiter.remainingCapacity(TENANT),
                "跨窗口归还不得抬高窗口二的放行量");
        assertEquals(ApiError.RATE_LIMITED,
                assertThrows(GatewayException.class,
                        () -> gatewayService.dispatch(new GatewayRequest(TENANT, "ok", "v2", "w2-still-full")))
                        .getError());
        pool.shutdown();
    }
}
