package com.github.lisama569.apigatewayversionthrottlejava.gateway;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.ApiError;

import java.util.Map;

/**
 * 网关内部统一使用的归一化异常。
 * 原始后端异常不会越过本类型直接透出给客户端。
 */
public class GatewayException extends RuntimeException {

    private final ApiError error;
    private final boolean retryable;
    private final String versionUsed;
    private final transient Map<String, Object> detail;

    public GatewayException(ApiError error, String message) {
        this(error, message, false, null, null);
    }

    public GatewayException(ApiError error, String message, boolean retryable,
                            String versionUsed, Map<String, Object> detail) {
        super(message);
        this.error = error;
        this.retryable = retryable;
        this.versionUsed = versionUsed;
        this.detail = detail;
    }

    public ApiError getError() {
        return error;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String getVersionUsed() {
        return versionUsed;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }
}
