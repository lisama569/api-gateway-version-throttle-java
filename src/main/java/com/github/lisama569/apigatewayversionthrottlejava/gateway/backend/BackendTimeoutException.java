package com.github.lisama569.apigatewayversionthrottlejava.gateway.backend;

/** 后端处理超时 */
public class BackendTimeoutException extends BackendException {
    public BackendTimeoutException(String message) {
        super(message);
    }

    public BackendTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
