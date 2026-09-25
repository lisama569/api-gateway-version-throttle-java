package com.github.lisama569.apigatewayversionthrottlejava.gateway;

/**
 * 网关层归一化后的错误类别。每一类都对应稳定、可区分的语义，
 * 原始异常细节不得直接透出给调用方。
 */
public enum ErrorCategory {
    /** 客户端声明的接口版本不被支持 */
    VERSION_UNSUPPORTED,
    /** 路由在已支持的版本下不存在（不得静默落到最新版本） */
    ROUTE_UNKNOWN,
    /** 租户单位时间放行量超限 */
    RATE_LIMITED,
    /** 后端处理超时 */
    BACKEND_TIMEOUT,
    /** 后端内部错误 */
    BACKEND_INTERNAL_ERROR,
    /** 后端业务拒绝 */
    BUSINESS_REJECTED
}
