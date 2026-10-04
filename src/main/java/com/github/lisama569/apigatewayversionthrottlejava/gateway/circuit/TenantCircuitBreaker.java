package com.github.lisama569.apigatewayversionthrottlejava.gateway.circuit;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.CircuitBreakerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按「租户 + 路由」维度的快速失败熔断器。
 * <p>语义：
 * <ul>
 *   <li>闭合：请求照常放行；后端超时使连续超时计数 +1，成功则清零；</li>
 *   <li>连续超时达到 {@code failureThreshold} 后进入保护期（打开），
 *       期间 {@link #tryPass} 直接返回 false，流量不再打到已卡住的后端；</li>
 *   <li>保护期持续 {@code openMillis} 后自动恢复闭合（计数清零），
 *       恢复后该放行的请求照常放行。</li>
 * </ul>
 * 每个 (tenantId, route) 的状态变更都在各自 synchronized 临界区内完成，并发下计数不错乱。
 */
@Component
public class TenantCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(TenantCircuitBreaker.class);

    /** 单个 (租户, 路由) 的熔断状态。 */
    private static final class State {
        private int consecutiveTimeouts;
        private long openUntilMillis; // 0 表示闭合
    }

    private record Key(String tenantId, String route) {
    }

    private final CircuitBreakerProperties properties;
    private final Map<Key, State> states = new ConcurrentHashMap<>();

    public TenantCircuitBreaker(CircuitBreakerProperties properties) {
        this.properties = properties;
    }

    /**
     * 请求进入后端前调用。
     *
     * @return false 表示处于保护期，应快速失败；保护期结束会自动恢复并放行
     */
    public boolean tryPass(String tenantId, String route) {
        long now = System.currentTimeMillis();
        State state = states.computeIfAbsent(new Key(tenantId, route), k -> new State());
        synchronized (state) {
            if (state.openUntilMillis == 0L) {
                return true;
            }
            if (now < state.openUntilMillis) {
                return false;
            }
            // 保护期结束：自动恢复闭合
            log.info("circuit recovered: tenantId={}, route={}", tenantId, route);
            state.openUntilMillis = 0L;
            state.consecutiveTimeouts = 0;
            return true;
        }
    }

    /** 后端调用成功，重置该租户在该路由上的连续超时计数。 */
    public void onSuccess(String tenantId, String route) {
        State state = states.get(new Key(tenantId, route));
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.consecutiveTimeouts = 0;
        }
    }

    /** 后端调用超时，累计连续超时次数，达到阈值则进入保护期。 */
    public void onTimeout(String tenantId, String route) {
        long now = System.currentTimeMillis();
        State state = states.computeIfAbsent(new Key(tenantId, route), k -> new State());
        synchronized (state) {
            state.consecutiveTimeouts++;
            if (state.consecutiveTimeouts >= properties.getFailureThreshold()
                    && state.openUntilMillis == 0L) {
                state.openUntilMillis = now + properties.getOpenMillis();
                log.warn("circuit opened: tenantId={}, route={}, consecutiveTimeouts={}, openMillis={}",
                        tenantId, route, state.consecutiveTimeouts, properties.getOpenMillis());
            }
        }
    }

    /** 距保护期结束还剩多少毫秒（不在保护期时返回 0）。 */
    public long retryAfterMillis(String tenantId, String route) {
        long now = System.currentTimeMillis();
        State state = states.get(new Key(tenantId, route));
        if (state == null) {
            return 0L;
        }
        synchronized (state) {
            return Math.max(0L, state.openUntilMillis - now);
        }
    }
}
