package com.github.lisama569.apigatewayversionthrottlejava.gateway.backend;

/** 后端内部错误 */
public class BackendInternalException extends BackendException {
    public BackendInternalException(String message) {
        super(message);
    }

    public BackendInternalException(String message, Throwable cause) {
        super(message, cause);
    }
}
