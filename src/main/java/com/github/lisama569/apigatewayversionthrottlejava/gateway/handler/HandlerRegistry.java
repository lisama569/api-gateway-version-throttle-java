package com.github.lisama569.apigatewayversionthrottlejava.gateway.handler;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.ApiVersion;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 路由与版本到后端实现的注册表。
 * <p>未知路由 / 版本返回空，由路由层显式判定，绝不静默回落到最新版本。
 */
@Component
public class HandlerRegistry {

    /** 单个版本的注册项：处理器 + 是否已弃用。 */
    public record Registration(BackendHandler handler, boolean deprecated) {
    }

    private final Map<String, Map<ApiVersion, Registration>> table = new ConcurrentHashMap<>();

    public void register(String route, ApiVersion version, boolean deprecated, BackendHandler handler) {
        table.computeIfAbsent(route, k -> new ConcurrentHashMap<>())
                .put(version, new Registration(handler, deprecated));
    }

    public Optional<Registration> lookup(String route, ApiVersion version) {
        return Optional.ofNullable(table.get(route)).map(m -> m.get(version));
    }

    public boolean routeExists(String route) {
        Map<ApiVersion, Registration> versions = table.get(route);
        return versions != null && !versions.isEmpty();
    }

    public boolean isDeprecated(String route, ApiVersion version) {
        return lookup(route, version).map(Registration::deprecated).orElse(false);
    }
}
