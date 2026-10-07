package com.github.lisama569.apigatewayversionthrottlejava.gateway;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.circuit.TenantCircuitBreaker;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.TimeoutProperties;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.error.ErrorNormalizer;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.handler.HandlerRegistry;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.ApiError;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.GatewayRequest;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.GatewayResponse;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.route.VersionRouter;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle.TenantRateLimiter;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.timeout.BackendInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 网关编排：版本路由 -> 快速失败检查 -> 租户限流 -> 限时后端调用 -> 异常归一化。
 * <p>额度纪律：版本/路由不支持、熔断快速失败的请求在限流之前被拒绝，不占用额度；
 * 限流拒绝的请求从未获得额度；后端调用失败（含超时）的请求立即归还额度。
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);
    private static final String ANONYMOUS_TENANT = "anonymous";

    private final VersionRouter versionRouter;
    private final TenantRateLimiter rateLimiter;
    private final ErrorNormalizer errorNormalizer;
    private final BackendInvoker backendInvoker;
    private final TenantCircuitBreaker circuitBreaker;
    private final TimeoutProperties timeoutProperties;

    public GatewayService(VersionRouter versionRouter, TenantRateLimiter rateLimiter,
                          ErrorNormalizer errorNormalizer, BackendInvoker backendInvoker,
                          TenantCircuitBreaker circuitBreaker, TimeoutProperties timeoutProperties) {
        this.versionRouter = versionRouter;
        this.rateLimiter = rateLimiter;
        this.errorNormalizer = errorNormalizer;
        this.backendInvoker = backendInvoker;
        this.circuitBreaker = circuitBreaker;
        this.timeoutProperties = timeoutProperties;
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

        if (!circuitBreaker.tryPass(tenantId, request.route())) {
            long retryAfter = circuitBreaker.retryAfterMillis(tenantId, request.route());
            log.info("circuit decision: tenantId={}, route={} fast-fail, retryAfterMillis={}",
                    tenantId, request.route(), retryAfter);
            throw new GatewayException(ApiError.CIRCUIT_OPEN,
                    "backend unstable, fast-failing", true, versionUsed,
                    Map.of("tenantId", tenantId, "route", request.route(),
                            "retryAfterMillis", retryAfter));
        }

        TenantRateLimiter.Permit permit = rateLimiter.tryAcquire(tenantId);
        if (permit == null) {
            log.info("throttle decision: tenantId={} rejected, remainingCapacity=0", tenantId);
            throw new GatewayException(ApiError.RATE_LIMITED,
                    "tenant rate limit exceeded", true, versionUsed,
                    Map.of("tenantId", tenantId, "remainingCapacity", 0));
        }
        log.info("throttle decision: tenantId={} admitted, remainingCapacity={}",
                tenantId, rateLimiter.remainingCapacity(tenantId));

        long timeoutMillis = timeoutProperties.timeoutFor(request.route());
        log.info("timeout decision: route={}, timeoutMillis={} (override={}, default={})",
                request.route(), timeoutMillis,
                timeoutProperties.getRouteOverrides().containsKey(request.route()),
                timeoutProperties.getDefaultMillis());

        try {
            Object data = backendInvoker.invoke(registration.handler(), request, timeoutMillis);
            circuitBreaker.onSuccess(tenantId, request.route());
            return GatewayResponse.ok(versionUsed, registration.deprecated(), data);
        } catch (Throwable t) {
            // 归还凭证绑定原窗口且最多生效一次：跨窗口结束不会污染新窗口，乱序/重复不会多还
            rateLimiter.release(permit);
            GatewayException normalized = errorNormalizer.normalize(t, versionUsed);
            if (normalized.getError() == ApiError.BACKEND_TIMEOUT) {
                circuitBreaker.onTimeout(tenantId, request.route());
            }
            log.info("failure normalized: tenantId={}, versionUsed={}, error={}, retryable={}",
                    tenantId, versionUsed, normalized.getError(), normalized.isRetryable());
            throw normalized;
        }
    }
}
