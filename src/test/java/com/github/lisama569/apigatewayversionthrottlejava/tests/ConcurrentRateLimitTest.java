package com.github.lisama569.apigatewayversionthrottlejava.tests;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle.RateLimitDecision;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle.TenantRateLimiter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发限流：N 个容量的桶在 M(M>N) 个并发请求下，
 * 放行数必须恰好为 N（不超发、不漏限），超限请求带可重试提示。
 */
class ConcurrentRateLimitTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentRateLimitTest.class);

    @Test
    void concurrentRequestsNeverExceedCapacity() throws Exception {
        int capacity = 20;
        int threads = 200;
        // 补充速率极低，保证测试窗口内不会因补充而多放行
        TenantRateLimiter limiter = new TenantRateLimiter(capacity, 0.0001);
        String tenant = "tenant-concurrent";

        log.info("[test-input] tenant={} capacity={} concurrentRequests={}", tenant, capacity, threads);

        // 每个任务一个虚拟线程，保证全部任务都能先就绪再同时放行
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    RateLimitDecision d = limiter.tryAcquire(tenant);
                    if (d.allowed()) {
                        allowed.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                        assertTrue(d.retryAfterMillis() > 0, "rejected request must carry retry hint");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        log.info("[test-basis] allowed={} rejected={} expectedAllowed={}", allowed.get(), rejected.get(), capacity);
        assertEquals(capacity, allowed.get(), "allowed count must equal bucket capacity exactly");
        assertEquals(threads - capacity, rejected.get(), "the rest must be rejected");
    }

    @Test
    void tenantsAreIsolated() {
        TenantRateLimiter limiter = new TenantRateLimiter(5, 0.0001);
        log.info("[test-input] two tenants, capacity=5 each");
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("tenant-a").allowed());
        }
        // tenant-a 已耗尽，tenant-b 不受影响
        assertTrue(limiter.tryAcquire("tenant-b").allowed(), "tenant-b must not be affected by tenant-a");
        log.info("[test-basis] tenant-a exhausted while tenant-b still allowed -> isolation holds");
    }
}
