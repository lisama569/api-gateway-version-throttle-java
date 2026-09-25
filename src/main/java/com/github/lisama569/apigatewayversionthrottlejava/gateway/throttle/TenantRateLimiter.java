package com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.ThrottleProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户维度固定窗口限流器。
 * <p>每个租户持有独立的窗口状态，计数检查与递增在同一个 synchronized 临界区内完成，
 * 保证并发下不超发；后端调用失败时通过 {@link #release(String)} 归还额度，
 * 使被拒绝/失败的请求不占用本应释放的额度。
 */
@Component
public class TenantRateLimiter {

    /** 单租户窗口状态：窗口起点 + 窗口内已放行计数。 */
    private static final class WindowState {
        private long windowStartMillis;
        private int count;

        WindowState(long now) {
            this.windowStartMillis = now;
        }
    }

    private final ThrottleProperties properties;
    private final Map<String, WindowState> windows = new ConcurrentHashMap<>();

    public TenantRateLimiter(ThrottleProperties properties) {
        this.properties = properties;
    }

    /** 尝试为租户放行一个请求；成功返回 true 并占用一份额度。 */
    public boolean tryAcquire(String tenantId) {
        long now = System.currentTimeMillis();
        WindowState state = windows.computeIfAbsent(tenantId, k -> new WindowState(now));
        synchronized (state) {
            if (now - state.windowStartMillis >= properties.getWindowMillis()) {
                state.windowStartMillis = now;
                state.count = 0;
            }
            if (state.count < limitFor(tenantId)) {
                state.count++;
                return true;
            }
            return false;
        }
    }

    /** 归还一份额度（后端调用失败/被拒绝时调用），计数不会减到 0 以下。 */
    public void release(String tenantId) {
        WindowState state = windows.get(tenantId);
        if (state == null) {
            return;
        }
        synchronized (state) {
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
