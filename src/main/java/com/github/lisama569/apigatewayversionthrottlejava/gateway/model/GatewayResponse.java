package com.github.lisama569.apigatewayversionthrottlejava.gateway.model;

import java.util.Map;

/**
 * 网关统一响应信封。
 * <p>成功与失败共用同一结构，失败时携带可区分的 {@link ApiError} 类别、
 * 人类可读原因与是否可重试标记；成功时可标记命中版本是否已弃用。
 */
public class GatewayResponse {

    private boolean success;
    private ApiError errorCode;
    private String errorReason;
    private boolean retryable;
    private Object data;
    private String versionUsed;
    private boolean deprecatedVersion;
    private Map<String, Object> detail;

    public static GatewayResponse ok(String versionUsed, boolean deprecatedVersion, Object data) {
        GatewayResponse r = new GatewayResponse();
        r.success = true;
        r.versionUsed = versionUsed;
        r.deprecatedVersion = deprecatedVersion;
        r.data = data;
        return r;
    }

    public static GatewayResponse rejected(ApiError error, String reason, boolean retryable,
                                           String versionUsed, Map<String, Object> detail) {
        GatewayResponse r = new GatewayResponse();
        r.success = false;
        r.errorCode = error;
        r.errorReason = reason;
        r.retryable = retryable;
        r.versionUsed = versionUsed;
        r.detail = detail;
        return r;
    }

    public boolean isSuccess() { return success; }
    public ApiError getErrorCode() { return errorCode; }
    public String getErrorReason() { return errorReason; }
    public boolean isRetryable() { return retryable; }
    public Object getData() { return data; }
    public String getVersionUsed() { return versionUsed; }
    public boolean isDeprecatedVersion() { return deprecatedVersion; }
    public Map<String, Object> getDetail() { return detail; }
}
