package com.github.lisama569.apigatewayversionthrottlejava.tests.gateway;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.ThrottleProperties;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle.TenantRateLimiter;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 额度归还纪律：申请到的额度最多归还一次——重复归还不生效、乱序归还不错乱、
 * 计数不会减成负数、跨窗口归还不减错窗口。
 */
class QuotaReleaseOnceTests {

    private static final Logger log = LoggerFactory.getLogger(QuotaReleaseOnceTests.class);

    private static final String TENANT = "tenant-release-once";
    private static final int LIMIT = 4;
    private static final long WINDOW_MS = 300L;

    private TenantRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        ThrottleProperties props = new ThrottleProperties();
        props.setDefaultLimit(LIMIT);
        props.setWindowMillis(WINDOW_MS);
        props.setTenantLimits(Map.of());
        this.rateLimiter = new TenantRateLimiter(props);
    }

    @Test
    void samePermitReleasedTwiceCountsOnlyOnce() {
        log.info("[release-once] input: tenantId={}, limit={}, 申请2份额度, 其中1份归还两次",
                TENANT, LIMIT);
        TenantRateLimiter.Permit p1 = rateLimiter.tryAcquire(TENANT);
        TenantRateLimiter.Permit p2 = rateLimiter.tryAcquire(TENANT);
        assertNotNull(p1);
        assertNotNull(p2);

        rateLimiter.release(p1);
        rateLimiter.release(p1); // 重复归还：必须被忽略
        rateLimiter.release(p1); // 第三次亦然

        int remaining = rateLimiter.remainingCapacity(TENANT);
        log.info("[release-once] decision: remainingCapacity={}（判定依据=同一凭证只生效一次, 剩余=上限{}-在持1）",
                remaining, LIMIT);
        assertEquals(LIMIT - 1, remaining, "同一凭证重复归还不得多还");

        rateLimiter.release(p2);
        assertEquals(LIMIT, rateLimiter.remainingCapacity(TENANT), "各凭证各还一次后额度应恢复满额");
    }

    @Test
    void outOfOrderReleasesNeverGoNegative() throws InterruptedException {
        int total = LIMIT * 3; // 12 个线程争抢 4 份额度
        ExecutorService pool = Executors.newFixedThreadPool(total);
        CountDownLatch ready = new CountDownLatch(total);
        CountDownLatch start = new CountDownLatch(1);
        List<TenantRateLimiter.Permit> permits = Collections.synchronizedList(new ArrayList<>());

        log.info("[release-once] input: tenantId={}, limit={}, {} 个线程并发申请后乱序归还",
                TENANT, LIMIT, total);
        for (int i = 0; i < total; i++) {
            pool.submit(() -> {
                ready.countDown();
                start.await();
                TenantRateLimiter.Permit permit = rateLimiter.tryAcquire(TENANT);
                if (permit != null) {
                    permits.add(permit);
                }
                return null;
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(LIMIT, permits.size(), "并发申请不得超发");

        // 乱序 + 并发归还，且每份都故意多还一次
        Collections.shuffle(permits);
        ExecutorService releasers = Executors.newFixedThreadPool(permits.size());
        for (TenantRateLimiter.Permit permit : permits) {
            releasers.submit(() -> {
                rateLimiter.release(permit);
                rateLimiter.release(permit); // 故意重复
                return null;
            });
        }
        releasers.shutdown();
        assertTrue(releasers.awaitTermination(30, TimeUnit.SECONDS));

        int remaining = rateLimiter.remainingCapacity(TENANT);
        log.info("[release-once] decision: 乱序并发归还后 remainingCapacity={}（判定依据=每份恰好还一次, 计数不得为负）",
                remaining);
        assertEquals(LIMIT, remaining, "乱序并发归还后额度必须恰好恢复满额，不多还、不计负");
    }

    @Test
    void releaseFromExpiredWindowDoesNotTouchNewWindow() throws InterruptedException {
        log.info("[release-once] input: tenantId={}, limit={}, window={}ms, 窗口1的额度在窗口2归还",
                TENANT, LIMIT, WINDOW_MS);

        // 窗口1：占一份额度
        TenantRateLimiter.Permit oldPermit = rateLimiter.tryAcquire(TENANT);
        assertNotNull(oldPermit);
        assertEquals(LIMIT - 1, rateLimiter.remainingCapacity(TENANT));

        // 等窗口翻篇，窗口2占两份额度
        Thread.sleep(WINDOW_MS + 100L);
        TenantRateLimiter.Permit p1 = rateLimiter.tryAcquire(TENANT);
        TenantRateLimiter.Permit p2 = rateLimiter.tryAcquire(TENANT);
        assertNotNull(p1);
        assertNotNull(p2);
        assertEquals(LIMIT - 2, rateLimiter.remainingCapacity(TENANT));

        // 窗口1的旧凭证归还：不得扣减窗口2的计数
        rateLimiter.release(oldPermit);
        int remaining = rateLimiter.remainingCapacity(TENANT);
        log.info("[release-once] decision: 旧窗口凭证归还后 remainingCapacity={}"
                        + "（判定依据=跨窗口归还被忽略, 剩余=上限{}-窗口2在持2）",
                remaining, LIMIT);
        assertEquals(LIMIT - 2, remaining, "跨窗口归还不得减错窗口");

        // 窗口2自己的归还仍然生效
        rateLimiter.release(p1);
        rateLimiter.release(p2);
        assertEquals(LIMIT, rateLimiter.remainingCapacity(TENANT), "本窗口归还必须正常生效");
    }
}
