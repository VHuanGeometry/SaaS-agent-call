package com.vhuan.gateway.filter;

import com.vhuan.common.constant.HeaderConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 请求日志全局过滤器（Order -2）
 * <p>
 * 记录每个请求的 traceId、方法、路径、最终状态码与耗时。
 * traceId 直接读请求头（TraceIdFilter 已透传），不依赖响应式链路中不可靠的 MDC。
 * </p>
 */
@Component
public class RequestLogFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod().name();
        // TraceIdFilter 已将 traceId 写入请求头，这里直接从请求头读取，保证跨线程可用
        String traceId = exchange.getRequest().getHeaders().getFirst(HeaderConstants.TRACE_ID);

        return chain.filter(exchange).doFinally(signal -> {
            long cost = System.currentTimeMillis() - start;
            HttpStatus status = HttpStatus.resolve(
                    exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value()
                            : 500
            );

            log.info("[Gateway] traceId={} {} {} → {} {}ms",
                    traceId, method, path,
                    status != null ? status.value() : 500,
                    cost
            );
        });
    }

    @Override
    public int getOrder() {
        return -2;
    }
}
