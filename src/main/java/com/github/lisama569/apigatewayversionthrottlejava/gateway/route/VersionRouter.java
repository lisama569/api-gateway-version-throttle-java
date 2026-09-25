package com.github.lisama569.apigatewayversionthrottlejava.gateway.route;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.GatewayException;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.handler.HandlerRegistry;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.ApiError;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.ApiVersion;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.GatewayRequest;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 版本路由：按客户端声明的版本解析并校验路由。
 * <ul>
 *   <li>无法识别的版本字符串 -> {@link ApiError#UNSUPPORTED_VERSION}</li>
 *   <li>路由不存在 -> {@link ApiError#UNKNOWN_ROUTE}，绝不静默落到最新版本</li>
 *   <li>路由存在但该版本未注册 -> {@link ApiError#UNSUPPORTED_VERSION}</li>
 *   <li>已弃用版本仍返回处理器，由上层在响应中标记弃用</li>
 * </ul>
 */
@Component
public class VersionRouter {

    private final HandlerRegistry registry;

    public VersionRouter(HandlerRegistry registry) {
        this.registry = registry;
    }

    public HandlerRegistry.Registration route(GatewayRequest request) {
        if (!registry.routeExists(request.route())) {
            throw new GatewayException(ApiError.UNKNOWN_ROUTE,
                    "route not found: " + request.route(), false, null,
                    Map.of("route", request.route()));
        }
        ApiVersion version = parseVersion(request.version());
        return registry.lookup(request.route(), version)
                .orElseThrow(() -> new GatewayException(ApiError.UNSUPPORTED_VERSION,
                        "version not supported for route: " + request.version(), false, null,
                        Map.of("route", request.route(),
                                "declaredVersion", request.version(),
                                "supportedVersions", supportedVersions(request.route()))));
    }

    private ApiVersion parseVersion(String raw) {
        if (raw == null) {
            throw unsupported(null);
        }
        return switch (raw.trim().toLowerCase()) {
            case "v1" -> ApiVersion.V1;
            case "v2" -> ApiVersion.V2;
            default -> throw unsupported(raw);
        };
    }

    private GatewayException unsupported(String raw) {
        return new GatewayException(ApiError.UNSUPPORTED_VERSION,
                "unsupported api version: " + raw, false, null,
                Map.of("declaredVersion", String.valueOf(raw),
                        "supportedVersions", List.of("v1", "v2")));
    }

    private List<String> supportedVersions(String route) {
        return Arrays.stream(ApiVersion.values())
                .filter(v -> registry.lookup(route, v).isPresent())
                .map(v -> v.name().toLowerCase())
                .toList();
    }
}
