package com.github.lisama569.apigatewayversionthrottlejava.gateway.model;

/**
 * 进入网关的请求上下文。
 *
 * @param tenantId 租户标识（限流维度，必填）
 * @param route    目标路由名（如 echo、fault）
 * @param version  客户端声明的原始版本字符串（如 v1、v2），原样保留便于日志解释
 * @param payload  业务请求体
 */
public record GatewayRequest(String tenantId, String route, String version, Object payload) {
}
