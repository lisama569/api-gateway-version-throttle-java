package com.github.lisama569.apigatewayversionthrottlejava.gateway.version;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.backend.BackendHandler;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按客户端声明的版本把请求分发到对应处理实现。
 * <ul>
 *   <li>未在版本表中登记的版本：拒绝，原因 VERSION_UNSUPPORTED；</li>
 *   <li>DEPRECATED 版本：仍路由到对应实现，但响应须标记 deprecated；</li>
 *   <li>版本合法但路由不存在：拒绝，原因 ROUTE_UNKNOWN，绝不静默落到最新版本。</li>
 * </ul>
 */
@Component
public class VersionRouter {

    private static final Logger log = LoggerFactory.getLogger(VersionRouter.class);

    private final GatewayProperties properties;
    /** key = route + "@" + version */
    private final Map<String, BackendHandler> handlers = new ConcurrentHashMap<>();

    public VersionRouter(GatewayProperties properties, List<BackendHandler> handlerBeans) {
        this.properties = properties;
        for (BackendHandler h : handlerBeans) {
            handlers.put(key(h.route(), h.version()), h);
        }
    }

    public RouteDecision resolve(String route, String apiVersion) {
        String statusText = properties.getVersions().get(apiVersion);
        if (statusText == null) {
            String reason = "version '" + apiVersion + "' is not supported; supported="
                    + properties.getVersions().keySet();
            log.info("[version-route] route={} version={} -> UNSUPPORTED_VERSION ({})", route, apiVersion, reason);
            return RouteDecision.unsupportedVersion(reason);
        }
        VersionStatus status = VersionStatus.valueOf(statusText.trim().toUpperCase());
        BackendHandler handler = handlers.get(key(route, apiVersion));
        if (handler == null) {
            String reason = "route '" + route + "' has no handler for version '" + apiVersion
                    + "'; unknown routes are never silently forwarded to the latest version";
            log.info("[version-route] route={} version={} -> UNKNOWN_ROUTE ({})", route, apiVersion, reason);
            return RouteDecision.unknownRoute(reason);
        }
        boolean deprecated = status == VersionStatus.DEPRECATED;
        log.info("[version-route] route={} version={} -> OK (deprecated={})", route, apiVersion, deprecated);
        return RouteDecision.ok(handler, deprecated);
    }

    private static String key(String route, String version) {
        return route + "@" + version;
    }
}
