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
 * <p>每个租户持有独立的窗口状态，窗口滚动判断、计数检查与递增在同一个 synchronized 临界区内完成，
 * 保证并发下不超发。
 * <p>额度纪律（关键语义）：
 * <ul>
 *   <li>{@link #tryAcquire} 成功后返回一份与「申请时所在窗口」绑定的 {@link Lease}；</li>
 *   <li>归还只能通过 {@link Lease#release()}，且每份租约至多生效一次（CAS 幂等），
 *       任何重复归还（同时结束或乱序结束）都不会把计数多减；</li>
 *   <li>归还时只减该租约对应窗口的计数：请求若在窗口翻到下一段之后才结束，
 *       额度归还到旧窗口，<b>绝不计入新窗口</b>，因此新窗口的实际放行量不可能被抬高；</li>
 *   <li>计数在窗口内始终等于「当前仍占用额度的请求数」，不可能减成负数。</li>
 * </ul>
 */
@Component
public class TenantRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TenantRateLimiter.class);

    /**
     * 一次成功放行的额度租约。绑定租户、申请时所在窗口起点与该窗口状态对象，
     * 与后续窗口滚动完全无关。
     */
    public final class Lease {
        private final String tenantId;
        private final long windowStartMillis;
        private final WindowState state;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Lease(String tenantId, long windowStartMillis, WindowState state) {
            this.tenantId = tenantId;
            this.windowStartMillis = windowStartMillis;
            this.state = state;
        }

        /**
         * 归还本租约占用的额度。幂等：重复调用（并发或乱序）至多生效一次。
         * 只归还到申请时所在的窗口；若该窗口已经滚走，则本次归还对新窗口没有任何影响。
         *
         * @return true 表示本次调用实际完成了归还；false 表示此前已归还过（被忽略）
         */
        public boolean release() {
            if (!released.compareAndSet(false, true)) {
                log.warn("duplicate quota release ignored: tenantId={}, windowStart={}",
                        tenantId, windowStartMillis);
                return false;
            }
            synchronized (state) {
                if (state.windowStartMillis != windowStartMillis) {
                    // 申请时的窗口已滚走：旧窗口计数随之作废，归还不得影响新窗口
                    log.info("release to rolled-over window discarded: tenantId={}, leaseWindow={}, currentWindow={}",
                            tenantId, windowStartMillis, state.windowStartMillis);
                    return false;
                }
                if (state.inFlight > 0) {
                    state.inFlight--;
                }
                log.debug("quota released: tenantId={}, windowStart={}, inFlight={}",
                        tenantId, windowStartMillis, state.inFlight);
                return true;
            }
        }
    }

    /** 单租户窗口状态：窗口起点 + 窗口内仍占用额度的请求数（已放行且未归还）。 */
    private static final class WindowState {
        private long windowStartMillis;
        private int inFlight;

        WindowState(long now) {
            this.windowStartMillis = now;
        }
    }

    private final ThrottleProperties properties;
    private final Map<String, WindowState> windows = new ConcurrentHashMap<>();

    public TenantRateLimiter(ThrottleProperties properties) {
        this.properties = properties;
    }

    /**
     * 尝试为租户放行一个请求；成功返回绑定当前窗口的额度租约，失败返回 {@code null}（未占用任何额度）。
     */
    public Lease tryAcquire(String tenantId) {
        long now = System.currentTimeMillis();
        WindowState state = windows.computeIfAbsent(tenantId, k -> new WindowState(now));
        synchronized (state) {
            rolloverIfNeeded(state, now);
            if (state.inFlight < limitFor(tenantId)) {
                state.inFlight++;
                return new Lease(tenantId, state.windowStartMillis, state);
            }
            return null;
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
            rolloverIfNeeded(state, now);
            return Math.max(0, limitFor(tenantId) - state.inFlight);
        }
    }

    /** 窗口内仍占用额度的请求数（测试与观测用）。 */
    public int inFlight(String tenantId) {
        long now = System.currentTimeMillis();
        WindowState state = windows.get(tenantId);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            rolloverIfNeeded(state, now);
            return state.inFlight;
        }
    }

    private void rolloverIfNeeded(WindowState state, long now) {
        if (now - state.windowStartMillis >= properties.getWindowMillis()) {
            state.windowStartMillis = now;
            state.inFlight = 0;
        }
    }

    private int limitFor(String tenantId) {
        return properties.getTenantLimits().getOrDefault(tenantId, properties.getDefaultLimit());
    }
}
