package com.github.lisama569.apigatewayversionthrottlejava.gateway.handler;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.GatewayRequest;

/** 某个路由在某个版本上的后端处理实现。 */
public interface BackendHandler {

    Object handle(GatewayRequest request) throws Exception;
}
