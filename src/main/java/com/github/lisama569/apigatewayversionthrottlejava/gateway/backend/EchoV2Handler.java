package com.github.lisama569.apigatewayversionthrottlejava.gateway.backend;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.GatewayRequest;
import org.springframework.stereotype.Component;

/** echo 路由的 v2 实现（当前版本） */
@Component
public class EchoV2Handler implements BackendHandler {

    @Override
    public String route() {
        return "echo";
    }

    @Override
    public String version() {
        return "v2";
    }

    @Override
    public String handle(GatewayRequest request) {
        return "v2 echo: " + request.payload();
    }
}
