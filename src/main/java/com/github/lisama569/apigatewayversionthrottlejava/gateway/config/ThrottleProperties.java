package com.github.lisama569.apigatewayversionthrottlejava.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 限流配置（骨架）。 */
@Component
@ConfigurationProperties(prefix = "gateway.throttle")
public class ThrottleProperties {

    private int defaultLimit = 0;
    private long windowMillis = 0L;
    private Map<String, Integer> tenantLimits = Map.of();

    public int getDefaultLimit() { return defaultLimit; }
    public void setDefaultLimit(int defaultLimit) { this.defaultLimit = defaultLimit; }
    public long getWindowMillis() { return windowMillis; }
    public void setWindowMillis(long windowMillis) { this.windowMillis = windowMillis; }
    public Map<String, Integer> getTenantLimits() { return tenantLimits; }
    public void setTenantLimits(Map<String, Integer> tenantLimits) { this.tenantLimits = tenantLimits; }
}
