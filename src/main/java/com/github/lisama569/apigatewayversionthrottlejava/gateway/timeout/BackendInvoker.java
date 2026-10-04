package com.github.lisama569.apigatewayversionthrottlejava.gateway.timeout;

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
 * 带响应时间上限的后端调用器。
 * <p>每个后端调用在独立的虚拟线程中执行，调用方在约定的 timeoutMillis 内等待结果：
 * <ul>
 *   <li>时限内返回：正常交出结果或抛出后端原始异常；</li>
 *   <li>超过时限：取消执行并抛出 {@link TimeoutException}，
 *       请求在约定时限内结束，不会无限期挂在网关里；</li>
 *   <li>虚拟线程按任务创建、数量不受平台线程池大小限制，
 *       大批请求同时打到卡住的后端时，每一个都能各自按时结束。</li>
 * </ul>
 */
@Component
public class BackendInvoker {

    private static final Logger log = LoggerFactory.getLogger(BackendInvoker.class);

    private final ExecutorService executor;

    public BackendInvoker() {
        this(Executors.newVirtualThreadPerTaskExecutor());
    }

    /** 测试可注入自定义执行器。 */
    public BackendInvoker(ExecutorService executor) {
        this.executor = executor;
    }

    /**
     * 在 timeoutMillis 内等待后端结果。
     *
     * @throws TimeoutException 后端在时限内未返回（真实卡住，而非抛异常）
     * @throws Exception        后端在时限内主动抛出的异常，原样上抛
     */
    public Object invoke(BackendHandler handler, GatewayRequest request, long timeoutMillis)
            throws Exception {
        Future<Object> future = executor.submit(() -> handler.handle(request));
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("backend invocation exceeded {}ms deadline, route={}, cancelled",
                    timeoutMillis, request.route());
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(cause);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.close();
    }
}
