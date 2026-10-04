package com.github.lisama569.apigatewayversionthrottlejava.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 响应时间上限配置。
 * <p>全局默认值对所有路由生效，个别路由可通过 routeOverrides 单独覆盖。
 */
@Component
@ConfigurationProperties(prefix = "gateway.timeout")
public class TimeoutProperties {

    /** 全局默认响应时间上限（毫秒）。 */
    private long defaultMillis = 2000L;

    /** 按路由覆盖的响应时间上限（key 为路由名，value 为毫秒）。 */
    private Map<String, Long> routeOverrides = Map.of();

    /** 解析指定路由实际生效的响应时间上限：路由覆盖优先，否则用全局默认。 */
    public long timeoutFor(String route) {
        return routeOverrides.getOrDefault(route, defaultMillis);
    }

    public long getDefaultMillis() { return defaultMillis; }
    public void setDefaultMillis(long defaultMillis) { this.defaultMillis = defaultMillis; }
    public Map<String, Long> getRouteOverrides() { return routeOverrides; }
    public void setRouteOverrides(Map<String, Long> routeOverrides) { this.routeOverrides = routeOverrides; }
}
