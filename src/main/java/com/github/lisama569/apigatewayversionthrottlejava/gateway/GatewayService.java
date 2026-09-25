package com.github.lisama569.apigatewayversionthrottlejava.gateway;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.error.ErrorNormalizer;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.handler.HandlerRegistry;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.ApiError;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.GatewayRequest;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.GatewayResponse;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.route.VersionRouter;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle.TenantRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 网关编排：版本路由 -> 租户限流 -> 后端调用 -> 异常归一化。
 * <p>额度纪律：版本/路由不支持的请求在限流之前被拒绝，不占用额度；
 * 限流拒绝的请求从未获得额度；后端调用失败的请求立即归还额度。
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);
    private static final String ANONYMOUS_TENANT = "anonymous";

    private final VersionRouter versionRouter;
    private final TenantRateLimiter rateLimiter;
    private final ErrorNormalizer errorNormalizer;

    public GatewayService(VersionRouter versionRouter, TenantRateLimiter rateLimiter,
                          ErrorNormalizer errorNormalizer) {
        this.versionRouter = versionRouter;
        this.rateLimiter = rateLimiter;
        this.errorNormalizer = errorNormalizer;
    }

    public GatewayResponse dispatch(GatewayRequest request) {
        String tenantId = (request.tenantId() == null || request.tenantId().isBlank())
                ? ANONYMOUS_TENANT : request.tenantId();
        log.info("dispatch input: tenantId={}, route={}, version={}, payload={}",
                tenantId, request.route(), request.version(), request.payload());

        HandlerRegistry.Registration registration = versionRouter.route(request);
        String versionUsed = request.version() == null ? null : request.version().trim().toLowerCase();
        log.info("route decision: route={}, version={} -> versionUsed={}, deprecated={}",
                request.route(), request.version(), versionUsed, registration.deprecated());

        if (!rateLimiter.tryAcquire(tenantId)) {
            log.info("throttle decision: tenantId={} rejected, remainingCapacity=0", tenantId);
            throw new GatewayException(ApiError.RATE_LIMITED,
                    "tenant rate limit exceeded", true, versionUsed,
                    Map.of("tenantId", tenantId, "remainingCapacity", 0));
        }
        log.info("throttle decision: tenantId={} admitted, remainingCapacity={}",
                tenantId, rateLimiter.remainingCapacity(tenantId));

        try {
            Object data = registration.handler().handle(request);
            return GatewayResponse.ok(versionUsed, registration.deprecated(), data);
        } catch (Throwable t) {
            rateLimiter.release(tenantId);
            GatewayException normalized = errorNormalizer.normalize(t, versionUsed);
            log.info("failure normalized: tenantId={}, versionUsed={}, error={}, retryable={}",
                    tenantId, versionUsed, normalized.getError(), normalized.isRetryable());
            throw normalized;
        }
    }
}
