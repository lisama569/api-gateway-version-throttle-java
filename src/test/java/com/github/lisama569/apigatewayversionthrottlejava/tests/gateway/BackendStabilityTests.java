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
import com.github.lisama569.apigatewayversionthrottlejava.gateway.resilience.BackendInvoker;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.resilience.CircuitBreakerProperties;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.resilience.RouteCircuitBreaker;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.resilience.TimeoutProperties;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.route.VersionRouter;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle.TenantRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 场景四：后端稳定性。
 * <ul>
 *   <li>响应时间上限：全局默认 + 单路由覆盖；后端真正卡住（挂起而非抛异常）时按时结束；</li>
 *   <li>额度账自洽：放行/超时混合批次下超时额度幂等归还，后续放行量与实际成功量对齐；</li>
 *   <li>快速失败：同租户同路由连续超时后进入短时 OPEN，直接给出可区分原因与再试提示，到期自动恢复。</li>
 * </ul>
 */
class BackendStabilityTests {

    private static final Logger log = LoggerFactory.getLogger(BackendStabilityTests.class);

    private final List<AutoCloseable> cleanups = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (AutoCloseable c : cleanups) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 关闭执行器时卡住的后端任务被中断属预期行为
            }
        }
        cleanups.clear();
    }

    /** 构造一个完全装配的网关（启用超时与熔断），执行器在用例结束后统一关闭。 */
    private GatewayService buildService(HandlerRegistry registry, TenantRateLimiter rateLimiter,
                                       TimeoutProperties timeoutProps,
                                       CircuitBreakerProperties cbProps) {
        BackendInvoker invoker = new BackendInvoker(timeoutProps);
        cleanups.add(invoker);
        return new GatewayService(
                new VersionRouter(registry), rateLimiter, new ErrorNormalizer(),
                invoker, new RouteCircuitBreaker(cbProps));
    }

    private ThrottleProperties throttleProps(int limit) {
        ThrottleProperties p = new ThrottleProperties();
        p.setDefaultLimit(limit);
        p.setWindowMillis(60_000L);
        p.setTenantLimits(Map.of());
        return p;
    }

    @Test
    void hangingBackendEndsWithinDefaultDeadline() {
        // 真正卡住的后端：latch 永远不被放开，handler 会一直 await（而不是直接抛异常）
        CountDownLatch neverRelease = new CountDownLatch(1);
        HandlerRegistry registry = new HandlerRegistry();
        registry.register("hang", ApiVersion.V2, false, req -> {
            neverRelease.await();
            return "should-never-return";
        });
        long deadline = 200L;
        TimeoutProperties timeoutProps = new TimeoutProperties();
        timeoutProps.setDefaultMillis(deadline);

        TenantRateLimiter rateLimiter = new TenantRateLimiter(throttleProps(10));
        GatewayService service = buildService(registry, rateLimiter, timeoutProps,
                new CircuitBreakerProperties());

        GatewayRequest req = new GatewayRequest("t-stab-1", "hang", "v2", "hanging-payload");
        log.info("[stability] input: tenantId={}, route={}, defaultTimeout={}ms, 后端行为=永久挂起",
                req.tenantId(), req.route(), deadline);

        long start = System.nanoTime();
        GatewayException ex = assertThrows(GatewayException.class, () -> service.dispatch(req));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        log.info("[stability] decision: error={}, elapsedMillis={}（必须在 {}ms 附近结束，不得无限挂起）, retryable={}",
                ex.getError(), elapsedMillis, deadline, ex.isRetryable());
        assertEquals(ApiError.BACKEND_TIMEOUT, ex.getError(), "卡住超时必须可区分");
        assertTrue(ex.isRetryable(), "超时结果必须提示可重试");
        assertTrue(elapsedMillis >= deadline - 50, "不应早于时限结束: " + elapsedMillis);
        assertTrue(elapsedMillis < deadline + 800, "不得明显超过时限: " + elapsedMillis);
        assertEquals(10, rateLimiter.remainingCapacity("t-stab-1"),
                "超时请求必须归还额度");
    }

    @Test
    void perRouteTimeoutOverridesDefault() {
        CountDownLatch neverRelease = new CountDownLatch(1);
        HandlerRegistry registry = new HandlerRegistry();
        registry.register("echo", ApiVersion.V2, false, req -> {
            neverRelease.await();
            return null;
        });
        registry.register("other", ApiVersion.V2, false, req -> {
            neverRelease.await();
            return null;
        });

        long defaultMillis = 600L;
        long echoOverride = 150L;
        TimeoutProperties timeoutProps = new TimeoutProperties();
        timeoutProps.setDefaultMillis(defaultMillis);
        timeoutProps.setRouteMillis(Map.of("echo", echoOverride));

        GatewayService service = buildService(registry, new TenantRateLimiter(throttleProps(10)),
                timeoutProps, new CircuitBreakerProperties());

        log.info("[stability] input: defaultTimeout={}ms, echo 路由覆盖={}ms, other 路由无覆盖",
                defaultMillis, echoOverride);

        long startEcho = System.nanoTime();
        GatewayException echoEx = assertThrows(GatewayException.class,
                () -> service.dispatch(new GatewayRequest("t-stab-2", "echo", "v2", null)));
        long echoElapsed = (System.nanoTime() - startEcho) / 1_000_000L;

        long startOther = System.nanoTime();
        GatewayException otherEx = assertThrows(GatewayException.class,
                () -> service.dispatch(new GatewayRequest("t-stab-2", "other", "v2", null)));
        long otherElapsed = (System.nanoTime() - startOther) / 1_000_000L;

        log.info("[stability] decision: echo elapsedMillis={}（应命中覆盖值 {}ms）, other elapsedMillis={}（应回落默认 {}ms）",
                echoElapsed, echoOverride, otherElapsed, defaultMillis);
        assertEquals(ApiError.BACKEND_TIMEOUT, echoEx.getError());
        assertEquals(ApiError.BACKEND_TIMEOUT, otherEx.getError());
        assertTrue(echoElapsed < echoOverride + 600, "echo 必须使用路由覆盖的更短时限: " + echoElapsed);
        assertTrue(otherElapsed >= defaultMillis - 50, "other 必须使用全局默认时限: " + otherElapsed);
    }

    @Test
    void concurrentHangingBackendsAllFinishAndQuotaFullyReturned() throws InterruptedException {
        int total = 24;
        int limit = 8;
        long deadline = 250L;
        String tenant = "t-stab-3";

        // 所有后端调用都真正卡住（latch 永不放开），而非直接抛异常
        CountDownLatch neverRelease = new CountDownLatch(1);
        HandlerRegistry registry = new HandlerRegistry();
        registry.register("hang", ApiVersion.V2, false, req -> {
            neverRelease.await();
            return null;
        });
        TimeoutProperties timeoutProps = new TimeoutProperties();
        timeoutProps.setDefaultMillis(deadline);
        // 把熔断阈值调到比本用例的超时次数更高，保证本用例只验证超时与额度，不受快速失败干扰
        CircuitBreakerProperties cbProps = new CircuitBreakerProperties();
        cbProps.setFailureThreshold(total + 10);
        TenantRateLimiter rateLimiter = new TenantRateLimiter(throttleProps(limit));
        GatewayService service = buildService(registry, rateLimiter, timeoutProps, cbProps);

        log.info("[stability] input: tenantId={}, limit={}/window, concurrentRequests={}, 全部后端永久挂起, timeout={}ms",
                tenant, limit, total, deadline);

        ExecutorService pool = Executors.newFixedThreadPool(total);
        CountDownLatch ready = new CountDownLatch(total);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger timeouts = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();
        List<Long> timeoutElapsed = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < total; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("test latch interrupted", ie);
                }
                long t0 = System.nanoTime();
                try {
                    service.dispatch(new GatewayRequest(tenant, "hang", "v2", "bulk"));
                } catch (GatewayException ex) {
                    if (ex.getError() == ApiError.BACKEND_TIMEOUT) {
                        timeoutElapsed.add((System.nanoTime() - t0) / 1_000_000L);
                        timeouts.incrementAndGet();
                    } else if (ex.getError() == ApiError.RATE_LIMITED) {
                        limited.incrementAndGet();
                    } else {
                        otherErrors.incrementAndGet();
                    }
                }
            });
        }
        ready.await();
        long wallStart = System.nanoTime();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "所有请求必须按时结束，不得有请求挂死");
        long wallElapsed = (System.nanoTime() - wallStart) / 1_000_000L;

        log.info("[stability] decision: timeouts={}, rateLimited={}, otherErrors={}, wallElapsed={}ms, remainingCapacity={}, 判定依据=放行{}个全部超时并各归还一份额度",
                timeouts.get(), limited.get(), otherErrors.get(), wallElapsed,
                rateLimiter.remainingCapacity(tenant), limit);

        assertEquals(limit, timeouts.get(), "恰好 limit 个请求被放行并全部超时");
        assertEquals(total - limit, limited.get(), "其余请求被限流（从未获得额度）");
        assertEquals(0, otherErrors.get(), "不应出现超时/限流以外的结果");
        assertEquals(limit, rateLimiter.remainingCapacity(tenant),
                "全部超时后额度必须一份不少地归还");
        assertTrue(wallElapsed < deadline + 1500,
                "并发下每个请求都应在自己的时限附近结束，墙钟时间不得串行叠加: " + wallElapsed);
        assertTrue(timeoutElapsed.stream().allMatch(e -> e >= deadline - 50 && e < deadline + 1500),
                "每个超时请求的实际耗时必须贴近约定时限: " + timeoutElapsed);
    }

    @Test
    void mixedSuccessAndTimeoutBatchKeepsQuotaAccountConsistent() {
        int limit = 5;
        String tenant = "t-stab-4";
        long deadline = 150L;

        CountDownLatch neverRelease = new CountDownLatch(1);
        HandlerRegistry registry = new HandlerRegistry();
        // 真正卡住的后端
        registry.register("hang", ApiVersion.V2, false, req -> {
            neverRelease.await();
            return null;
        });
        // 正常快速返回的后端
        registry.register("ok", ApiVersion.V2, false, req -> "done");

        TimeoutProperties timeoutProps = new TimeoutProperties();
        timeoutProps.setDefaultMillis(deadline);
        CircuitBreakerProperties cbProps = new CircuitBreakerProperties();
        cbProps.setFailureThreshold(100);
        TenantRateLimiter rateLimiter = new TenantRateLimiter(throttleProps(limit));
        GatewayService service = buildService(registry, rateLimiter, timeoutProps, cbProps);

        log.info("[stability] input: tenantId={}, limit={}, 批次=2 个正常放行 + 2 个卡住超时", tenant, limit);

        // 同租户窗口内混合一批：2 成功 + 2 超时
        assertTrue(service.dispatch(new GatewayRequest(tenant, "ok", "v2", "ok-1")).isSuccess());
        assertTrue(service.dispatch(new GatewayRequest(tenant, "ok", "v2", "ok-2")).isSuccess());
        for (int i = 1; i <= 2; i++) {
            final int idx = i;
            GatewayException ex = assertThrows(GatewayException.class,
                    () -> service.dispatch(new GatewayRequest(tenant, "hang", "v2", "hang-" + idx)));
            assertEquals(ApiError.BACKEND_TIMEOUT, ex.getError());
        }

        int remaining = rateLimiter.remainingCapacity(tenant);
        log.info("[stability] decision: 混合批次后 remainingCapacity={}（2 份成功占用 + 2 份超时已归还，应剩 3）", remaining);
        assertEquals(3, remaining, "超时额度必须恰好归还，后续放行量与实际成功量对齐");

        // 归还后的额度必须还能精确支撑 3 个成功请求，不多不少
        for (int i = 1; i <= 3; i++) {
            final int idx = i;
            GatewayResponse resp = service.dispatch(new GatewayRequest(tenant, "ok", "v2", "refill-" + idx));
            assertTrue(resp.isSuccess(), "归还额度后的第 " + idx + " 个请求应被放行");
        }
        GatewayException overflow = assertThrows(GatewayException.class,
                () -> service.dispatch(new GatewayRequest(tenant, "ok", "v2", "overflow")));
        log.info("[stability] decision: 再放 3 个成功后第 6 个请求判定 error={}（不得因多还额度而被放开）",
                overflow.getError());
        assertEquals(ApiError.RATE_LIMITED, overflow.getError(), "不得超过原本上限");
        assertEquals(0, rateLimiter.remainingCapacity(tenant));
    }

    @Test
    void duplicateReleaseNeverReturnsQuotaTwice() {
        TenantRateLimiter rateLimiter = new TenantRateLimiter(throttleProps(3));
        TenantRateLimiter.Permit p1 = rateLimiter.tryAcquire("t-stab-5");
        TenantRateLimiter.Permit p2 = rateLimiter.tryAcquire("t-stab-5");
        assertEquals(1, rateLimiter.remainingCapacity("t-stab-5"));

        rateLimiter.release(p1);
        rateLimiter.release(p1);
        rateLimiter.release(p1);
        rateLimiter.release(null);
        assertEquals(2, rateLimiter.remainingCapacity("t-stab-5"), "同一许可重复归还只能生效一次");

        rateLimiter.release(p2);
        assertEquals(3, rateLimiter.remainingCapacity("t-stab-5"));
        log.info("[stability] decision: permit 幂等归还验证通过，重复 release 未多还额度");
    }

    @Test
    void repeatedTimeoutsOpenCircuitThenAutoRecover() throws InterruptedException {
        int threshold = 3;
        long openMillis = 300L;
        long deadline = 80L;
        String tenant = "t-stab-6";

        AtomicInteger backendHits = new AtomicInteger();
        CountDownLatch neverRelease = new CountDownLatch(1);
        HandlerRegistry registry = new HandlerRegistry();
        registry.register("flaky", ApiVersion.V2, false, req -> {
            backendHits.incrementAndGet();
            // 后端前 100s 内一直卡住；恢复阶段通过替换路由行为来模拟后端恢复
            if ("recovered".equals(req.payload())) {
                return "back-to-normal";
            }
            neverRelease.await();
            return null;
        });

        TimeoutProperties timeoutProps = new TimeoutProperties();
        timeoutProps.setDefaultMillis(deadline);
        CircuitBreakerProperties cbProps = new CircuitBreakerProperties();
        cbProps.setFailureThreshold(threshold);
        cbProps.setOpenMillis(openMillis);
        TenantRateLimiter rateLimiter = new TenantRateLimiter(throttleProps(100));
        GatewayService service = buildService(registry, rateLimiter, timeoutProps, cbProps);

        log.info("[stability] input: tenantId={}, route=flaky, threshold={}, openMillis={}, 后端持续卡住",
                tenant, threshold, openMillis);

        // 连续超时达到阈值
        for (int i = 1; i <= threshold; i++) {
            final int idx = i;
            GatewayException ex = assertThrows(GatewayException.class,
                    () -> service.dispatch(new GatewayRequest(tenant, "flaky", "v2", "hang-" + idx)));
            assertEquals(ApiError.BACKEND_TIMEOUT, ex.getError());
        }

        // 阈值之后立即快速失败：必须可区分，且不再打向后端
        int hitsBeforeOpen = backendHits.get();
        GatewayException openEx = assertThrows(GatewayException.class,
                () -> service.dispatch(new GatewayRequest(tenant, "flaky", "v2", "during-open")));
        log.info("[stability] decision: 连续 {} 次超时后 error={}, detail={}",
                threshold, openEx.getError(), openEx.getDetail());
        assertEquals(ApiError.CIRCUIT_OPEN, openEx.getError(), "快速失败原因必须可区分");
        assertTrue(openEx.isRetryable(), "快速失败必须提示可重试");
        Object retryAfter = openEx.getDetail().get("retryAfterMillis");
        assertNotNull(retryAfter, "必须给出多久之后可再试的提示");
        assertTrue(((Number) retryAfter).longValue() > 0 && ((Number) retryAfter).longValue() <= openMillis,
                "retryAfterMillis 必须在保护期范围内");
        assertEquals(hitsBeforeOpen, backendHits.get(), "OPEN 期间请求不得再打到已卡住的后端");
        assertEquals(100, rateLimiter.remainingCapacity(tenant), "OPEN 期间快速失败不得占用额度");

        // 保护期结束后自动试探恢复
        Thread.sleep(openMillis + 150);
        GatewayResponse probe = service.dispatch(new GatewayRequest(tenant, "flaky", "v2", "recovered"));
        log.info("[stability] decision: 保护期结束后试探请求 success={}", probe.isSuccess());
        assertTrue(probe.isSuccess(), "试探请求成功后必须自动恢复");

        // 恢复后正常请求照常放行
        GatewayResponse after = service.dispatch(new GatewayRequest(tenant, "flaky", "v2", "recovered"));
        assertTrue(after.isSuccess(), "恢复后该放行的请求仍要照常放行");
    }

    @Test
    void circuitIsolatedByTenantAndRoute() {
        int threshold = 2;
        CountDownLatch neverRelease = new CountDownLatch(1);
        HandlerRegistry registry = new HandlerRegistry();
        registry.register("echo", ApiVersion.V2, false, req -> {
            if ("healthy".equals(req.payload())) {
                return "ok";
            }
            neverRelease.await();
            return null;
        });
        registry.register("other", ApiVersion.V2, false, req -> "other-ok");

        TimeoutProperties timeoutProps = new TimeoutProperties();
        timeoutProps.setDefaultMillis(80L);
        CircuitBreakerProperties cbProps = new CircuitBreakerProperties();
        cbProps.setFailureThreshold(threshold);
        cbProps.setOpenMillis(60_000L);
        TenantRateLimiter rateLimiter = new TenantRateLimiter(throttleProps(100));
        GatewayService service = buildService(registry, rateLimiter, timeoutProps, cbProps);

        String a = "t-stab-7a";
        String b = "t-stab-7b";
        log.info("[stability] input: {} 在 echo 连续超时 {} 次熔断，验证 other 路由与租户 {} 不受牵连", a, threshold, b);

        for (int i = 1; i <= threshold; i++) {
            final int idx = i;
            GatewayException ex = assertThrows(GatewayException.class,
                    () -> service.dispatch(new GatewayRequest(a, "echo", "v2", "hang-" + idx)));
            assertEquals(ApiError.BACKEND_TIMEOUT, ex.getError());
        }
        GatewayException openEx = assertThrows(GatewayException.class,
                () -> service.dispatch(new GatewayRequest(a, "echo", "v2", "x")));
        assertEquals(ApiError.CIRCUIT_OPEN, openEx.getError());

        // 同租户不同路由不受影响
        GatewayResponse otherRoute = service.dispatch(new GatewayRequest(a, "other", "v2", "x"));
        assertTrue(otherRoute.isSuccess(), "熔断必须按路由隔离");

        // 不同租户同路由不受影响（且仍可正常打到健康后端）
        GatewayResponse otherTenant = service.dispatch(new GatewayRequest(b, "echo", "v2", "healthy"));
        log.info("[stability] decision: {}+other success={}, {}+echo success={}（熔断器按 tenantId+route 隔离）",
                a, otherRoute.isSuccess(), b, otherTenant.isSuccess());
        assertTrue(otherTenant.isSuccess(), "熔断必须按租户隔离");
    }

    @Test
    void permitReleasedAfterWindowRollDoesNotCorruptNewWindow() throws InterruptedException {
        // 直接构造一个超短窗口的限流器，验证跨窗口归还不会算错新窗口的账
        ThrottleProperties props = new ThrottleProperties();
        props.setDefaultLimit(2);
        props.setWindowMillis(100L);
        TenantRateLimiter rateLimiter = new TenantRateLimiter(props);
        String tenant = "t-stab-9";

        TenantRateLimiter.Permit oldPermit = rateLimiter.tryAcquire(tenant);
        assertNotNull(oldPermit);
        assertEquals(1, rateLimiter.remainingCapacity(tenant));

        // 等待窗口滚动，旧窗口的许可此时才归还
        Thread.sleep(160L);
        rateLimiter.release(oldPermit);

        assertEquals(2, rateLimiter.remainingCapacity(tenant),
                "旧窗口的超时归还不得污染新窗口计数");
        log.info("[stability] decision: 窗口滚动后归还旧许可，新窗口额度仍为满额 2（未多还）");
    }

    @Test
    void halfOpenProbeTimeoutReopensCircuit() throws InterruptedException {
        int threshold = 2;
        long openMillis = 200L;
        String tenant = "t-stab-8";

        CountDownLatch neverRelease = new CountDownLatch(1);
        HandlerRegistry registry = new HandlerRegistry();
        registry.register("flaky", ApiVersion.V2, false, req -> {
            // 后端始终没恢复：任何请求都继续卡住
            neverRelease.await();
            return null;
        });

        TimeoutProperties timeoutProps = new TimeoutProperties();
        timeoutProps.setDefaultMillis(80L);
        CircuitBreakerProperties cbProps = new CircuitBreakerProperties();
        cbProps.setFailureThreshold(threshold);
        cbProps.setOpenMillis(openMillis);
        GatewayService service = buildService(registry, new TenantRateLimiter(throttleProps(100)),
                timeoutProps, cbProps);

        for (int i = 1; i <= threshold; i++) {
            final int idx = i;
            assertEquals(ApiError.BACKEND_TIMEOUT, assertThrows(GatewayException.class,
                    () -> service.dispatch(new GatewayRequest(tenant, "flaky", "v2", "h" + idx))).getError());
        }
        assertEquals(ApiError.CIRCUIT_OPEN, assertThrows(GatewayException.class,
                () -> service.dispatch(new GatewayRequest(tenant, "flaky", "v2", "open"))).getError());

        Thread.sleep(openMillis + 100);
        // 试探请求仍然超时：必须重新进入 OPEN，而不是放过流量
        GatewayException probeTimeout = assertThrows(GatewayException.class,
                () -> service.dispatch(new GatewayRequest(tenant, "flaky", "v2", "probe")));
        log.info("[stability] decision: HALF_OPEN 试探仍超时 -> {}", probeTimeout.getError());
        assertEquals(ApiError.BACKEND_TIMEOUT, probeTimeout.getError());

        GatewayException stillOpen = assertThrows(GatewayException.class,
                () -> service.dispatch(new GatewayRequest(tenant, "flaky", "v2", "after-probe")));
        assertEquals(ApiError.CIRCUIT_OPEN, stillOpen.getError(), "试探失败必须重新打开保护期");
    }
}
