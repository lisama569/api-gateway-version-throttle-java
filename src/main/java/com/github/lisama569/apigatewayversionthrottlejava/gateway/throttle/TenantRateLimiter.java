package com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.ThrottleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 租户维度固定窗口限流器。
 * <p>每个租户持有独立的窗口状态，计数检查与递增在同一个 synchronized 临界区内完成，
 * 保证并发下不超发。
 * <p>额度归还语义（跨窗口安全）：
 * <ul>
 *   <li>{@link #tryAcquire(String)} 成功时返回一枚 {@link Permit}，
 *       记录该额度所属的窗口起点；</li>
 *   <li>{@link #release(Permit)} 只在「当前窗口仍是额度所属窗口」时才扣减计数——
 *       跨窗口才结束的请求，其归还不会算到新窗口头上，
 *       新窗口的放行量不会被旧窗口的归还抬高；</li>
 *   <li>每枚 Permit 最多归还一次（内部一次性标志），
 *       重复归还、乱序归还都不会把计数减成负数或减错窗口。</li>
 * </ul>
 */
@Component
public class TenantRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TenantRateLimiter.class);

    /** 单租户窗口状态：窗口起点 + 窗口内已放行计数。 */
    private static final class WindowState {
        private long windowStartMillis;
        private int count;

        WindowState(long now) {
            this.windowStartMillis = now;
        }
    }

    /**
     * 一次成功申请到的额度凭证。
     * <p>绑定所属窗口起点，保证归还只影响原窗口；内部一次性标志保证最多归还一次。
     */
    public static final class Permit {
        private final String tenantId;
        private final WindowState state;
        private final long windowStartMillis;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Permit(String tenantId, WindowState state, long windowStartMillis) {
            this.tenantId = tenantId;
            this.state = state;
            this.windowStartMillis = windowStartMillis;
        }
    }

    private final ThrottleProperties properties;
    private final Map<String, WindowState> windows = new ConcurrentHashMap<>();

    public TenantRateLimiter(ThrottleProperties properties) {
        this.properties = properties;
    }

    /**
     * 尝试为租户放行一个请求。
     *
     * @return 放行时返回额度凭证（失败/超时后须用 {@link #release(Permit)} 归还）；
     *         被限流时返回 null，从未占用额度
     */
    public Permit tryAcquire(String tenantId) {
        long now = System.currentTimeMillis();
        WindowState state = windows.computeIfAbsent(tenantId, k -> new WindowState(now));
        synchronized (state) {
            if (now - state.windowStartMillis >= properties.getWindowMillis()) {
                state.windowStartMillis = now;
                state.count = 0;
            }
            if (state.count < limitFor(tenantId)) {
                state.count++;
                return new Permit(tenantId, state, state.windowStartMillis);
            }
            return null;
        }
    }

    /**
     * 归还一份额度（后端调用失败/被拒绝时调用）。
     * <p>幂等：同一枚凭证重复调用只生效一次；若额度所属窗口已经翻篇，
     * 归还被忽略，不会扣减新窗口的计数。
     */
    public void release(Permit permit) {
        if (permit == null) {
            return;
        }
        if (!permit.released.compareAndSet(false, true)) {
            log.info("quota release ignored: tenantId={}, permit already released",
                    permit.tenantId);
            return;
        }
        synchronized (permit.state) {
            if (permit.state.windowStartMillis != permit.windowStartMillis) {
                log.info("quota release ignored: tenantId={}, permit window expired "
                                + "(permitWindowStart={}, currentWindowStart={})",
                        permit.tenantId, permit.windowStartMillis, permit.state.windowStartMillis);
                return;
            }
            if (permit.state.count > 0) {
                permit.state.count--;
            }
        }
    }

    /** 当前窗口剩余额度（用于日志与观测）。 */
    public int remainingCapacity(String tenantId) {
        long now = System.currentTimeMillis();
        WindowState state = windows.get(tenantId);
        if (state == null) {
            return limitFor(tenantId);
        }
        synchronized (state) {
            if (now - state.windowStartMillis >= properties.getWindowMillis()) {
                return limitFor(tenantId);
            }
            return Math.max(0, limitFor(tenantId) - state.count);
        }
    }

    private int limitFor(String tenantId) {
        return properties.getTenantLimits().getOrDefault(tenantId, properties.getDefaultLimit());
    }
}
