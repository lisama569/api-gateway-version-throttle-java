package com.github.lisama569.apigatewayversionthrottlejava.gateway.backend;

/** 后端业务拒绝 */
public class BusinessRejectedException extends BackendException {
    public BusinessRejectedException(String message) {
        super(message);
    }

    public BusinessRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
