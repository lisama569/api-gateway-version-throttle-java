package com.github.lisama569.apigatewayversionthrottlejava.gateway.config;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.handler.HandlerRegistry;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.handler.impl.EchoV1Handler;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.handler.impl.EchoV2Handler;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.ApiVersion;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * 路由注册：声明每个路由支持的版本及弃用状态。
 * echo 路由：v1 已弃用（仍可用，响应会标记 deprecated），v2 为当前版本。
 */
@Configuration
public class GatewayRoutesConfig {

    private final HandlerRegistry registry;
    private final EchoV1Handler echoV1Handler;
    private final EchoV2Handler echoV2Handler;

    public GatewayRoutesConfig(HandlerRegistry registry,
                               EchoV1Handler echoV1Handler, EchoV2Handler echoV2Handler) {
        this.registry = registry;
        this.echoV1Handler = echoV1Handler;
        this.echoV2Handler = echoV2Handler;
    }

    @PostConstruct
    public void registerRoutes() {
        registry.register("echo", ApiVersion.V1, true, echoV1Handler);
        registry.register("echo", ApiVersion.V2, false, echoV2Handler);
    }
}
