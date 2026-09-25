package com.github.lisama569.apigatewayversionthrottlejava.gateway.error;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.GatewayException;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 后端异常归一化。
 * <p>映射规则（稳定语义，原始异常不透出）：
 * <ul>
 *   <li>{@link TimeoutException} -> BACKEND_TIMEOUT（可重试）</li>
 *   <li>{@link BusinessRejectionException} -> BUSINESS_REJECTED（不可重试，原因来自业务）</li>
 *   <li>其他未知异常 -> BACKEND_INTERNAL_ERROR（可重试，原因统一为通用文案）</li>
 *   <li>已是 {@link GatewayException} 的异常原样透传</li>
 * </ul>
 */
@Component
public class ErrorNormalizer {

    private static final Logger log = LoggerFactory.getLogger(ErrorNormalizer.class);

    public GatewayException normalize(Throwable t, String versionUsed) {
        if (t instanceof GatewayException ge) {
            return ge;
        }
        if (t instanceof TimeoutException) {
            log.warn("backend timeout, versionUsed={}", versionUsed, t);
            return new GatewayException(ApiError.BACKEND_TIMEOUT,
                    "backend timed out", true, versionUsed, null);
        }
        if (t instanceof BusinessRejectionException bre) {
            log.info("business rejected, versionUsed={}, reason={}", versionUsed, bre.getMessage());
            return new GatewayException(ApiError.BUSINESS_REJECTED,
                    bre.getMessage(), false, versionUsed,
                    Map.of("rejectionReason", String.valueOf(bre.getMessage())));
        }
        log.error("backend internal error, versionUsed={}", versionUsed, t);
        return new GatewayException(ApiError.BACKEND_INTERNAL_ERROR,
                "backend internal error", true, versionUsed, null);
    }
}
