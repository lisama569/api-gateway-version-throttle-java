package com.github.lisama569.apigatewayversionthrottlejava.gateway.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 后端响应时间上限配置。
 * <ul>
 *   <li>{@code gateway.timeout.default-millis}：全局默认响应时间上限；</li>
 *   <li>{@code gateway.timeout.route-millis.<route>}：针对单个路由的覆盖值。</li>
 * </ul>
 * 路由未配置单独值时回落到全局默认值。
 */
@Component
@ConfigurationProperties(prefix = "gateway.timeout")
public class TimeoutProperties {

    private long defaultMillis = 10000L;
    private Map<String, Long> routeMillis = Map.of();

    public long getDefaultMillis() { return defaultMillis; }
    public void setDefaultMillis(long defaultMillis) { this.defaultMillis = defaultMillis; }
    public Map<String, Long> getRouteMillis() { return routeMillis; }
    public void setRouteMillis(Map<String, Long> routeMillis) { this.routeMillis = routeMillis; }

    /** 解析指定路由的响应时间上限：路由单独配置优先，否则回落到全局默认值。 */
    public long timeoutFor(String route) {
        Long perRoute = route == null ? null : routeMillis.get(route);
        return perRoute != null ? perRoute : defaultMillis;
    }
}
