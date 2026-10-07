package com.github.lisama569.apigatewayversionthrottlejava.tests.gateway;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.ThrottleProperties;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle.TenantRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 限流器额度账单测：
 * 跨窗口结束的请求归还额度不得影响新窗口；每份租约至多归还一次（并发重复归还也只生效一次）；
 * 计数不会减成负数。
 */
class TenantRateLimiterWindowLeaseTests {

    private static final Logger log = LoggerFactory.getLogger(TenantRateLimiterWindowLeaseTests.class);

    private static final String TENANT = "tenant-lease";
    private static final int LIMIT = 3;
    private static final long WINDOW_MS = 150L;

    private TenantRateLimiter limiter;

    @BeforeEach
    void setUp() {
        ThrottleProperties props = new ThrottleProperties();
        props.setDefaultLimit(LIMIT);
        props.setWindowMillis(WINDOW_MS);
        props.setTenantLimits(java.util.Map.of());
        this.limiter = new TenantRateLimiter(props);
    }

    @Test
    void releaseIsIdempotentWithinSameWindow() {
        TenantRateLimiter.Lease a = limiter.tryAcquire(TENANT);
        TenantRateLimiter.Lease b = limiter.tryAcquire(TENANT);
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(LIMIT - 2, limiter.remainingCapacity(TENANT));
        log.info("[lease] input: 同窗口占用 2 份额度, remaining={}", LIMIT - 2);

        assertTrue(a.release(), "首次归还必须生效");
        boolean second = a.release();
        boolean third = a.release();
        log.info("[lease] decision: 同一租约第 2/3 次归还返回 false/{}（不得重复减计数）, remaining={}",
                third, limiter.remainingCapacity(TENANT));
        assertFalse(second, "重复归还必须被忽略");
        assertFalse(third, "重复归还必须被忽略");
        assertEquals(LIMIT - 1, limiter.remainingCapacity(TENANT), "重复归还不得多减");

        assertTrue(b.release());
        assertEquals(LIMIT, limiter.remainingCapacity(TENANT));
        assertEquals(0, limiter.inFlight(TENANT), "计数不得被重复归还减成负数");
    }

    @Test
    void staleReleaseAfterRolloverDoesNotTouchNewWindow() throws InterruptedException {
        List<TenantRateLimiter.Lease> oldLeases = new ArrayList<>();
        for (int i = 0; i < LIMIT; i++) {
            oldLeases.add(limiter.tryAcquire(TENANT));
        }
        assertNull(limiter.tryAcquire(TENANT), "旧窗口应已放满");
        log.info("[lease] input: 旧窗口 {} 份额度全部占用, 等待窗口滚动 {}ms", LIMIT, WINDOW_MS);

        Thread.sleep(WINDOW_MS + 80L);
        assertEquals(LIMIT, limiter.remainingCapacity(TENANT), "新窗口应恢复满额");

        // 新窗口先放 LIMIT 份并占满
        List<TenantRateLimiter.Lease> newLeases = new ArrayList<>();
        for (int i = 0; i < LIMIT; i++) {
            newLeases.add(limiter.tryAcquire(TENANT));
        }
        assertNull(limiter.tryAcquire(TENANT), "新窗口同样不得超发");
        log.info("[lease] input: 新窗口也已占用 {} 份, 此时旧窗口的请求才超时结束并归还", LIMIT);

        for (TenantRateLimiter.Lease old : oldLeases) {
            assertFalse(old.release(), "跨窗口归还必须作废，不能计入新窗口");
        }
        log.info("[lease] decision: 旧窗口 {} 份租约跨窗口归还后, 新窗口 remaining={}（必须仍为 0）",
                LIMIT, limiter.remainingCapacity(TENANT));
        assertEquals(0, limiter.remainingCapacity(TENANT),
                "跨窗口归还绝不能抬高新窗口放行量");
        assertNull(limiter.tryAcquire(TENANT), "新窗口实际放行量不得超过租户上限");

        for (TenantRateLimiter.Lease now : newLeases) {
            assertTrue(now.release());
        }
        assertEquals(LIMIT, limiter.remainingCapacity(TENANT));
    }

    @Test
    void concurrentDuplicateReleasesCountOnlyOnce() throws InterruptedException {
        int leases = LIMIT;
        int releasersPerLease = 4;
        List<TenantRateLimiter.Lease> held = new ArrayList<>();
        for (int i = 0; i < leases; i++) {
            held.add(limiter.tryAcquire(TENANT));
        }
        int threads = leases * releasersPerLease;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        log.info("[lease] input: {} 份租约, 每份被 {} 个线程同时归还（乱序/重复）", leases, releasersPerLease);

        for (TenantRateLimiter.Lease lease : held) {
            for (int r = 0; r < releasersPerLease; r++) {
                pool.submit(() -> {
                    start.await();
                    lease.release();
                    return null;
                });
            }
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        log.info("[lease] decision: 并发重复归还结束后 remaining={}, inFlight={}（必须恢复满额且不为负）",
                limiter.remainingCapacity(TENANT), limiter.inFlight(TENANT));
        assertEquals(LIMIT, limiter.remainingCapacity(TENANT), "每份租约至多归还一次");
        assertEquals(0, limiter.inFlight(TENANT), "计数不得减成负数或错乱");
    }
}
