package com.github.lisama569.apigatewayversionthrottlejava.gateway.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 快速失败（熔断）配置，维度为租户 + 路由。
 * <ul>
 *   <li>{@code gateway.circuit-breaker.failure-threshold}：连续超时多少次后进入保护期；</li>
 *   <li>{@code gateway.circuit-breaker.open-millis}：保护期时长，期间直接快速失败，
 *       到期后自动试探恢复。</li>
 * </ul>
 * 仅「连续超时」计入；成功或任何非超时结果都会把连续计数清零。
 */
@Component
@ConfigurationProperties(prefix = "gateway.circuit-breaker")
public class CircuitBreakerProperties {

    private int failureThreshold = 5;
    private long openMillis = 5000L;

    public int getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
    public long getOpenMillis() { return openMillis; }
    public void setOpenMillis(long openMillis) { this.openMillis = openMillis; }
}
