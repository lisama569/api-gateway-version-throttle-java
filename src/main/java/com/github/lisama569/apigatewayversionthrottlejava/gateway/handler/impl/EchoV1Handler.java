package com.github.lisama569.apigatewayversionthrottlejava.gateway.handler.impl;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.handler.BackendHandler;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.GatewayRequest;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 示例后端：v1（已弃用，仍可用）回声实现。 */
@Component
public class EchoV1Handler implements BackendHandler {
    @Override
    public Object handle(GatewayRequest request) {
        return Map.of("echo", String.valueOf(request.payload()), "impl", "echo-v1-legacy");
    }
}
