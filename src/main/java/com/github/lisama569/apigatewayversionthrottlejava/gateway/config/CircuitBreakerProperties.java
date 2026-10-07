package com.github.lisama569.apigatewayversionthrottlejava.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 快速失败（熔断）配置。
 * <p>同一租户在同一路由上连续超时达到阈值后，进入一段短暂的快速失败保护期；
 * 保护期结束后进入半开试探，只允许极少量探测请求打到后端，
 * 探测仍超时则立即重新进入保护期，探测成功才完全恢复。
 */
@Component
@ConfigurationProperties(prefix = "gateway.circuit")
public class CircuitBreakerProperties {

    /** 触发快速失败所需的连续超时次数。 */
    private int failureThreshold = 3;

    /** 快速失败保护期时长（毫秒），到期后进入半开试探而非直接恢复。 */
    private long openMillis = 30_000L;

    /** 保护期结束后半开试探允许同时打到后端的探测请求数（默认极少量：1）。 */
    private int halfOpenPermits = 1;

    public int getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
    public long getOpenMillis() { return openMillis; }
    public void setOpenMillis(long openMillis) { this.openMillis = openMillis; }
    public int getHalfOpenPermits() { return halfOpenPermits; }
    public void setHalfOpenPermits(int halfOpenPermits) { this.halfOpenPermits = halfOpenPermits; }
}
