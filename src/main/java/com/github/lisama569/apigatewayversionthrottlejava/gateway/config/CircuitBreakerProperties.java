package com.github.lisama569.apigatewayversionthrottlejava.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 快速失败（熔断）配置。
 * <p>同一租户在同一路由上连续超时达到阈值后，进入一段短暂的快速失败保护期，
 * 保护期结束后自动恢复。
 */
@Component
@ConfigurationProperties(prefix = "gateway.circuit")
public class CircuitBreakerProperties {

    /** 触发快速失败所需的连续超时次数。 */
    private int failureThreshold = 3;

    /** 快速失败保护期时长（毫秒），到期后自动恢复放行。 */
    private long openMillis = 30_000L;

    public int getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
    public long getOpenMillis() { return openMillis; }
    public void setOpenMillis(long openMillis) { this.openMillis = openMillis; }
}
