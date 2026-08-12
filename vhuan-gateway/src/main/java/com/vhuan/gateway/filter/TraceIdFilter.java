package com.vhuan.gateway.filter;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.vhuan.common.constant.HeaderConstants;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * TraceId 生成与传递全局过滤器（Order -3，过滤器链最前）
 * <p>
 * 优先沿用上游传入的 X-Trace-Id，否则生成新的；
 * 将 traceId 写入 MDC 供日志输出，同时透传到下游服务请求头以串联全链路，
 * 并在响应头返回便于前端排查。
 * </p>
 */
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 优先从请求头获取 traceId（上游传递），否则生成新的
        String traceId = exchange.getRequest().getHeaders()
                .getFirst(HeaderConstants.TRACE_ID);

        if (StrUtil.isBlank(traceId)) {
            traceId = IdUtil.fastSimpleUUID();  // Hutool 生成无横线 UUID
        }

        // 设置 MDC（用于同步段日志输出；响应式链路跨线程时 MDC 不可靠，
        // 因此下游的日志读取依赖透传的 X-Trace-Id 请求头，而非 MDC）
        MDC.put("traceId", traceId);

        // 重建请求对象，将 traceId 透传给下游服务，保证全链路日志可串联
        ServerHttpRequest newRequest = exchange.getRequest().mutate()
                .header(HeaderConstants.TRACE_ID, traceId)
                .build();

        // 添加响应头，方便前端排查
        exchange.getResponse().getHeaders().add(HeaderConstants.TRACE_ID, traceId);

        // doFinally 的 lambda 需要捕获一个 effectively final 的 traceId
        final String finalTraceId = traceId;
        return chain.filter(exchange.mutate().request(newRequest).build())
                .doFinally(signal -> {
                    // 折中方案：仅当 MDC 中仍是本请求的 traceId 时才移除，
                    // 避免 doFinally 在共享线程上误清其他请求刚写入的 MDC
                    if (StrUtil.equals(finalTraceId, MDC.get("traceId"))) {
                        MDC.remove("traceId");
                    }
                });
    }

    @Override
    public int getOrder() {
        return -3;
    }
}
