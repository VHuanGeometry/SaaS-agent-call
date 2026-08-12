package com.vhuan.gateway.filter;

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
 * 记录每个请求的方法、路径、最终状态码与耗时。
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

        return chain.filter(exchange).doFinally(signal -> {
            long cost = System.currentTimeMillis() - start;
            HttpStatus status = HttpStatus.resolve(
                    exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value()
                            : 500
            );

            log.info("[Gateway] {} {} → {} {}ms",
                    method, path,
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
