package com.github.lisama569.apigatewayversionthrottlejava.gateway;

/**
 * 进入网关的统一请求模型。
 *
 * @param tenantId  租户标识，限流维度
 * @param apiVersion 客户端声明的接口版本
 * @param route     目标路由名
 * @param payload   请求体
 */
public record GatewayRequest(String tenantId, String apiVersion, String route, String payload) {
}
