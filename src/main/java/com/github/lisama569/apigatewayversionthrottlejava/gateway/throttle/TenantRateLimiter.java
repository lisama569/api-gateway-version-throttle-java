package com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按租户维度的令牌桶限流器。
 * <p>
 * 每个租户一个桶：容量 capacity，按 refillPerSecond 匀速补充。
 * 所有状态变更都在桶对象上 synchronized，保证并发下不超发、不漏限。
 * 后端失败的请求通过 {@link #release(String)} 归还额度。
 */
@Component
public class TenantRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TenantRateLimiter.class);

    private final int capacity;
    private final double refillPerSecond;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public TenantRateLimiter(GatewayProperties properties) {
        this.capacity = properties.getRateLimitCapacity();
        this.refillPerSecond = properties.getRateLimitRefillPerSecond();
    }

    /** 测试专用：显式指定参数 */
    public TenantRateLimiter(int capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    /** 尝试为租户获取一个放行额度 */
    public RateLimitDecision tryAcquire(String tenantId) {
        Bucket bucket = bucket(tenantId);
        synchronized (bucket) {
            bucket.refill();
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                log.info("[rate-limit] tenant={} -> ALLOWED (remaining={})",
                        tenantId, String.format("%.2f", bucket.tokens));
                return RateLimitDecision.allow();
            }
            long retryAfter = (long) Math.ceil((1.0 - bucket.tokens) / refillPerSecond * 1000.0);
            log.info("[rate-limit] tenant={} -> REJECTED (tokens={}, retryAfterMs={})",
                    tenantId, String.format("%.2f", bucket.tokens), retryAfter);
            return RateLimitDecision.reject(Math.max(retryAfter, 1L));
        }
    }

    /** 归还一个额度（后端失败的请求不得占用其本应释放的额度），不超过桶容量 */
    public void release(String tenantId) {
        Bucket bucket = bucket(tenantId);
        synchronized (bucket) {
            bucket.refill();
            bucket.tokens = Math.min(bucket.tokens + 1.0, capacity);
            log.info("[rate-limit] tenant={} -> RELEASED (tokens={})",
                    tenantId, String.format("%.2f", bucket.tokens));
        }
    }

    private Bucket bucket(String tenantId) {
        return buckets.computeIfAbsent(tenantId, k -> {
            Bucket b = new Bucket();
            b.tokens = capacity;
            b.lastRefillNanos = System.nanoTime();
            return b;
        });
    }

    private final class Bucket {
        double tokens;
        long lastRefillNanos;

        void refill() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
                lastRefillNanos = now;
            }
        }
    }
}
