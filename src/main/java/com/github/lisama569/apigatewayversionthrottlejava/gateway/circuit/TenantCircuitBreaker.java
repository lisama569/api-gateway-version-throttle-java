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
 *   <li>保护期持续 {@code openMillis} 后进入<b>半开试探</b>：只放行极少量
 *       （{@code halfOpenProbeCount} 个）试探请求，其余请求继续快速失败；</li>
 *   <li>试探请求一旦超时，立即重新进入保护期，不会把成批流量压到未恢复的后端；
 *       试探成功后才真正闭合，该放行的请求照常放行。</li>
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
        private boolean halfOpen;     // 保护期已过、正在试探
        private int probesInFlight;   // 半开期间已放行但未结束的试探请求数
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
     * @return false 表示处于保护期（或试探名额已用完），应快速失败；
     *         保护期结束后只放行极少量试探请求
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
            // 保护期结束：进入半开，只放极少量请求试探后端是否恢复
            if (!state.halfOpen) {
                state.halfOpen = true;
                log.info("circuit half-open: tenantId={}, route={}, probing with up to {} request(s)",
                        tenantId, route, properties.getHalfOpenProbeCount());
            }
            if (state.probesInFlight < properties.getHalfOpenProbeCount()) {
                state.probesInFlight++;
                return true;
            }
            return false;
        }
    }

    /** 后端调用成功：试探成功则真正闭合；闭合期成功则清零连续超时计数。 */
    public void onSuccess(String tenantId, String route) {
        State state = states.get(new Key(tenantId, route));
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.halfOpen) {
                state.halfOpen = false;
                state.probesInFlight = 0;
                state.openUntilMillis = 0L;
                state.consecutiveTimeouts = 0;
                log.info("circuit closed after successful probe: tenantId={}, route={}",
                        tenantId, route);
                return;
            }
            state.consecutiveTimeouts = 0;
        }
    }

    /**
     * 后端调用超时。
     * <p>半开试探期间超时：立即重新进入保护期，不再放行；
     * 闭合期超时：累计连续超时次数，达到阈值则进入保护期。
     */
    public void onTimeout(String tenantId, String route) {
        long now = System.currentTimeMillis();
        State state = states.computeIfAbsent(new Key(tenantId, route), k -> new State());
        synchronized (state) {
            if (state.halfOpen) {
                state.halfOpen = false;
                state.probesInFlight = 0;
                state.consecutiveTimeouts = 0;
                state.openUntilMillis = now + properties.getOpenMillis();
                log.warn("circuit re-opened: probe still timing out, tenantId={}, route={}, openMillis={}",
                        tenantId, route, properties.getOpenMillis());
                return;
            }
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
