package com.github.lisama569.apigatewayversionthrottlejava.gateway.error;

/**
 * 后端处理器主动抛出的业务拒绝信号。
 * 网关将其归一化为 BUSINESS_REJECTED，客户端可据此与系统故障区分。
 */
public class BusinessRejectionException extends RuntimeException {

    public BusinessRejectionException(String message) {
        super(message);
    }
}
