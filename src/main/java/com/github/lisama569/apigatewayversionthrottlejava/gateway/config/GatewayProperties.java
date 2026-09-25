package com.github.lisama569.apigatewayversionthrottlejava.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 网关配置项。
 */
@Component
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /** 版本生命周期：key 为版本号，value 为 ACTIVE / DEPRECATED */
    private Map<String, String> versions = new HashMap<>();

    /** 限流：每租户令牌桶容量 */
    private int rateLimitCapacity = 10;

    /** 限流：每租户每秒补充令牌数 */
    private double rateLimitRefillPerSecond = 10.0;

    /** 后端调用超时（毫秒） */
    private long backendTimeoutMillis = 2000;

    public Map<String, String> getVersions() {
        return versions;
    }

    public void setVersions(Map<String, String> versions) {
        this.versions = versions;
    }

    public int getRateLimitCapacity() {
        return rateLimitCapacity;
    }

    public void setRateLimitCapacity(int rateLimitCapacity) {
        this.rateLimitCapacity = rateLimitCapacity;
    }

    public double getRateLimitRefillPerSecond() {
        return rateLimitRefillPerSecond;
    }

    public void setRateLimitRefillPerSecond(double rateLimitRefillPerSecond) {
        this.rateLimitRefillPerSecond = rateLimitRefillPerSecond;
    }

    public long getBackendTimeoutMillis() {
        return backendTimeoutMillis;
    }

    public void setBackendTimeoutMillis(long backendTimeoutMillis) {
        this.backendTimeoutMillis = backendTimeoutMillis;
    }
}
