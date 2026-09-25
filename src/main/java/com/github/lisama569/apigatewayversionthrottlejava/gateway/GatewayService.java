package com.github.lisama569.apigatewayversionthrottlejava.gateway;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.backend.BackendInternalException;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.backend.BackendTimeoutException;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.backend.BusinessRejectedException;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.config.GatewayProperties;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle.RateLimitDecision;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle.TenantRateLimiter;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.version.RouteDecision;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.version.VersionRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 网关编排：版本路由 -> 租户限流 -> 后端调用 -> 失败归一化。
 * <p>
 * 归一化规则：超时 / 内部错误 / 业务拒绝分别映射为 BACKEND_TIMEOUT /
 * BACKEND_INTERNAL_ERROR / BUSINESS_REJECTED；原始异常细节不透出。
 * 被限流拒绝的请求不消耗额度；后端失败的请求归还其额度。
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final VersionRouter versionRouter;
    private final TenantRateLimiter rateLimiter;
    private final long backendTimeoutMillis;
    private final ExecutorService backendExecutor;

    @org.springframework.beans.factory.annotation.Autowired
    public GatewayService(VersionRouter versionRouter, TenantRateLimiter rateLimiter,
                          GatewayProperties properties) {
        this(versionRouter, rateLimiter, properties.getBackendTimeoutMillis(),
                Executors.newCachedThreadPool());
    }

    /** 测试专用：显式注入全部依赖 */
    public GatewayService(VersionRouter versionRouter, TenantRateLimiter rateLimiter,
                          long backendTimeoutMillis, ExecutorService backendExecutor) {
        this.versionRouter = versionRouter;
        this.rateLimiter = rateLimiter;
        this.backendTimeoutMillis = backendTimeoutMillis;
        this.backendExecutor = backendExecutor;
    }

    public GatewayResponse handle(GatewayRequest request) {
        log.info("[gateway] request tenant={} version={} route={} payload={}",
                request.tenantId(), request.apiVersion(), request.route(), request.payload());

        // 1. 版本路由
        RouteDecision route = versionRouter.resolve(request.route(), request.apiVersion());
        switch (route.resolution()) {
            case UNSUPPORTED_VERSION -> {
                return reject(request, ErrorCategory.VERSION_UNSUPPORTED, route.reason(), null);
            }
            case UNKNOWN_ROUTE -> {
                return reject(request, ErrorCategory.ROUTE_UNKNOWN, route.reason(), null);
            }
            case OK -> {
                // continue
            }
        }

        // 2. 租户限流
        RateLimitDecision limit = rateLimiter.tryAcquire(request.tenantId());
        if (!limit.allowed()) {
            return reject(request, ErrorCategory.RATE_LIMITED,
                    "tenant rate limit exceeded; retry later", limit.retryAfterMillis());
        }

        // 3. 后端调用（带超时）+ 4. 失败归一化
        boolean deprecated = route.deprecated();
        try {
            Future<String> future = backendExecutor.submit(() -> route.handler().handle(request));
            String body = future.get(backendTimeoutMillis, TimeUnit.MILLISECONDS);
            log.info("[gateway] tenant={} route={} version={} -> OK (deprecated={})",
                    request.tenantId(), request.route(), request.apiVersion(), deprecated);
            return GatewayResponse.ok(request.route(), request.apiVersion(), deprecated, body);
        } catch (TimeoutException e) {
            return failBackend(request, deprecated, ErrorCategory.BACKEND_TIMEOUT,
                    "backend timed out after " + backendTimeoutMillis + "ms", e);
        } catch (ExecutionException e) {
            return failBackend(request, deprecated, classify(e.getCause()),
                    "backend invocation failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failBackend(request, deprecated, ErrorCategory.BACKEND_INTERNAL_ERROR,
                    "gateway interrupted while waiting for backend", e);
        }
    }

    private ErrorCategory classify(Throwable cause) {
        if (cause instanceof BusinessRejectedException) {
            return ErrorCategory.BUSINESS_REJECTED;
        }
        if (cause instanceof BackendTimeoutException) {
            return ErrorCategory.BACKEND_TIMEOUT;
        }
        if (cause instanceof BackendInternalException) {
            return ErrorCategory.BACKEND_INTERNAL_ERROR;
        }
        // 未知异常一律归为内部错误，原始异常不透出
        return ErrorCategory.BACKEND_INTERNAL_ERROR;
    }

    private GatewayResponse failBackend(GatewayRequest request, boolean deprecated,
                                        ErrorCategory category, String message, Throwable cause) {
        // 失败的请求不得占用其本应释放的额度
        rateLimiter.release(request.tenantId());
        log.info("[gateway] tenant={} route={} version={} -> {} ({}) causeType={}",
                request.tenantId(), request.route(), request.apiVersion(), category, message,
                cause == null ? "none" : cause.getClass().getSimpleName());
        return GatewayResponse.rejected(request.route(), request.apiVersion(), deprecated,
                category, message, null);
    }

    private GatewayResponse reject(GatewayRequest request, ErrorCategory category,
                                   String reason, Long retryAfterMillis) {
        log.info("[gateway] tenant={} route={} version={} -> {} ({})",
                request.tenantId(), request.route(), request.apiVersion(), category, reason);
        return GatewayResponse.rejected(request.route(), request.apiVersion(), false,
                category, reason, retryAfterMillis);
    }
}
