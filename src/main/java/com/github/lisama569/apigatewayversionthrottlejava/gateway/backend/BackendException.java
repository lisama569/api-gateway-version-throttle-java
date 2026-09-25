package com.github.lisama569.apigatewayversionthrottlejava.gateway.backend;

/** 后端处理失败的基类，网关层据此做错误归一化 */
public abstract class BackendException extends RuntimeException {
    protected BackendException(String message) {
        super(message);
    }

    protected BackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
