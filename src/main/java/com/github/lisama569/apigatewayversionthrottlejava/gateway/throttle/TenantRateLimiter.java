package com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.ThrottleProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户维度固定窗口限流器。
 * <p>每个租户持有独立的窗口状态，「窗口重置判断 + 计数检查 + 递增」在同一个
 * synchronized 临界区内完成，保证并发下不超发。
 *
 * <p>额度纪律：放行时发放一个<b>携带窗口序号（epoch）的 {@link Permit}</b>，
 * 请求结束（失败/超时）时只能归还自己持有的许可：
 * <ul>
 *   <li>归还操作幂等——同一份许可重复归还不会重复退额度，杜绝「多还」；</li>
 *   <li>只在许可发放时的同一个窗口内归还；窗口已滚动则不再触碰新窗口计数，
 *       杜绝跨窗口把新窗口额度算错；</li>
 *   <li>每个被放行的请求最终要么成功占用、要么恰好归还一次，杜绝「漏还」。</li>
 * </ul>
 */
@Component
public class TenantRateLimiter {

    /**
     * 一次放行所占用的额度凭证。归还动作幂等：只有第一个调用者真正退回计数。
     */
    public static final class Permit {
        private final String tenantId;
        private final long epoch;
        private boolean returned;

        private Permit(String tenantId, long epoch) {
            this.tenantId = tenantId;
            this.epoch = epoch;
        }

        public String tenantId() {
            return tenantId;
        }
    }

    /** 单租户窗口状态：窗口起点 + 窗口序号 + 窗口内已放行计数。 */
    private static final class WindowState {
        private long windowStartMillis;
        private long epoch;
        private int count;

        WindowState(long now) {
            this.windowStartMillis = now;
            this.epoch = 1L;
        }
    }

    private final ThrottleProperties properties;
    private final Map<String, WindowState> windows = new ConcurrentHashMap<>();

    public TenantRateLimiter(ThrottleProperties properties) {
        this.properties = properties;
    }

    /**
     * 尝试为租户放行一个请求；成功返回占用了一份额度的 {@link Permit}，额度不足返回 null。
     */
    public Permit tryAcquire(String tenantId) {
        long now = System.currentTimeMillis();
        WindowState state = windows.computeIfAbsent(tenantId, k -> new WindowState(now));
        synchronized (state) {
            rollWindowIfNeeded(state, now);
            if (state.count < limitFor(tenantId)) {
                state.count++;
                return new Permit(tenantId, state.epoch);
            }
            return null;
        }
    }

    /**
     * 归还许可：仅当许可尚未归还、且其所属窗口仍是当前窗口时退回一份计数。
     * 调用多次也只生效一次。
     */
    public void release(Permit permit) {
        if (permit == null) {
            return;
        }
        WindowState state = windows.get(permit.tenantId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (permit.returned) {
                return;
            }
            // 许可来自已经滚走的旧窗口：不触碰当前窗口计数，避免多还。
            if (state.epoch != permit.epoch) {
                permit.returned = true;
                return;
            }
            permit.returned = true;
            if (state.count > 0) {
                state.count--;
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
            rollWindowIfNeeded(state, now);
            return Math.max(0, limitFor(tenantId) - state.count);
        }
    }

    private void rollWindowIfNeeded(WindowState state, long now) {
        if (now - state.windowStartMillis >= properties.getWindowMillis()) {
            state.windowStartMillis = now;
            state.count = 0;
            state.epoch++;
        }
    }

    private int limitFor(String tenantId) {
        return properties.getTenantLimits().getOrDefault(tenantId, properties.getDefaultLimit());
    }
}
