package com.github.lisama569.apigatewayversionthrottlejava.gateway.backend;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.GatewayRequest;

/** 某个路由在某个版本下的后端处理实现 */
public interface BackendHandler {

    String route();

    String version();

    String handle(GatewayRequest request) throws Exception;
}
