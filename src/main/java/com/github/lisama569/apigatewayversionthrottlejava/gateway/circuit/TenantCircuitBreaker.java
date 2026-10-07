package com.github.lisama569.apigatewayversionthrottlejava.gateway.circuit;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.CircuitBreakerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按「租户 + 路由」维度的快速失败熔断器。
 * <p>状态机：
 * <ul>
 *   <li>{@link State#CLOSED 闭合}：请求照常放行；后端超时使连续超时计数 +1，成功则清零；</li>
 *   <li>连续超时达到 {@code failureThreshold} 后进入 {@link State#OPEN 保护期}，
 *       期间 {@link #tryPass} 直接返回 false，流量不再打到已卡住的后端；</li>
 *   <li>保护期持续 {@code openMillis} 后并<b>不</b>直接放行全部流量，而是进入
 *       {@link State#HALF_OPEN 半开试探}：只允许 {@code halfOpenPermits}
 *       （默认 1，极少量）个探测请求真正打到后端，其余请求继续快速失败；</li>
 *   <li>探测请求仍超时 -> <b>立即</b>重新进入保护期，无需再攒失败次数，成批流量不会压到卡住的后端；
 *       探测请求成功（后端已恢复）-> 回到闭合，之后该放行的请求照常放行。</li>
 * </ul>
 * 每个 (tenantId, route) 的状态变更都在各自 synchronized 临界区内完成，并发下计数不错乱。
 */
@Component
public class TenantCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(TenantCircuitBreaker.class);

    private enum State { CLOSED, OPEN, HALF_OPEN }

    /** 单个 (租户, 路由) 的熔断状态。 */
    private static final class CircuitState {
        private State state = State.CLOSED;
        private int consecutiveTimeouts;
        private long openUntilMillis; // OPEN 时的保护期截止时间；其他状态为 0
        private int probesInFlight;   // HALF_OPEN 时已放行、尚未返回的探测请求数
    }

    private record Key(String tenantId, String route) {
    }

    private final CircuitBreakerProperties properties;
    private final Map<Key, CircuitState> states = new ConcurrentHashMap<>();

    public TenantCircuitBreaker(CircuitBreakerProperties properties) {
        this.properties = properties;
    }

    /**
     * 请求进入后端前调用。
     *
     * @return true 表示可以打到后端（闭合，或半开中拿到了唯一的探测名额）；
     *         false 表示处于保护期 / 探测名额已用尽，应快速失败
     */
    public boolean tryPass(String tenantId, String route) {
        long now = System.currentTimeMillis();
        CircuitState state = states.computeIfAbsent(new Key(tenantId, route), k -> new CircuitState());
        synchronized (state) {
            switch (state.state) {
                case CLOSED:
                    return true;
                case OPEN:
                    if (now < state.openUntilMillis) {
                        return false;
                    }
                    // 保护期结束：进入半开试探，而不是直接恢复
                    state.state = State.HALF_OPEN;
                    state.probesInFlight = 0;
                    log.info("circuit half-open probing: tenantId={}, route={}, permits={}",
                            tenantId, route, properties.getHalfOpenPermits());
                    return tryGrantProbe(tenantId, route, state);
                case HALF_OPEN:
                    return tryGrantProbe(tenantId, route, state);
                default:
                    return true;
            }
        }
    }

    /** HALF_OPEN 下发放探测名额；调用方必须已持有 state 锁。 */
    private boolean tryGrantProbe(String tenantId, String route, CircuitState state) {
        if (state.probesInFlight < properties.getHalfOpenPermits()) {
            state.probesInFlight++;
            log.info("probe admitted: tenantId={}, route={}, probesInFlight={}",
                    tenantId, route, state.probesInFlight);
            return true;
        }
        return false;
    }

    /** 后端调用成功：闭合时清零计数；半开时判定后端恢复，完全闭合。 */
    public void onSuccess(String tenantId, String route) {
        CircuitState state = states.get(new Key(tenantId, route));
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.consecutiveTimeouts = 0;
            if (state.state == State.HALF_OPEN) {
                closeAfterHealthyProbe(tenantId, route, state);
            }
        }
    }

    /**
     * 后端在时限内返回了「非超时」结果（含业务拒绝、内部错误）：
     * 说明后端是通的、并未卡住。闭合状态下不计超时；半开试探中据此判定后端恢复并闭合，
     * 避免一次业务异常把熔断器永久卡在半开（探测名额已用尽又等不到下一个保护期）。
     */
    public void onNonTimeoutResult(String tenantId, String route) {
        CircuitState state = states.get(new Key(tenantId, route));
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.state == State.HALF_OPEN) {
                closeAfterHealthyProbe(tenantId, route, state);
            }
        }
    }

    /** 探测确认后端健康后完全闭合；调用方必须已持有 state 锁。 */
    private void closeAfterHealthyProbe(String tenantId, String route, CircuitState state) {
        state.state = State.CLOSED;
        state.probesInFlight = 0;
        state.consecutiveTimeouts = 0;
        state.openUntilMillis = 0L;
        log.info("circuit closed after healthy probe: tenantId={}, route={}", tenantId, route);
    }

    /** 后端调用超时：闭合时累计连续超时；半开探测超时则立即重新进入保护期。 */
    public void onTimeout(String tenantId, String route) {
        long now = System.currentTimeMillis();
        CircuitState state = states.computeIfAbsent(new Key(tenantId, route), k -> new CircuitState());
        synchronized (state) {
            if (state.state == State.HALF_OPEN) {
                // 探测证明后端仍未恢复：立刻重新打开，不再攒次数、不再放批量流量
                state.state = State.OPEN;
                state.probesInFlight = 0;
                state.openUntilMillis = now + properties.getOpenMillis();
                log.warn("probe timed out, circuit reopened immediately: tenantId={}, route={}, openMillis={}",
                        tenantId, route, properties.getOpenMillis());
                return;
            }
            if (state.state == State.OPEN) {
                // 保护期内的请求不会打到后端，走到这里属于迟到结果，忽略
                return;
            }
            state.consecutiveTimeouts++;
            if (state.consecutiveTimeouts >= properties.getFailureThreshold()) {
                state.state = State.OPEN;
                state.openUntilMillis = now + properties.getOpenMillis();
                log.warn("circuit opened: tenantId={}, route={}, consecutiveTimeouts={}, openMillis={}",
                        tenantId, route, state.consecutiveTimeouts, properties.getOpenMillis());
            }
        }
    }

    /**
     * 距保护期结束还剩多少毫秒（用于快速失败提示）。
     * 保护期内返回剩余时间；半开试探进行中返回 0（探测结果很快就会决定下一步）。
     */
    public long retryAfterMillis(String tenantId, String route) {
        long now = System.currentTimeMillis();
        CircuitState state = states.get(new Key(tenantId, route));
        if (state == null) {
            return 0L;
        }
        synchronized (state) {
            if (state.state == State.OPEN) {
                return Math.max(0L, state.openUntilMillis - now);
            }
            return 0L;
        }
    }

    /** 是否处于半开试探中（测试与观测用）。 */
    public boolean isHalfOpen(String tenantId, String route) {
        CircuitState state = states.get(new Key(tenantId, route));
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.state == State.HALF_OPEN;
        }
    }
}
