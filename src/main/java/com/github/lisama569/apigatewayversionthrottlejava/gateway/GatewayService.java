package com.github.lisama569.apigatewayversionthrottlejava.gateway;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.error.ErrorNormalizer;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.handler.HandlerRegistry;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.ApiError;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.GatewayRequest;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.GatewayResponse;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.resilience.BackendInvoker;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.resilience.CircuitBreakerProperties;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.resilience.RouteCircuitBreaker;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.resilience.TimeoutProperties;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.route.VersionRouter;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle.TenantRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 网关编排：版本路由 -> 快速失败检查 -> 租户限流 -> 限时后端调用 -> 异常归一化。
 * <p>额度纪律：
 * <ul>
 *   <li>版本/路由不支持的请求在限流之前被拒绝，不占用额度；</li>
 *   <li>熔断器 OPEN 期间的快速失败不经过后端、也不占用额度；</li>
 *   <li>被限流拒绝的请求从未获得额度；</li>
 *   <li>后端调用失败/超时的请求通过窗口标记的 Permit 幂等归还额度，不多还、不漏还。</li>
 * </ul>
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);
    private static final String ANONYMOUS_TENANT = "anonymous";

    private final VersionRouter versionRouter;
    private final TenantRateLimiter rateLimiter;
    private final ErrorNormalizer errorNormalizer;
    private final BackendInvoker backendInvoker;
    private final RouteCircuitBreaker circuitBreaker;

    /** 完整构造：启用响应时间上限与快速失败。 */
    @Autowired
    public GatewayService(VersionRouter versionRouter, TenantRateLimiter rateLimiter,
                          ErrorNormalizer errorNormalizer, BackendInvoker backendInvoker,
                          RouteCircuitBreaker circuitBreaker) {
        this.versionRouter = versionRouter;
        this.rateLimiter = rateLimiter;
        this.errorNormalizer = errorNormalizer;
        this.backendInvoker = backendInvoker;
        this.circuitBreaker = circuitBreaker;
    }

    /** 兼容构造：不启用快速失败，并使用宽松的默认响应时间上限（供既有装配复用）。 */
    public GatewayService(VersionRouter versionRouter, TenantRateLimiter rateLimiter,
                          ErrorNormalizer errorNormalizer) {
        this(versionRouter, rateLimiter, errorNormalizer,
                new BackendInvoker(new TimeoutProperties()),
                new RouteCircuitBreaker(new CircuitBreakerProperties()) {
                    @Override
                    public CheckResult check(String tenantId, String route) {
                        return new CheckResult(true, 0L);
                    }
                });
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

        RouteCircuitBreaker.CheckResult breaker = circuitBreaker.check(tenantId, request.route());
        if (!breaker.allowed()) {
            throw new GatewayException(ApiError.CIRCUIT_OPEN,
                    "backend temporarily unavailable due to repeated timeouts, please retry later",
                    true, versionUsed,
                    Map.of("tenantId", tenantId, "route", request.route(),
                            "retryAfterMillis", breaker.retryAfterMillis()));
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

        long timeoutMillis = backendInvoker.resolveTimeout(request.route());
        long startNanos = System.nanoTime();
        try {
            Object data = backendInvoker.invoke(registration.handler(), request, timeoutMillis);
            circuitBreaker.record(tenantId, request.route(), false);
            return GatewayResponse.ok(versionUsed, registration.deprecated(), data);
        } catch (Throwable t) {
            rateLimiter.release(permit);
            boolean timedOut = t instanceof TimeoutException;
            circuitBreaker.record(tenantId, request.route(), timedOut);
            GatewayException normalized = errorNormalizer.normalize(t, versionUsed);
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            log.info("failure normalized: tenantId={}, versionUsed={}, error={}, retryable={}, elapsedMillis={}, quotaReturned=true",
                    tenantId, versionUsed, normalized.getError(), normalized.isRetryable(), elapsedMillis);
            throw normalized;
        }
    }
}
