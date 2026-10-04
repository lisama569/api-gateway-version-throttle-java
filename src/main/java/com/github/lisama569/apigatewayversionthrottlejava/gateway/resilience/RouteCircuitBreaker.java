package com.github.lisama569.apigatewayversionthrottlejava.gateway.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户 + 路由维度的快速失败熔断器。
 *
 * <p>状态机（每个 tenantId+route 独立一份状态，互不影响）：
 * <ul>
 *   <li><b>CLOSED</b>：正常放行；连续超时次数累计，达到 {@code failureThreshold}
 *       后转入 OPEN。非超时结果（成功 / 业务拒绝 / 内部错误等）会把连续计数清零。</li>
 *   <li><b>OPEN</b>：保护期内请求直接快速失败，不再打向后端，
 *       并返回保护期剩余时间作为「多久后可再试」的提示。</li>
 *   <li><b>HALF_OPEN</b>：保护期到期后的第一个请求被试探性放行；
 *       该请求超时则立即回到 OPEN（重新计时保护期），
 *       得到任何非超时结果则回到 CLOSED，正常流量完全恢复。</li>
 * </ul>
 * 状态切换全部在每个 key 自己的监视器内完成，并发下判定一致。
 */
@Component
public class RouteCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(RouteCircuitBreaker.class);

    /** 请求进入前的检查结果：allowed=false 时 retryAfterMillis 为建议再试等待。 */
    public record CheckResult(boolean allowed, long retryAfterMillis) {
    }

    private static final class BreakerState {
        /** CLOSED 下连续超时计数；OPEN 下固定为阈值；HALF_OPEN 下保持阈值直到出结果。 */
        private int consecutiveTimeouts;
        /** OPEN 保护期的到期时间戳（epoch millis），0 表示不在 OPEN。 */
        private long openUntilMillis;

        boolean isOpen(long now) {
            return openUntilMillis != 0L && now < openUntilMillis;
        }
    }

    private final CircuitBreakerProperties properties;
    private final Map<String, BreakerState> states = new ConcurrentHashMap<>();

    public RouteCircuitBreaker(CircuitBreakerProperties properties) {
        this.properties = properties;
    }

    private static String key(String tenantId, String route) {
        return tenantId + "|" + route;
    }

    /**
     * 请求进入前检查。
     *
     * @return 允许放行时 {@link CheckResult#allowed()} 为 true；
     *         OPEN 保护期内返回 false 与剩余保护时长（毫秒）
     */
    public CheckResult check(String tenantId, String route) {
        BreakerState state = states.computeIfAbsent(key(tenantId, route), k -> new BreakerState());
        long now = System.currentTimeMillis();
        synchronized (state) {
            if (state.isOpen(now)) {
                long retryAfter = state.openUntilMillis - now;
                log.info("circuit decision: tenantId={}, route={} -> OPEN quick-fail, retryAfterMillis={}",
                        tenantId, route, retryAfter);
                return new CheckResult(false, retryAfter);
            }
            // 保护期刚结束：当前请求作为试探流量进入 HALF_OPEN。
            if (state.openUntilMillis != 0L) {
                state.openUntilMillis = 0L;
                log.info("circuit decision: tenantId={}, route={} OPEN -> HALF_OPEN probe admitted",
                        tenantId, route);
            }
            return new CheckResult(true, 0L);
        }
    }

    /**
     * 记录一次后端调用结果。
     *
     * @param timeout true=后端超时（连续计数 +1，达到阈值则打开保护）；
     *                false=任何非超时结果（成功 / 业务拒绝 / 其他错误），连续计数清零并恢复关闭
     */
    public void record(String tenantId, String route, boolean timeout) {
        BreakerState state = states.computeIfAbsent(key(tenantId, route), k -> new BreakerState());
        long now = System.currentTimeMillis();
        synchronized (state) {
            if (timeout) {
                state.consecutiveTimeouts++;
                if (state.consecutiveTimeouts >= properties.getFailureThreshold() || state.openUntilMillis != 0L) {
                    state.openUntilMillis = now + properties.getOpenMillis();
                    log.warn("circuit decision: tenantId={}, route={} consecutiveTimeouts={} -> OPEN for {}ms",
                            tenantId, route, state.consecutiveTimeouts, properties.getOpenMillis());
                } else {
                    log.info("circuit decision: tenantId={}, route={} consecutiveTimeouts={}/{}",
                            tenantId, route, state.consecutiveTimeouts, properties.getFailureThreshold());
                }
            } else {
                if (state.openUntilMillis != 0L || state.consecutiveTimeouts > 0) {
                    log.info("circuit decision: tenantId={}, route={} recovered -> CLOSED", tenantId, route);
                }
                state.consecutiveTimeouts = 0;
                state.openUntilMillis = 0L;
            }
        }
    }
}
