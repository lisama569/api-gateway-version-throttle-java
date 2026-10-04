package com.github.lisama569.apigatewayversionthrottlejava.gateway.resilience;

import com.github.lisama569.apigatewayversionthrottlejava.gateway.handler.BackendHandler;
import com.github.lisama569.apigatewayversionthrottlejava.gateway.model.GatewayRequest;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 后端调用执行器：在独立线程（每任务一个虚拟线程）上执行后端处理，
 * 并对单次调用施加硬性的响应时间上限。
 *
 * <p>语义保证：
 * <ul>
 *   <li>后端一直不返回时，调用线程最多等待 {@code timeoutMillis} 即收到
 *       {@link TimeoutException}，请求不会无限期挂在网关内；</li>
 *   <li>每一个请求都在自己的线程上执行，大批请求同时卡住时不会因排队而互相拖延，
 *       每个请求都能在各自的时限内结束；</li>
 *   <li>超时后会以中断方式取消后端任务（{@code mayInterruptIfRunning=true}），
 *       后端返回的异常被原样解包抛出，由上层做故障归一化。</li>
 * </ul>
 */
@Component
public class BackendInvoker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BackendInvoker.class);

    private final TimeoutProperties properties;
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("backend-call-", 0).factory());

    public BackendInvoker(TimeoutProperties properties) {
        this.properties = properties;
    }

    /**
     * 在不超过 timeoutMillis 的时间内执行 handler。
     *
     * @return 后端返回的数据
     * @throws TimeoutException  后端在时限内没有返回
     * @throws Exception         后端自身抛出的异常（解包后原样透传）
     * @throws Error             后端抛出的严重错误
     */
    public Object invoke(BackendHandler handler, GatewayRequest request, long timeoutMillis)
            throws Throwable {
        if (timeoutMillis <= 0) {
            throw new TimeoutException("backend response deadline must be positive: " + timeoutMillis);
        }
        Future<Object> future = executor.submit(() -> handler.handle(request));
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            log.warn("backend call timed out after {}ms, route={}, tenantId={}",
                    timeoutMillis, request.route(), request.tenantId());
            throw new TimeoutException("backend exceeded response deadline " + timeoutMillis + "ms");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            throw cause;
        } catch (InterruptedException ie) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new TimeoutException("gateway interrupted while waiting for backend response");
        }
    }

    /** 未显式指定超时时长时，按路由从配置解析（路由覆盖优先，否则全局默认）。 */
    public long resolveTimeout(String route) {
        return properties.timeoutFor(route);
    }

    @PreDestroy
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
