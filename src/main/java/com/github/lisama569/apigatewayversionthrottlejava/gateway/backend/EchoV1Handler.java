package com.github.lisama569.apigatewayversionthrottlejava.gateway.backend;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.GatewayRequest;
import org.springframework.stereotype.Component;

/** echo 路由的 v1 实现（已弃用，仍可用） */
@Component
public class EchoV1Handler implements BackendHandler {

    @Override
    public String route() {
        return "echo";
    }

    @Override
    public String version() {
        return "v1";
    }

    @Override
    public String handle(GatewayRequest request) {
        return "v1 echo: " + request.payload();
    }
}
